package se.kth.castor.rockstofetch.util;

import static se.kth.castor.rockstofetch.util.Spoons.getCtMethod;
import static se.kth.castor.rockstofetch.util.Spoons.sanitizeName;

import se.kth.castor.rockstofetch.generate.GenerationContext;
import se.kth.castor.rockstofetch.instrument.RecordedInvocation;
import se.kth.castor.rockstofetch.instrument.RecordedTargetedInvocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;

public class Mocks {

  /**
   * {@return a new local variable initialized with a mock of the parameter type}
   *
   * @param factory the factory
   * @param mutParameter the parameter of the MUT to mock
   */
  public static CtLocalVariable<?> generateMockedParameter(
      Factory factory,
      CtParameter<?> mutParameter
  ) {
    return factory.createLocalVariable(
        mutParameter.getType(),
        mutParameter.getSimpleName(),
        mock(factory, mutParameter.getType())
    );
  }

  /**
   * {@return a {@code Mockito.mock(Foo.class)} invocation.}
   *
   * @param factory the factory
   * @param type the type to mock
   * @param <T> arbitrary type of the method, only provided to make using it with spoon easier
   */
  public static <T> CtInvocation<T> mock(Factory factory, CtTypeReference<?> type) {
    return factory.createInvocation(
        factory.createTypeAccess(getMockito(factory)),
        getMockReference(factory),
        SpoonUtil.getClassLiteral(factory, type)
    );
  }

  /**
   * {@return a reference to the Mockito.mock method}
   *
   * @param factory the factory
   * @param <T> arbitrary type of the method, only provided to make using it with spoon easier
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static <T> CtExecutableReference<T> getMockReference(Factory factory) {
    CtExecutableReference<?> reference = factory.createExecutableReference()
        .setDeclaringType(getMockito(factory))
        .setSimpleName("mock");
    ((CtExecutableReference) reference).setParameters(List.of(
        factory.createCtTypeReference(Class.class)
    ));
    return (CtExecutableReference<T>) reference;
  }

  /**
   * {@return a reference to the Mockito class}
   *
   * @param factory the factory
   */
  public static CtTypeReference<?> getMockito(Factory factory) {
    return factory.createReference("org.mockito.Mockito");
  }

  /**
   * {@return a list of Mockito.doReturn() statements providing the return values for the given
   * invocations}
   *
   * @param factory the factory
   * @param targetVariableName the name of the target variable, will be used as a prefix for all
   *     newly created return value variables
   * @param invocations the invocations to mock
   * @param tempMockNames the names for temporary mocks
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static List<CtStatement> generateDoReturnForMethod(
      Factory factory,
      String targetVariableName,
      List<? extends RecordedTargetedInvocation> invocations,
      GenerationContext tempMockNames
  ) {
    CtMethod<?> invokedMethod = getCtMethod(factory, invocations.get(0).recordedMethod());
    List<CtStatement> statements = new ArrayList<>();

    List<String> returnValues = new ArrayList<>();
    int counter = 0;
    for (RecordedTargetedInvocation invocation : invocations) {
      if (invocation.returned().staticType().equals("void")) {
        continue;
      }
      String name =
          targetVariableName
          + "_ret_"
          + sanitizeName(invocation.recordedMethod().methodName())
          + counter++;
      statements.add(invocation.returned().asSnippet(factory, name, tempMockNames));
      returnValues.add(name);
    }

    if (statements.isEmpty()) {
      return List.of();
    }

    CtInvocation doReturn = factory.createInvocation(
        factory.createTypeAccess(getMockito(factory)),
        mockitoDoReturn(factory),
        (List) returnValues.stream()
            .map(it -> factory.createVariableRead(
                factory.createLocalVariableReference(factory.Type().OBJECT, it),
                false
            ))
            .toList()
    );
    CtInvocation<?> when = factory.createInvocation(
        doReturn,
        mockitoWhen(factory),
        factory.createVariableRead(
            factory.createLocalVariableReference(
                factory.Type().OBJECT,
                targetVariableName
            ),
            false
        )
    );
    statements.add(invokeWithAnyMatchers(factory, invokedMethod, when));

    return statements;
  }

  private static CtExecutableReference<Object> mockitoDoReturn(Factory factory) {
    return factory.createExecutableReference()
        .setDeclaringType(getMockito(factory))
        .setParameters(List.of(
            factory.Type().OBJECT,
            factory.createArrayReference(factory.Type().OBJECT)
        ))
        .setSimpleName("doReturn");
  }

  private static CtExecutableReference<Object> mockitoWhen(Factory factory) {
    return factory.createExecutableReference()
        .setDeclaringType(getMockitoStubber(factory))
        .setParameters(List.of(factory.Type().OBJECT))
        .setSimpleName("when");
  }

  /**
   * Invokes the given method on the given target, passing {@code ArgumentMatchers.any()} for all
   * arguments.
   *
   * @param factory the factory
   * @param invokedMethod the method to invoke
   * @param target the target to invoke it on
   * @return the invocation
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static CtInvocation<?> invokeWithAnyMatchers(
      Factory factory,
      CtMethod<?> invokedMethod,
      CtInvocation<?> target
  ) {
    return factory.createInvocation(
        target,
        invokedMethod.getReference(),
        (List) invokedMethod.getParameters()
            .stream()
            .map(it -> factory.createInvocation(
                factory.createTypeAccess(getMockitoArgumentMatchers(factory)),
                factory.createExecutableReference()
                    .setParameters(List.of(factory.createCtTypeReference(Class.class)))
                    .setDeclaringType(getMockitoArgumentMatchers(factory))
                    .setSimpleName("any"),
                SpoonUtil.getClassLiteral(factory, it.getType())
            ))
            .toList()
    );
  }

  private static CtTypeReference<?> getMockitoArgumentMatchers(Factory factory) {
    return factory.createReference("org.mockito.ArgumentMatchers");
  }

  private static CtTypeReference<?> getMockitoStubber(Factory factory) {
    return factory.createReference("org.mockito.stubbing.Stubber");
  }

  /**
   * {@return a reference to the Mockito.atLeast} method
   *
   * @param factory the factory
   */
  public static CtExecutableReference<Object> mockitoAtLeast(Factory factory) {
    return factory.createExecutableReference()
        .setDeclaringType(getMockito(factory))
        .setParameters(List.of(factory.Type().INTEGER_PRIMITIVE))
        .setSimpleName("atLeast");
  }

  /**
   * {@return a reference to the Mockito.verify} method
   *
   * @param factory the factory
   */
  public static CtExecutableReference<Object> mockitoVerify(Factory factory) {
    return factory.createExecutableReference()
        .setDeclaringType(getMockito(factory))
        .setParameters(List.of(factory.Type().OBJECT, factory.Type().OBJECT))
        .setSimpleName("verify");
  }

  /**
   * Collects all targets of the passed invocations. It then groups all calls by target and method
   * and passes them to your callback. This allows you to e.g. generate doReturn statements for each
   * object and method.
   *
   * @param mut the method under test
   * @param mutInvocation the invocation of the MUT
   * @param generationContext the generation context
   * @param nestedInvocations the nested invocations to group
   * @param action the action to take for each {@code (target, method, List<Invocation>)} tuple
   * @param <T> arbitrary type of the method, only provided to make using it with spoon easier
   * @return the resulting statements
   */
  public static <T extends RecordedTargetedInvocation> List<CtStatement> doForMocksPerTargetAndPerMethod(
      CtMethod<?> mut,
      RecordedInvocation mutInvocation,
      GenerationContext generationContext,
      List<T> nestedInvocations,
      BiFunction<String, List<T>, List<CtStatement>> action
  ) {
    List<CtStatement> statements = new ArrayList<>();

    Map<Integer, List<T>> nestedPerTarget = nestedInvocations.stream()
        .collect(Collectors.groupingBy(T::targetId));
    for (var entry : nestedPerTarget.entrySet().stream().sorted(Entry.comparingByKey()).toList()) {
      String variableName = generationContext.tempMockNames().getOrDefault(
          entry.getKey(),
          getVariableNameFromTargetName(
              mut,
              mutInvocation.targetName(entry.getKey())
          )
      );

      entry.getValue()
          .stream()
          .collect(Collectors.groupingBy(T::recordedMethod))
          .values()
          .stream()
          .flatMap(it -> action.apply(variableName, it).stream())
          .forEach(statements::add);
    }

    return statements;
  }

  private static String getVariableNameFromTargetName(CtMethod<?> mut, String targetName) {
    String variableName;
    if (targetName.startsWith("param:")) {
      variableName = mut.getParameters()
          .get(Integer.parseInt(targetName.replace("param:", "")))
          .getSimpleName();
    } else if (targetName.startsWith("field:")) {
      variableName = targetName.substring("field:".length());
    } else if (targetName.startsWith("mock:")) {
      variableName = targetName.substring("mock:".length());
    } else {
      throw new RuntimeException("Unknown target: '" + targetName + "'");
    }
    return variableName;
  }
}
