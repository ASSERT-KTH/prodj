package se.kth.castor.rockstofetch.generate;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtMethod;

public class TestFilterer {

  public void filter(CtClass<?> testClass, List<CategorizedProblem> problems) {
    Callgraph callgraph = Callgraph.forType(testClass);
    for (CategorizedProblem problem : problems) {
      if (!problem.isError()) {
        continue;
      }
      String fileName = new String(problem.getOriginatingFileName());
      if (!fileName.equals(testClass.getPosition().getFile().toString())) {
        continue;
      }
      testClass.getMethods()
          .stream()
          .filter(it -> it.getPosition().isValidPosition())
          .filter(it -> it.getPosition().getSourceStart() <= problem.getSourceStart())
          .filter(it -> it.getPosition().getSourceEnd() >= problem.getSourceEnd())
          .min(Comparator.comparingInt(it -> it.getPosition().getSourceStart()))
          .ifPresent(method -> nukeMethod(problem, method, callgraph));
    }
  }

  private void nukeMethod(CategorizedProblem problem, CtMethod<?> method, Callgraph callgraph) {
    Queue<CtMethod<?>> victimQueue = new ArrayDeque<>();
    victimQueue.add(method);
    CtMethod<?> current;
    while ((current = victimQueue.poll()) != null) {
      victimQueue.addAll(callgraph.getDirectCallers(current));
      System.out.println("Deleted " + current.getSimpleName() + " due to " + problem);
      callgraph.removeNode(current);
      // Might be added multiple times
      victimQueue.remove(current);
      current.delete();
    }
  }
}
