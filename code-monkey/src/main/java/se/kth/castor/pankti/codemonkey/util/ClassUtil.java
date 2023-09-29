package se.kth.castor.pankti.codemonkey.util;

import com.google.common.collect.Lists;
import com.google.common.graph.MutableNetwork;
import com.google.common.graph.Network;
import com.google.common.graph.NetworkBuilder;
import com.google.common.graph.Traverser;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil.DirectFieldParameterWrite;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtNamedElement;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

@SuppressWarnings("UnstableApiUsage")
public final class ClassUtil {

  private ClassUtil() {
    throw new UnsupportedOperationException("No instantiation");
  }

  public static Map<CtConstructor<?>, ConstructorMapping> getConstructorFieldAssignments(
      CtClass<?> type,
      Predicate<CtConstructor<?>> constructorFilter
  ) {
    Map<CtConstructor<?>, ConstructorMapping> constructors = new HashMap<>();

    Set<CtTypeReference<?>> selfAndSuperClasses = SpoonUtil.getSuperclasses(type)
        .stream()
        .map(CtType::getReference)
        .collect(Collectors.toSet());

    Network<ConstructorNode, CallEdge> graph = buildConstructorsGraph(type, constructorFilter);

    for (CtConstructor<?> constructor : type.getConstructors()) {
      if (!constructorFilter.test(constructor)) {
        continue;
      }
      constructors.put(
          constructor,
          getAssignedInConstructor(constructors, graph, selfAndSuperClasses, constructor)
      );
    }

    // Do not consider partial constructor calls
    constructors.entrySet()
        .removeIf(entry -> !entry.getValue().assignsAllParameters());

    constructors.keySet()
        .removeIf(it -> !it.getDeclaringType().equals(type));

    return constructors;
  }

  private static ConstructorMapping getAssignedInConstructor(
      Map<CtConstructor<?>, ConstructorMapping> constructors,
      Network<ConstructorNode, CallEdge> graph,
      Set<CtTypeReference<?>> selfAndSuperClasses,
      CtConstructor<?> constructor
  ) {
    // Traverse in reverse topological (=depth first post) order
    List<ConstructorNode> nodesInOrder = Lists.newArrayList(
        Traverser.forGraph(graph).depthFirstPostOrder(new ConstructorNode(constructor)).iterator()
    );
    ConstructorMapping paramMap = null;
    for (ConstructorNode constructorNode : nodesInOrder) {
      paramMap = new ConstructorMapping(constructor);

      paramMap.merge(
          getDirectlyAssignedInConstructor(constructorNode.constructor(), selfAndSuperClasses)
      );
      if (graph.outDegree(constructorNode) == 0) {
        constructors
            .computeIfAbsent(constructorNode.constructor(), ConstructorMapping::new)
            .merge(paramMap);
        continue;
      }
      // found a delegation call, convert the parent's parameters to ours
      CallEdge callEdge = graph.outEdges(constructorNode).iterator().next();
      for (int i = 0; i < callEdge.target().getParameters().size(); i++) {
        CtParameter<?> calledParameter = callEdge.target().getParameters().get(i);
        if (i >= callEdge.invocation().getArguments().size() && calledParameter.isVarArgs()) {
          continue;
        }

        CtExpression<?> ourArgument = callEdge.invocation().getArguments().get(i);

        // TODO: Chain broken, there are weird other values now?
        if (!(ourArgument instanceof CtVariableRead<?> variableRead)) {
          continue;
        }
        if (!(variableRead.getVariable().getDeclaration() instanceof CtParameter<?> ourParam)) {
          continue;
        }

        // Copy the values from our target. This will be in constructors as we iterate in reverse
        // topological order
        paramMap.addMappings(
            ourParam,
            constructors.get(callEdge.target()).get(calledParameter)
        );
      }

      // Record our chain in the constructor
      constructors
          .computeIfAbsent(constructorNode.constructor(), ConstructorMapping::new)
          .merge(paramMap);
    }
    return paramMap;
  }

  private static Network<ConstructorNode, CallEdge> buildConstructorsGraph(
      CtClass<?> type,
      Predicate<CtConstructor<?>> constructorFilter
  ) {
    MutableNetwork<ConstructorNode, CallEdge> graph = NetworkBuilder.directed()
        .allowsParallelEdges(false)
        .allowsSelfLoops(false)
        .build();

    for (CtConstructor<?> constructor : type.getConstructors()) {
      if (!constructorFilter.test(constructor)) {
        continue;
      }
      buildGraphForConstructor(graph, constructor);
    }
    return graph;
  }

  private static ConstructorNode buildGraphForConstructor(
      MutableNetwork<ConstructorNode, CallEdge> graph,
      CtConstructor<?> constructor
  ) {
    ConstructorNode node = new ConstructorNode(constructor);
    if (graph.nodes().contains(node)) {
      return node;
    }
    graph.addNode(node);
    if (constructor.getBody().getStatements().isEmpty()) {
      return node;
    }
    if (!(constructor.getBody().getStatement(0) instanceof CtInvocation<?> call)) {
      return node;
    }
    if (!(call.getExecutable().getExecutableDeclaration() instanceof CtConstructor<?> called)) {
      return node;
    }

    // The enum constructor has a mismatch between argument and parameter count.
    // We remove those fields in the state so this is not needed here...
    if (called.getDeclaringType().getQualifiedName().equals("java.lang.Enum")) {
      return node;
    }

    graph.addEdge(
        node,
        buildGraphForConstructor(graph, called),
        new CallEdge(constructor, call, called)
    );

    return node;
  }

  private static ConstructorMapping getDirectlyAssignedInConstructor(
      CtConstructor<?> constructor,
      Set<CtTypeReference<?>> selfAndSuperTypes
  ) {
    ConstructorMapping result = new ConstructorMapping(constructor);

    for (CtFieldWrite<?> write : constructor.getElements(new TypeFilter<>(CtFieldWrite.class))) {
      SpoonUtil.getFieldParameterAssignment(write, selfAndSuperTypes)
          .flatMap(DirectFieldParameterWrite::asConstructorWrite)
          .ifPresent(it -> result.addMapping(it.readParameter(), it.writtenField()));
    }

    return result;
  }

  public static boolean isBoxed(Class<?> clazz) {
    return clazz == Byte.class || clazz == Short.class || clazz == Integer.class
           || clazz == Long.class
           || clazz == Float.class || clazz == Double.class
           || clazz == Boolean.class || clazz == Character.class;
  }

  public static boolean isBasicallyPrimitive(Class<?> clazz) {
    return clazz.isPrimitive() || clazz == String.class || isBoxed(clazz);
  }

  public static Class<?> getPrimitiveClass(String className) {
    return switch (className) {
      case "byte" -> byte.class;
      case "short" -> short.class;
      case "int" -> int.class;
      case "long" -> long.class;
      case "float" -> float.class;
      case "double" -> double.class;
      case "boolean" -> boolean.class;
      case "char" -> char.class;
      case "void" -> void.class;
      default -> null;
    };
  }

  private record ConstructorNode(CtConstructor<?> constructor) {

  }

  private record CallEdge(
      CtConstructor<?> source,
      CtInvocation<?> invocation,
      CtConstructor<?> target
  ) {

  }

  public record ConstructorMapping(
      CtConstructor<?> constructor,
      Map<CtParameter<?>, Set<CtField<?>>> mapping
  ) {

    public ConstructorMapping(CtConstructor<?> constructor) {
      this(constructor, new HashMap<>());
    }

    public void addMapping(CtParameter<?> parameter, CtField<?> field) {
      mapping.computeIfAbsent(parameter, __ -> new HashSet<>())
          .add(field);
    }

    public void addMappings(CtParameter<?> ourParam, Set<CtField<?>> fields) {
      fields.forEach(field -> addMapping(ourParam, field));
    }

    public void merge(ConstructorMapping other) {
      for (var entry : other.mapping().entrySet()) {
        addMappings(entry.getKey(), entry.getValue());
      }
    }

    public Set<CtField<?>> get(CtParameter<?> calledParameter) {
      return Set.copyOf(mapping.getOrDefault(calledParameter, Set.of()));
    }

    public boolean assignsAllParameters() {
      return constructor.getParameters().size() == mapping.size();
    }

    public Map<CtParameter<?>, List<CtField<?>>> toSortedFieldMap() {
      return mapping.entrySet().stream()
          .map(entry -> Map.entry(
              entry.getKey(),
              entry.getValue()
                  .stream()
                  .sorted(Comparator.comparing(CtNamedElement::getSimpleName))
                  .toList()
          ))
          .collect(Collectors.toMap(
              Entry::getKey,
              Entry::getValue
          ));
    }

  }

}
