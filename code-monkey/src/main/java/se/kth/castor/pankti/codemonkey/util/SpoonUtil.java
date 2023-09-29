package se.kth.castor.pankti.codemonkey.util;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtOperatorAssignment;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtActualTypeContainer;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.CtScanner;
import spoon.reflect.visitor.filter.SuperInheritanceHierarchyFunction;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.adaption.TypeAdaptor;

public class SpoonUtil {

  public static boolean isDefaultValue(CtLiteral<?> literal) {
    if (!literal.getType().isPrimitive()) {
      return literal.getValue() == null;
    }
    if (literal.getValue() instanceof Number n) {
      return n.doubleValue() == 0;
    }
    if (literal.getType().getSimpleName().equals("boolean")) {
      //noinspection PointlessBooleanExpression
      return (boolean) literal.getValue() == false;
    }
    return (char) literal.getValue() == 0;
  }

  @SuppressWarnings({"unchecked"})
  public static <T> CtExpression<T> getLiteral(Factory factory, Object value) {
    CtExpression<?> expression;
    if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
      expression = handleNanInfinite(factory, f);
    } else if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
      expression = handleNanInfinite(factory, d);
    } else {
      expression = factory.createLiteral(value);
    }
    if (value instanceof Short || value instanceof Byte) {
      expression.addTypeCast(factory.createCtTypeReference(value.getClass()).unbox());
    }
    return (CtExpression<T>) expression;
  }

  private static CtExpression<?> handleNanInfinite(Factory factory, Number number) {
    if (number instanceof Float f) {
      if (f.isNaN()) {
        return createFieldRead(factory, "NaN", factory.Type().FLOAT);
      }
      return f > 0
          ? createFieldRead(factory, "POSITIVE_INFINITY", factory.Type().FLOAT)
          : createFieldRead(factory, "NEGATIVE_INFINITY", factory.Type().FLOAT);
    } else if (number instanceof Double d) {
      if (d.isNaN()) {
        return createFieldRead(factory, "NaN", factory.Type().DOUBLE);
      }
      return d > 0
          ? createFieldRead(factory, "POSITIVE_INFINITY", factory.Type().DOUBLE)
          : createFieldRead(factory, "NEGATIVE_INFINITY", factory.Type().DOUBLE);
    } else {
      throw new IllegalArgumentException("Unexpected type: " + number.getClass() + " " + number);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static CtExpression<?> createFieldRead(
      Factory factory,
      String name,
      CtTypeReference<?> type
  ) {
    return factory.createFieldRead()
        .<CtFieldRead>setVariable(
            (CtFieldReference) type.getDeclaredField(name)
        )
        .setTarget(factory.createTypeAccess(type));
  }

  public static List<CtClass<?>> getSuperclasses(CtType<?> type) {
    return type
        .map(
            new SuperInheritanceHierarchyFunction(new HashSet<>())
                .includingSelf(true)
                .includingInterfaces(false)
        )
        .list();
  }

  /**
   * Returns the field if the {@link CtFieldWrite} directly (i.e. without computations) assigns a
   * parameter to the field.
   *
   * @param write the field write to check
   * @param allowedDeclaringTypes the types the written field can be declared by
   * @return the written field or empty if the assignment wasn't direct
   */
  public static Optional<DirectFieldParameterWrite> getFieldParameterAssignment(
      CtFieldWrite<?> write,
      Set<CtTypeReference<?>> allowedDeclaringTypes
  ) {
    CtField<?> field = write.getVariable().getFieldDeclaration();
    if (field == null) {
      return Optional.empty();
    }
    CtType<?> declaringType = field.getDeclaringType();
    if (!declaringType.isClass() || !allowedDeclaringTypes.contains(declaringType.getReference())) {
      return Optional.empty();
    }
    // ? =
    if (!(write.getParent() instanceof CtAssignment<?, ?> assignment)) {
      return Optional.empty();
    }
    // *= or += do not count
    if (assignment instanceof CtOperatorAssignment<?, ?>) {
      return Optional.empty();
    }
    // field = ?
    if (assignment.getAssignment() instanceof CtVariableRead<?> read) {
      return writeFromDirectParamRead(field, read);
    }
    // field = foo(?)
    if (assignment.getAssignment() instanceof CtInvocation<?> invocation) {
      return writeFromInvocation(field, invocation);
    }
    // field = new foo(?)
    if (assignment.getAssignment() instanceof CtConstructorCall<?> call) {
      return writeFromCopyConstructor(field, call);
    }

    return Optional.empty();
  }

  private static Optional<DirectFieldParameterWrite> writeFromDirectParamRead(
      CtField<?> field,
      CtVariableRead<?> read
  ) {
    // field = param
    if (!(read.getVariable().getDeclaration() instanceof CtParameter<?> parameter)) {
      return Optional.empty();
    }
    return writeFromParam(field, parameter);
  }

  private static Optional<DirectFieldParameterWrite> writeFromInvocation(
      CtField<?> field,
      CtInvocation<?> invocation
  ) {
    return writeFromPureCheckMethod(field, invocation);
  }

  private static Optional<DirectFieldParameterWrite> writeFromCopyConstructor(
      CtField<?> field,
      CtConstructorCall<?> invocation
  ) {
    CtConstructor<?> called = (CtConstructor<?>) invocation.getExecutable()
        .getExecutableDeclaration();

    if (invocation.getArguments().size() != 1) {
      return Optional.empty();
    }

    // LinkedList field = new LinkedList(Collection);
    //    |   Must be assignable to param   ^
    //    +---------------------------------+
    if (!TypeAdaptor.isSubtype(
        field.getType().getTypeDeclaration(), called.getParameters().get(0).getType()
    )) {
      return Optional.empty();
    }

    if (!(invocation.getArguments().get(0) instanceof CtVariableRead<?> read)) {
      return Optional.empty();
    }
    if (!(read.getVariable().getDeclaration() instanceof CtParameter<?> parameter)) {
      return Optional.empty();
    }

    if (!TypeAdaptor.isSubtype(called.getDeclaringType(), field.getType())) {
      return Optional.empty();
    }

    return writeFromParam(field, parameter);
  }

  private static Optional<DirectFieldParameterWrite> writeFromPureCheckMethod(
      CtField<?> field,
      CtInvocation<?> invocation
  ) {
    if (!(invocation.getExecutable().getExecutableDeclaration() instanceof CtMethod<?> called)) {
      return Optional.empty();
    }

    for (CtExpression<?> argument : invocation.getArguments()) {
      if (!(argument instanceof CtVariableRead<?> read)) {
        continue;
      }
      if (!(read.getVariable().getDeclaration() instanceof CtParameter<?> parameter)) {
        continue;
      }
      if (MethodUtil.returnsArgumentUnchanged(called, parameter)) {
        return writeFromParam(field, parameter);
      }
    }

    return Optional.empty();
  }

  private static Optional<DirectFieldParameterWrite> writeFromParam(
      CtField<?> field,
      CtParameter<?> parameter
  ) {
    if (parameter.getParent() instanceof CtMethod<?> method) {
      return Optional.of(new DirectFieldParameterWriteMethod(field, method, parameter));
    }
    if (parameter.getParent() instanceof CtConstructor<?> constructor) {
      return Optional.of(new DirectFieldParameterWriteConstructor(field, constructor, parameter));
    }

    return Optional.empty();
  }

  /**
   * {@return a spoon version of a {@code Foo.class} literal.}
   *
   * @param factory the factory
   * @param type the type to get the class for
   */
  public static CtFieldRead<?> getClassLiteral(Factory factory, CtTypeReference<?> type) {
    CtTypeReference<?> myReference = type.clone();
    myReference.setAnnotations(List.of());
    myReference.setActualTypeArguments(List.of());
    CtFieldRead<?> read = factory.createFieldRead();
    read.setVariable(
        factory.createFieldReference()
            .setDeclaringType(myReference)
            .setSimpleName("class")
    );
    read.setTarget(factory.createTypeAccess(myReference));
    return read;
  }

  public static CtTypeReference<?> typeVarToRawtype(CtTypeReference<?> in) {
    if (in instanceof CtTypeParameterReference) {
      return in.getFactory().Type().objectType();
    }
    CtTypeReference<?> clone = in.clone();
    clone.accept(new CtScanner() {
      @Override
      public void visitCtTypeParameterReference(CtTypeParameterReference ref) {
        // Clear our parent
        ref.getParent(new TypeFilter<>(CtActualTypeContainer.class))
            .setActualTypeArguments(List.of());
      }
    });
    return clone;
  }

  public static boolean isAccessible(CtModifiable element) {
    if (GlobalSwitches.ONLY_ALLOW_PUBLIC_ELEMENTS) {
      return element.isPublic();
    }

    if (element.isPrivate()) {
      return false;
    }
    if (element instanceof CtType<?> type) {
      return !type.getQualifiedName().startsWith("java.");
    }
    if (element instanceof CtTypeMember member) {
      return isAccessible(member.getDeclaringType());
    }
    return true;
  }

  public sealed interface DirectFieldParameterWrite {

    CtField<?> writtenField();

    CtExecutable<?> executableWithWrite();

    CtParameter<?> readParameter();

    default Optional<DirectFieldParameterWriteMethod> asMethodWrite() {
      if (this instanceof DirectFieldParameterWriteMethod methodWrite) {
        return Optional.of(methodWrite);
      }
      return Optional.empty();
    }

    default Optional<DirectFieldParameterWriteConstructor> asConstructorWrite() {
      if (this instanceof DirectFieldParameterWriteConstructor constructorWrite) {
        return Optional.of(constructorWrite);
      }
      return Optional.empty();
    }

  }

  public record DirectFieldParameterWriteMethod(
      CtField<?> writtenField,
      CtMethod<?> executableWithWrite,
      CtParameter<?> readParameter
  ) implements DirectFieldParameterWrite {

  }

  public record DirectFieldParameterWriteConstructor(
      CtField<?> writtenField,
      CtConstructor<?> executableWithWrite,
      CtParameter<?> readParameter
  ) implements DirectFieldParameterWrite {

  }

}
