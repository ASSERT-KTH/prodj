package se.kth.castor.rockstofetch.generate;

import static se.kth.castor.rockstofetch.util.Spoons.inline;
import static se.kth.castor.rockstofetch.util.Spoons.isBasicallyPrimitive;

import se.kth.castor.rockstofetch.util.Spoons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtComment;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.CtScanner;
import spoon.reflect.visitor.filter.TypeFilter;

public class PostProcessor {

  private static final int MAX_LINES_BEFORE_OUTLINING = 5;

  private final Statistics statistics;

  public PostProcessor(Statistics statistics) {
    this.statistics = statistics;
  }

  public void process(CtType<?> type) {
    Callgraph callgraph = Callgraph.forType(type);
    // Fix-point all methods together
    boolean someMethodChanged;
    do {
      someMethodChanged = false;

      for (CtMethod<?> method : type.getMethods()) {
        // Fix-point a single method
        ChangeResult changed;
        do {
          changed = processMethod(method, callgraph);
          someMethodChanged |= changed.isRerunGlobal();
        } while (changed.isRerunLocal());
      }

    } while (someMethodChanged);
  }

  private ChangeResult processMethod(CtMethod<?> method, Callgraph callgraph) {
    if (!callgraph.calledByTest(method)) {
      method.delete();
      callgraph.removeNode(method);
      return ChangeResult.DELETED;
    }
    CtMethod<?> original = method.clone();

    fixNarrowThrownExceptions(method);
    fixInlineVariables(method);
    fixRemoveNumberSuffixes(method);
    fixMoreSpecificAssertMethods(method);
    fixStaticallyImportMockitoAndAsserts(method);
    fixUnnecessaryByteCasts(method);

    if (!Callgraph.isTest(method)) {
      if (fixInlineOutlinedMethod(method, callgraph)) {
        // method was deleted, do not retry
        return ChangeResult.DELETED;
      }
    }

    return !original.equals(method) ? ChangeResult.CHANGED : ChangeResult.UNCHANGED;
  }

  private void fixInlineVariables(CtMethod<?> method) {
    List<Integer> commentLines = new ArrayList<>();
    Map<CtLocalVariable<?>, List<CtVariableRead<?>>> reads = new HashMap<>();
    Set<CtLocalVariable<?>> writtenVariables = new HashSet<>();

    method.accept(new CtScanner() {
      private final Map<String, CtLocalVariable<?>> variableNameMap = new HashMap<>();

      @Override
      public void visitCtComment(CtComment comment) {
        commentLines.add(comment.getPosition().getLine());
      }

      @Override
      public <T> void visitCtLocalVariable(CtLocalVariable<T> localVariable) {
        super.visitCtLocalVariable(localVariable);
        variableNameMap.put(localVariable.getSimpleName(), localVariable);
      }

      @Override
      public <R> void visitCtVariableRead(CtVariableRead<R> variableRead) {
        if (variableRead.getVariable() instanceof CtLocalVariableReference<R> reference) {
          CtLocalVariable<?> var = variableNameMap.get(reference.getSimpleName());
          reads.computeIfAbsent(var, ignored -> new ArrayList<>()).add(variableRead);
        }
      }

      @Override
      public <T> void visitCtVariableWrite(CtVariableWrite<T> variableWrite) {
        if (variableWrite.getVariable() instanceof CtLocalVariableReference<T> reference) {
          CtLocalVariable<?> var = variableNameMap.get(reference.getSimpleName());
          writtenVariables.add(var);
        }
      }
    });
    commentLines.sort(Integer::compareTo);
    reads.values().removeIf(it -> it.size() != 1);
    // Do not inline variables which are re-assigned
    reads.keySet().removeAll(writtenVariables);

    method.accept(new CtScanner() {
      @Override
      public <T> void visitCtLocalVariable(CtLocalVariable<T> localVariable) {
        super.visitCtLocalVariable(localVariable);
        if (!reads.containsKey(localVariable)) {
          return;
        }

        int localVariableLine = localVariable.getPosition().getLine();
        int readLine = reads.get(localVariable).get(0).getPosition().getLine();
        boolean commentBetweenDeclarationAndUse = commentLines.stream()
            .dropWhile(it -> it <= localVariableLine)
            .anyMatch(it -> it < readLine);
        boolean forceInline = false;

        boolean isSimple = isBasicallyPrimitive(localVariable.getType());
        // Also allow inlining of primitive arrays
        if (localVariable.getType() instanceof CtArrayTypeReference<T> arrayType) {
          isSimple |= isBasicallyPrimitive(arrayType.getArrayType());
        }
        // Also inline null values
        if (localVariable.getAssignment() instanceof CtLiteral<T> lit && lit.getValue() == null) {
          isSimple = true;
        }
        isSimple |= localVariable.getType().isEnum();

        // Also inline "Object foo = bar;" and replace the use site with "bar"
        if (localVariable.getAssignment() instanceof CtVariableRead<T> variableRead
            && variableRead.getVariable() instanceof CtLocalVariableReference<T>) {
          forceInline = true;
        }

        boolean isInReturn = reads.get(localVariable).get(0).getParent() instanceof CtReturn<?>;

        if (forceInline || !commentBetweenDeclarationAndUse && (isSimple || isInReturn)) {
          reads.get(localVariable).get(0).replace(localVariable.getAssignment());
          transplantComments(localVariable);
          localVariable.delete();
          if (statistics != null) {
            statistics.getProcessing().addInlinedVariable();
          }
        }
      }

      private <T> void transplantComments(CtLocalVariable<T> localVariable) {
        CtStatement commentTarget = method.getBody()
            .getStatement(method.getBody().getStatements().indexOf(localVariable) + 1);
        List<CtComment> comments = new ArrayList<>(commentTarget.getComments());
        comments.addAll(0, localVariable.getComments());
        commentTarget.setComments(comments);
      }
    });
  }

  private void fixRemoveNumberSuffixes(CtMethod<?> method) {
    // String name9 = "hello";
    //   => String name = "hello";
    // If that has no conflicts :)

    List<CtLocalVariable<?>> variables = new ArrayList<>();
    method.accept(new CtScanner() {
      @Override
      public <T> void visitCtLocalVariable(CtLocalVariable<T> localVariable) {
        super.visitCtLocalVariable(localVariable);
        variables.add(localVariable);
      }
    });

    Map<String, List<CtLocalVariable<?>>> groupedByCleanedName = variables.stream()
        .collect(Collectors.groupingBy(
            it -> it.getSimpleName().replaceAll("\\d|_", ""),
            Collectors.toList()
        ));

    Map<CtLocalVariable<?>, String> newNames = new HashMap<>();
    for (var entry : groupedByCleanedName.entrySet()) {
      String prefix = entry.getKey();

      for (int i = 0; i < entry.getValue().size(); i++) {
        CtLocalVariable<?> variable = entry.getValue().get(i);
        String suffix = i == 0 ? "" : Integer.toString(i - 1);
        String newName = prefix + suffix;
        if (Spoons.isKeyword(newName)) {
          continue;
        }
        newNames.put(variable, newName);
      }
    }

    newNames.entrySet().removeIf(entry -> entry.getValue().equals(entry.getKey().getSimpleName()));

    if (newNames.isEmpty()) {
      return;
    }

    // Manually refactor to only traverse the AST once (always constant in the number of variables)
    method.accept(new CtScanner() {
      @Override
      public <T> void visitCtLocalVariableReference(CtLocalVariableReference<T> reference) {
        super.visitCtLocalVariableReference(reference);
        String newName = newNames.get(reference.getDeclaration());
        if (newName != null) {
          reference.setSimpleName(newName);
        }
      }
    });
    for (Entry<CtLocalVariable<?>, String> entry : newNames.entrySet()) {
      entry.getKey().setSimpleName(entry.getValue());
    }
  }

  private void fixMoreSpecificAssertMethods(CtMethod<?> method) {
    method.accept(new CtScanner() {
      @Override
      public <T> void visitCtInvocation(CtInvocation<T> invocation) {
        super.visitCtInvocation(invocation);
        handleJunitAssertEquals(invocation);
        handleAssertJAssertEquals(invocation);
      }

      private void handleAssertJAssertEquals(CtInvocation<?> invocation) {
        if (!invocation.getExecutable().getSimpleName().equals("isEqualTo")) {
          return;
        }
        String declaredIn = invocation.getExecutable().getDeclaringType().getQualifiedName();
        if (!declaredIn.startsWith("org.assertj.core.api") || !declaredIn.endsWith("Assert")) {
          return;
        }
        if (invocation.getArguments().isEmpty()) {
          return;
        }
        if (!(invocation.getArguments().get(0) instanceof CtLiteral<?> literal)) {
          return;
        }
        if (literal.getValue() instanceof Boolean bool) {
          if (bool) {
            invocation.getExecutable().setSimpleName("isTrue");
          } else {
            invocation.getExecutable().setSimpleName("isFalse");
          }
          invocation.getArguments().clear();
          if (statistics != null) {
            statistics.getProcessing().addAssertionRewritten();
          }
        } else if (literal.getValue() == null) {
          invocation.getExecutable().setSimpleName("isNull");
          invocation.getArguments().clear();
          if (statistics != null) {
            statistics.getProcessing().addAssertionRewritten();
          }
        }
      }

      private void handleJunitAssertEquals(CtInvocation<?> invocation) {
        if (!invocation.getExecutable().getSimpleName().equals("assertEquals")) {
          return;
        }
        String declaredIn = invocation.getExecutable().getDeclaringType().getQualifiedName();
        if (!declaredIn.equals("org.junit.jupiter.api.Assertions")) {
          return;
        }
        if (!(invocation.getArguments().get(0) instanceof CtLiteral<?> literal)) {
          return;
        }
        if (literal.getValue() instanceof Boolean bool) {
          handleAssertBoolean(bool, invocation);
        } else if (literal.getValue() == null) {
          invocation.getExecutable().setSimpleName("assertNull");
          invocation.removeArgument(invocation.getArguments().get(0));
        }
      }

      private void handleAssertBoolean(Boolean value, CtInvocation<?> invocation) {
        if (value) {
          invocation.getExecutable().setSimpleName("assertTrue");
        } else {
          invocation.getExecutable().setSimpleName("assertFalse");
        }
        invocation.removeArgument(invocation.getArguments().get(0));
      }
    });
  }

  private void fixStaticallyImportMockitoAndAsserts(CtMethod<?> method) {
    method.accept(new CtScanner() {
      @Override
      public <T> void visitCtInvocation(CtInvocation<T> invocation) {
        super.visitCtInvocation(invocation);
        if (!(invocation.getTarget() instanceof CtTypeAccess<?> typeAccess)) {
          return;
        }
        String accessedType = typeAccess.getAccessedType().getQualifiedName();
        if (accessedType.startsWith("org.junit.jupiter.api")
            || accessedType.startsWith("org.assertj.core.api")
            || accessedType.startsWith("org.mockito")
            || invocation.getExecutable().getSimpleName().endsWith("NanAwareComparator")
            || invocation.getExecutable().getSimpleName().equals("nanAwareComparison")) {
          typeAccess.setImplicit(true);
        }
      }
    });
  }

  private void fixNarrowThrownExceptions(CtMethod<?> method) {
    if (method.getThrownTypes().isEmpty()) {
      return;
    }

    Set<CtTypeReference<? extends Throwable>> thrownTypes = new HashSet<>();
    method.accept(new CtScanner() {
      @Override
      public <T> void visitCtInvocation(CtInvocation<T> invocation) {
        super.visitCtInvocation(invocation);
        // We destroy our model in the other methods by rewriting method calls
        // This might therefore not exist.
        if (invocation.getExecutable().getExecutableDeclaration() == null) {
          return;
        }
        thrownTypes.addAll(invocation.getExecutable().getExecutableDeclaration().getThrownTypes());
      }

      @Override
      public <T> void visitCtConstructorCall(CtConstructorCall<T> ctConstructorCall) {
        super.visitCtConstructorCall(ctConstructorCall);
        // We destroy our model in the other methods by rewriting method calls
        // This might therefore not exist.
        if (ctConstructorCall.getExecutable().getExecutableDeclaration() == null) {
          return;
        }
        thrownTypes.addAll(
            ctConstructorCall.getExecutable().getExecutableDeclaration().getThrownTypes()
        );
      }
    });

    Set<CtTypeReference<? extends Throwable>> newThrownTypes;
    if (thrownTypes.isEmpty()) {
      newThrownTypes = Set.of();
    } else if (thrownTypes.size() < 3) {
      newThrownTypes = thrownTypes;
    } else {
      newThrownTypes = Set.of(thrownTypes.stream().reduce(Spoons::getLowestSupertype).get());
    }

    if (statistics != null && !newThrownTypes.equals(method.getThrownTypes())) {
      statistics.getProcessing().addRemovedException();
    }
    method.setThrownTypes(newThrownTypes);
  }

  private boolean fixInlineOutlinedMethod(CtMethod<?> victim, Callgraph callgraph) {
    if (victim.getBody().getStatements().size() > MAX_LINES_BEFORE_OUTLINING) {
      return false;
    }
    if (!victim.getParameters().isEmpty()) {
      return false;
    }
    if (victim.getSimpleName().contains("nanAwareComparison")
        || victim.getSimpleName().contains("NanAwareComparator")) {
      return false;
    }
    Set<CtMethod<?>> directCallers = Set.copyOf(callgraph.getDirectCallers(victim));

    if (directCallers.isEmpty()) {
      victim.delete();
      return false;
    }

    int inlineCount = 0;
    for (CtMethod<?> murderer : directCallers) {
      if (inlineInto(victim, callgraph, murderer)) {
        inlineCount++;
      }
    }

    if (inlineCount == directCallers.size()) {
      victim.delete();
    }

    if (statistics != null) {
      for (int i = 0; i < inlineCount; i++) {
        statistics.getProcessing().addInlineMethodEvent();
      }
    }

    return inlineCount > 0;
  }

  private boolean inlineInto(CtMethod<?> victim, Callgraph callgraph, CtMethod<?> murderer) {
    List<CtInvocation<?>> calls = murderer.<CtInvocation<?>>getElements(
            new TypeFilter<>(CtInvocation.class)
        )
        .stream()
        .filter(it -> it.getExecutable().getSignature().equals(victim.getSignature())
                      && it.getExecutable()
                          .getDeclaringType()
                          .getQualifiedName()
                          .equals(victim.getDeclaringType().getQualifiedName())
        )
        .toList();

    // TODO: Do not inline if it appears multiple times?

    // Fix up indirect unneeded returns
    //  Object foo = new ...;
    //  return foo;
    fixInlineVariables(victim);

    // inline all occurrences
    for (CtInvocation<?> call : calls) {
      inline(victim, call);
    }
    callgraph.wasInlined(murderer, victim);
    return true;
  }

  private void fixUnnecessaryByteCasts(CtMethod<?> method) {
    Factory factory = method.getFactory();
    CtTypeReference<Byte> byteType = factory.Type().bytePrimitiveType();
    CtTypeReference<Character> charType = factory.Type().characterPrimitiveType();

    for (CtLiteral<?> literal : method.getElements(new TypeFilter<>(CtLiteral.class))) {
      if (!literal.getType().getSimpleName().equals("int")) {
        continue;
      }
      CtExpression<?> expr = literal;
      CtElement parent = literal.getParent();
      if (parent instanceof CtUnaryOperator<?> op) {
        expr = op;
        parent = op.getParent();
      }
      if (expr.getTypeCasts().isEmpty()) {
        continue;
      }
      if (!expr.getTypeCasts().contains(charType) && !expr.getTypeCasts().contains(byteType)) {
        continue;
      }
      if (parent instanceof CtAssignment<?, ?> || parent instanceof CtNewArray<?>) {
        expr.setTypeCasts(List.of());
      }
    }
  }

  private enum ChangeResult {
    UNCHANGED(false, false),
    DELETED(true, false),
    CHANGED(true, true);

    private final boolean rerunGlobal;
    private final boolean rerunLocal;

    ChangeResult(boolean rerunGlobal, boolean rerunLocal) {
      this.rerunGlobal = rerunGlobal;
      this.rerunLocal = rerunLocal;
    }

    public boolean isRerunGlobal() {
      return rerunGlobal;
    }

    public boolean isRerunLocal() {
      return rerunLocal;
    }
  }

}
