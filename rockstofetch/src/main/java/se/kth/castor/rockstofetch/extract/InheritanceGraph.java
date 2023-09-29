package se.kth.castor.rockstofetch.extract;

import com.google.common.collect.Sets;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

@SuppressWarnings("UnstableApiUsage")
public class InheritanceGraph {

  private final MutableGraph<FastHashTypeWrapper> graph;

  public InheritanceGraph() {
    this.graph = GraphBuilder.directed().allowsSelfLoops(false).build();
  }

  public boolean hasSupertype(CtType<?> potentialSub, Set<CtType<?>> potentialSuper) {
    FastHashTypeWrapper subWrapper = FastHashTypeWrapper.of(potentialSub);
    Set<FastHashTypeWrapper> superWrappers = potentialSuper.stream()
        .map(FastHashTypeWrapper::of)
        .collect(Collectors.toSet());

    if (!graph.nodes().contains(subWrapper)) {
      return false;
    }

    return !Sets.intersection(Graphs.reachableNodes(graph, subWrapper), superWrappers).isEmpty();
  }

  public void addType(CtType<?> type) {
    addTypeImpl(type);
  }

  private FastHashTypeWrapper addTypeImpl(CtType<?> type) {
    FastHashTypeWrapper wrapper = FastHashTypeWrapper.of(type);
    if (graph.nodes().contains(wrapper)) {
      return wrapper;
    }

    graph.addNode(wrapper);
    if (type.getSuperclass() != null) {
      graph.putEdge(wrapper, addTypeImpl(type.getSuperclass().getTypeDeclaration()));
    }
    for (CtTypeReference<?> superInterface : type.getSuperInterfaces()) {
      graph.putEdge(wrapper, addTypeImpl(superInterface.getTypeDeclaration()));
    }

    return wrapper;
  }

  private record FastHashTypeWrapper(CtType<?> type) {

    private FastHashTypeWrapper {
      Objects.requireNonNull(type);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type.getQualifiedName());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FastHashTypeWrapper that = (FastHashTypeWrapper) o;
      return Objects.equals(type.getQualifiedName(), that.type.getQualifiedName());
    }

    private static FastHashTypeWrapper of(CtType<?> type) {
      return new FastHashTypeWrapper(type);
    }
  }

}
