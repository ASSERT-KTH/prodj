package se.kth.castor.rockstofetch.generate;

import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import java.util.Set;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

@SuppressWarnings("UnstableApiUsage")
public class Callgraph {

  private final MutableGraph<CtMethod<?>> callgraph;

  private Callgraph(MutableGraph<CtMethod<?>> callgraph) {
    this.callgraph = callgraph;
  }

  public void wasInlined(CtMethod<?> murderer, CtMethod<?> victim) {
    callgraph.successors(victim).forEach(it -> callgraph.putEdge(murderer, it));
    callgraph.removeEdge(murderer, victim);
  }

  public void removeNode(CtMethod<?> method) {
    callgraph.removeNode(method);
  }

  public Set<CtMethod<?>> getDirectCallers(CtMethod<?> method) {
    return callgraph.predecessors(method);
  }

  public boolean calledByTest(CtMethod<?> method) {
    return Graphs.reachableNodes(Graphs.transpose(callgraph), method)
        .stream()
        .anyMatch(Callgraph::isTest);
  }

  public static boolean isTest(CtMethod<?> method) {
    return method.getAnnotations()
        .stream()
        .anyMatch(it -> it.getAnnotationType().getSimpleName().equals("Test"));
  }

  public static Callgraph forType(CtType<?> type) {
    MutableGraph<CtMethod<?>> graph = GraphBuilder.directed().allowsSelfLoops(true).build();

    for (CtMethod<?> method : type.getMethods()) {
      graph.addNode(method);
      buildEdgesForMethod(type, graph, method);
    }

    return new Callgraph(graph);
  }

  private static void buildEdgesForMethod(
      CtType<?> type, MutableGraph<CtMethod<?>> graph, CtMethod<?> method
  ) {
    for (var invocation : method.getBody().getElements(new TypeFilter<>(CtInvocation.class))) {
      CtTypeReference<?> declaringType = invocation.getExecutable().getDeclaringType();
      if (!declaringType.getQualifiedName().equals(type.getQualifiedName())) {
        continue;
      }
      CtExecutable<?> executable = invocation.getExecutable().getExecutableDeclaration();
      if (!(executable instanceof CtMethod<?> called)) {
        continue;
      }
      graph.putEdge(method, called);
    }
  }
}
