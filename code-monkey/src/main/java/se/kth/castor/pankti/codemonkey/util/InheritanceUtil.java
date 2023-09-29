package se.kth.castor.pankti.codemonkey.util;

import com.google.common.collect.MapMaker;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.ImmutableGraph;
import com.google.common.graph.ImmutableGraph.Builder;
import com.google.common.graph.Traverser;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.support.adaption.TypeAdaptor;

public class InheritanceUtil {

  private static final Map<CtMethod<?>, Collection<CtMethod<?>>> TOP_METHODS = new MapMaker()
      .weakKeys()
      .weakValues()
      .makeMap();

  /**
   * Generalizes {@code actualClass} to the least specific type that still declares all given fields
   * and methods.
   *
   * @param actualClass the actual, concrete object type to generalize
   * @param bound the bounding (probably assigned) type we can not generalize further
   * @param methods the called methods
   * @param fields the accessed fields
   * @return the generalized type or initial if no constraints were given
   */
  public static CtTypeReference<?> findLeastSpecificTypeOrInitial(
      CtType<?> actualClass,
      CtTypeReference<?> bound,
      Set<CtMethod<?>> methods,
      Set<CtField<?>> fields
  ) {
    // Specialize assignedType type if needed
    Set<CtType<?>> requiredTypes = fields.stream()
        .map(CtTypeMember::getDeclaringType)
        .collect(Collectors.toCollection(HashSet::new));

    // We also must satisfy the bound!
    requiredTypes.add(bound.getTypeDeclaration());

    for (CtMethod<?> method : methods) {
      Collection<CtMethod<?>> topDefinitions = TOP_METHODS.computeIfAbsent(
          method, CtMethod::getTopDefinitions
      );
      if (topDefinitions.isEmpty()) {
        requiredTypes.add(method.getDeclaringType());
      }
      topDefinitions.stream().map(CtTypeMember::getDeclaringType).forEach(requiredTypes::add);
    }

    if (requiredTypes.size() == 1) {
      return changeReferencedType(bound, requiredTypes.iterator().next());
    } else {
      return changeReferencedType(
          bound,
          InheritanceUtil.findLowestCommonPredecessorOf(actualClass, requiredTypes)
      );
    }
  }

  private static CtTypeReference<?> changeReferencedType(
      CtTypeReference<?> original, CtType<?> type
  ) {
    TypeAdaptor adaptor = new TypeAdaptor(type);
    CtType<?> originalDecl = original.getTypeDeclaration();

    Map<CtTypeParameter, CtTypeReference<?>> typeParamRefs = new HashMap<>();

    for (int i = 0; i < originalDecl.getFormalCtTypeParameters().size(); i++) {
      CtTypeParameter parameter = originalDecl.getFormalCtTypeParameters().get(i);
      CtTypeReference<?> actualArgument = i < original.getActualTypeArguments().size()
          ? original.getActualTypeArguments().get(i)
          : type.getFactory().Type().objectType();

      CtTypeReference<?> adapted = adaptor.adaptType(parameter);

      if (adapted instanceof CtTypeParameterReference typeParamRef) {
        typeParamRefs.put(typeParamRef.getDeclaration(), actualArgument);
      }
    }

    CtTypeReference<?> newAssignedType = type.getReference();
    for (CtTypeParameter parameter : type.getFormalCtTypeParameters()) {
      newAssignedType.addActualTypeArgument(
          typeParamRefs.getOrDefault(parameter, type.getFactory().Type().objectType())
      );
    }

    return newAssignedType;
  }


  /**
   * Finds the lowest common predecessor for a set of types.
   * <p>
   * Consider this example where {@code Foo1} and {@code Foo2} are required and {@code Bottom} is
   * the bottom. The lowest common predecessor of this inheritance graph would be {@code AType}.
   * <pre>
   *         Object
   *         /    \
   *  (*) Foo1  Foo2 (*)
   *         \    /
   *         AType
   *           |
   *         Bottom
   * </pre>
   *
   * @param rawRequiredTypes the types the given type needs to implement.
   * @return the lowest predecessor
   */
  @SuppressWarnings("UnstableApiUsage")
  public static CtType<?> findLowestCommonPredecessorOf(
      CtType<?> bottom,
      Collection<CtType<?>> rawRequiredTypes
  ) {
    ImmutableGraph<String> inheritanceGraph = getGraph(bottom);
    Map<String, Integer> markCounts = new HashMap<>();
    // Object is implicitly covered
    List<CtType<?>> requiredTypes = rawRequiredTypes.stream()
        .filter(it -> !it.getQualifiedName().equals(Object.class.getName()))
        .toList();

    for (CtType<?> type : requiredTypes) {
      Traverser.forGraph(inheritanceGraph::predecessors).breadthFirst(type.getQualifiedName())
          .forEach(it -> markCounts.merge(it, 1, Math::addExact));
    }
    String lastType = bottom.getQualifiedName();
    for (var ctType : Traverser.forGraph(inheritanceGraph::successors).breadthFirst(lastType)) {
      if (markCounts.getOrDefault(ctType, -1) != requiredTypes.size()) {
        continue;
      }
      lastType = ctType;
    }

    return bottom.getFactory().createReference(lastType).getTypeDeclaration();
  }

  @SuppressWarnings("UnstableApiUsage")
  private static ImmutableGraph<String> getGraph(CtType<?> bottom) {
    Builder<String> graph = GraphBuilder.directed()
        .allowsSelfLoops(false)
        .immutable();

    Set<String> explored = new HashSet<>();
    Queue<CtType<?>> workQueue = new ArrayDeque<>();
    workQueue.add(bottom);

    CtType<?> current;
    while ((current = workQueue.poll()) != null) {
      if (!explored.add(current.getQualifiedName())) {
        continue;
      }
      graph.addNode(current.getQualifiedName());

      if (current.getSuperclass() != null) {
        CtType<?> superType = current.getSuperclass().getTypeDeclaration();
        workQueue.add(superType);
        graph.putEdge(current.getQualifiedName(), superType.getQualifiedName());
      }

      for (CtTypeReference<?> superInterface : current.getSuperInterfaces()) {
        CtType<?> superDeclaration = superInterface.getTypeDeclaration();
        workQueue.add(superDeclaration);
        graph.putEdge(current.getQualifiedName(), superDeclaration.getQualifiedName());
      }
    }

    return graph.build();
  }
}
