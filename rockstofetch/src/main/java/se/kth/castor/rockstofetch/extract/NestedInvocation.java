package se.kth.castor.rockstofetch.extract;

import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import java.util.List;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtTypeInformation;

public sealed interface NestedInvocation {

  RecordedMethod toRecordedMethod();

  record NestedFieldInvocation(
      String declaringClassName,
      String fieldName,
      String methodDeclaringClassName,
      String methodName,
      List<String> methodParameterTypes
  ) implements NestedInvocation {

    @Override
    public RecordedMethod toRecordedMethod() {
      return new RecordedMethod(methodDeclaringClassName(), methodName(), methodParameterTypes());
    }

    @Override
    public String toString() {
      return declaringClassName
             + "#" + fieldName
             + "->" + methodDeclaringClassName + "#" + methodName
             + "(" + String.join(",", methodParameterTypes) + ")";
    }

    public static NestedFieldInvocation fromCtInvocation(
        CtInvocation<?> invocation,
        CtField<?> field
    ) {
      return new NestedFieldInvocation(
          field.getDeclaringType().getQualifiedName(),
          field.getSimpleName(),
          invocation.getExecutable().getDeclaringType().getQualifiedName(),
          invocation.getExecutable().getSimpleName(),
          invocation.getExecutable()
              .getParameters()
              .stream()
              .map(CtTypeInformation::getQualifiedName)
              .toList()
      );
    }
  }

  record NestedParameterInvocation(
      String parameterName,
      String methodDeclaringClassName,
      String methodName,
      List<String> methodParameterTypes
  ) implements NestedInvocation {

    @Override
    public RecordedMethod toRecordedMethod() {
      return new RecordedMethod(methodDeclaringClassName(), methodName(), methodParameterTypes());
    }

    @Override
    public String toString() {
      return "#" + parameterName
             + "->" + methodDeclaringClassName + "#" + methodName
             + "(" + String.join(",", methodParameterTypes) + ")";
    }


    public static NestedParameterInvocation fromCtInvocation(
        CtInvocation<?> invocation,
        CtParameter<?> parameter
    ) {
      return new NestedParameterInvocation(
          parameter.getSimpleName(),
          invocation.getExecutable().getDeclaringType().getQualifiedName(),
          invocation.getExecutable().getSimpleName(),
          invocation.getExecutable()
              .getParameters()
              .stream()
              .map(CtTypeInformation::getQualifiedName)
              .toList()
      );
    }
  }

}
