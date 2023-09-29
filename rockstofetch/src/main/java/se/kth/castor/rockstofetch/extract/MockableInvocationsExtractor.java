package se.kth.castor.rockstofetch.extract;

import static se.kth.castor.rockstofetch.util.Spoons.isBasicallyPrimitive;

import se.kth.castor.rockstofetch.extract.NestedInvocation.NestedFieldInvocation;
import se.kth.castor.rockstofetch.extract.NestedInvocation.NestedParameterInvocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.CtScanner;

public class MockableInvocationsExtractor extends CtScanner {

  private final List<NestedInvocation> nestedInvocations;

  public MockableInvocationsExtractor() {
    nestedInvocations = new ArrayList<>();
  }

  public List<NestedInvocation> getNestedInvocations() {
    return Collections.unmodifiableList(nestedInvocations);
  }

  @Override
  public <T> void visitCtInvocation(CtInvocation<T> invocation) {
    super.visitCtInvocation(invocation);

    CtMethod<?> mut = invocation.getParent(CtMethod.class);

    if (!(invocation.getExecutable().getExecutableDeclaration() instanceof CtMethod<T> called)) {
      return;
    }

    if (!isMethodCallMockable(mut, called)) {
      return;
    }

    // Fields (target method => method called on field)
    if (invocation.getTarget() instanceof CtFieldRead<?> fieldAccess) {
      // Ignore calls on other fields (e.g. System.out or w/e)
      if (!isFieldOfClass(fieldAccess, mut.getDeclaringType())) {
        return;
      }

      nestedInvocations.add(
          NestedFieldInvocation.fromCtInvocation(
              invocation,
              fieldAccess.getVariable().getFieldDeclaration()
          )
      );
      return;
    }

    // Parameter (target method => method called on parameter)
    if (!(invocation.getTarget() instanceof CtVariableRead<?> varAccess)) {
      return;
    }
    if (!(varAccess.getVariable().getDeclaration() instanceof CtParameter<?> param)) {
      return;
    }

    nestedInvocations.add(NestedParameterInvocation.fromCtInvocation(invocation, param));
  }

  private <T> boolean isMethodCallMockable(CtMethod<?> mut, CtMethod<T> called) {
    //    - target method is not `private` or `static`
    if (called.getModifiers().contains(ModifierKind.STATIC)) {
      return false;
    }
    // own: Native, as that is nasty
    if (called.isNative()) {
      return false;
    }
    if (called.getDeclaringType().getQualifiedName().equals(List.class.getName())) {
      return false;
    }
    if (called.getDeclaringType().getQualifiedName().equals(Map.class.getName())) {
      return false;
    }
    if (isDeclaringTypeUnmockable(called.getReference())) {
      return false;
    }
    //    - target method is not `private` (currently broken)
    if (called.getModifiers().contains(ModifierKind.PRIVATE)) {
      return false;
    }
    //    - target method is not in `String` or `Collection`
    //      - probably so you don't have to keep the state?
    //    - target method is not excluded
    //      - methods declared in object, Consumer and random stuff that broke things
    if (isBlacklisted(called)) {
      return false;
    }

    //    - target method is not declared in same class as MUT
    //      - this might guard against calls on `this`?
    //      - not sure why, the method could be inherited and called on this without triggering the check
    if (called.getDeclaringType().equals(mut.getDeclaringType())) {
      // TODO: Maybe prohibit just "this" calls instead?
//      return false;
    }
    //    - target method has not exactly one statement
    if (called.getBody() != null && called.getBody().getStatements().size() == 1) {
      return false;
    }
    //    - target method returns string or primitive type
    if (!isBasicallyPrimitive(called.getType())) {
      // TODO: Do we want this?
      // return false;
    }

    return true;
  }

  private boolean isDeclaringTypeUnmockable(CtExecutableReference<?> ref) {
    return ref.getDeclaringType().getModifiers().contains(ModifierKind.PRIVATE);
  }

  private boolean isBlacklisted(CtMethod<?> method) {
    String qualifiedName = method.getDeclaringType().getQualifiedName();
    if (qualifiedName.equals("java.lang.String")) {
      return true;
    }
    return qualifiedName.equals("java.util.Collection");
  }

  private boolean isFieldOfClass(CtFieldRead<?> fieldRead, CtType<?> typeInQuestion) {
    return typeInQuestion.getAllFields().contains(fieldRead.getVariable());
  }

}
