package se.kth.castor.rockstofetch.extract;

import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import java.util.List;
import java.util.stream.Collectors;
import spoon.reflect.declaration.CtMethod;

public record RecordingCandidateMethod(
    String declaringClassName,
    String methodName,
    List<String> parameterTypes,
    List<NestedInvocation> nestedInvocations
) {

  public RecordedMethod toRecordedMethod() {
    return new RecordedMethod(declaringClassName, methodName, parameterTypes);
  }

  @Override
  public String toString() {
    String nested = nestedInvocations.stream()
        .map(it -> "\033[37m  " + it + "\033[0m")
        .collect(Collectors.joining("\n"));

    return declaringClassName + "#" + methodName
           + "(" + String.join(", ", parameterTypes) + ")"
           + (nested.isEmpty() ? "" : "\n" + nested.indent(2).stripTrailing());
  }

  public static RecordingCandidateMethod fromCtMethod(CtMethod<?> method) {
    MockableInvocationsExtractor extractor = new MockableInvocationsExtractor();
    method.accept(extractor);

    return new RecordingCandidateMethod(
        method.getDeclaringType().getQualifiedName(),
        method.getSimpleName(),
        method.getParameters().stream()
            .map(it -> it.getType().getTypeErasure().getQualifiedName())
            .toList(),
        extractor.getNestedInvocations()
    );
  }
}
