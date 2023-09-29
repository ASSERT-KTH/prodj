package se.kth.castor.rockstofetch.generate;

import static se.kth.castor.rockstofetch.util.Mocks.doForMocksPerTargetAndPerMethod;
import static se.kth.castor.rockstofetch.util.Mocks.generateDoReturnForMethod;
import static se.kth.castor.rockstofetch.util.Mocks.generateMockedParameter;
import static se.kth.castor.rockstofetch.util.Mocks.getMockito;
import static se.kth.castor.rockstofetch.util.Mocks.invokeWithAnyMatchers;
import static se.kth.castor.rockstofetch.util.Mocks.mockitoAtLeast;
import static se.kth.castor.rockstofetch.util.Mocks.mockitoVerify;
import static se.kth.castor.rockstofetch.util.Spoons.getCtMethod;
import static se.kth.castor.rockstofetch.util.Spoons.isBasicallyPrimitive;
import static se.kth.castor.rockstofetch.util.Spoons.testName;

import se.kth.castor.rockstofetch.generate.DataReader.LoadedInvocation;
import se.kth.castor.rockstofetch.instrument.RecordedInvocation;
import se.kth.castor.rockstofetch.instrument.RecordedMockedInvocation;
import se.kth.castor.rockstofetch.instrument.RecordedNestedInvocation;
import se.kth.castor.rockstofetch.instrument.RecordedTargetedInvocation;
import se.kth.castor.rockstofetch.serialization.FieldMocker;
import se.kth.castor.rockstofetch.serialization.RockySerializer;
import se.kth.castor.rockstofetch.util.Spoons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.factory.Factory;

public class JunitTestMethodParameterOracle {

  public static void forInvocation(
      Factory factory,
      RockySerializer serializer,
      LoadedInvocation loadedInvocation,
      EventSequence events,
      Statistics statistics
  ) {
    RecordedInvocation invocation = loadedInvocation.invocation();
    List<RecordedNestedInvocation> nestedInvocations = loadedInvocation.nestedInvocations();
    List<RecordedMockedInvocation> mockedInvocations = loadedInvocation.mockedInvocations();
    GenerationContext generationContext = new GenerationContext(
        new HashMap<>(), events, statistics
    );

    CtMethod<?> mut = Spoons.getCtMethod(factory, invocation.recordedMethod());

    boolean hasNoMockableParameter = mut.getParameters()
        .stream()
        .allMatch(it -> isBasicallyPrimitive(it.getType()));
    if (hasNoMockableParameter || (nestedInvocations.isEmpty() && mockedInvocations.isEmpty())) {
      return;
    }

    CtMethod<?> testMethod = setupMethod(factory, invocation);

    CtBlock<?> body = factory.createBlock();
    testMethod.setBody(body);

    // Arrange requirements
    generateArrange(factory, serializer, loadedInvocation, generationContext, mut)
        .forEach(body::addStatement);

    // Call method
    body.addStatement(generateAct(factory, invocation, mut));

    // Verify result
    generateVerify(factory, loadedInvocation, generationContext, mut)
        .forEach(body::addStatement);

    generationContext.methodCache().addMethod(testMethod);
  }

  private static CtMethod<?> setupMethod(
      Factory factory,
      RecordedInvocation invocation
  ) {
    CtMethod<?> testMethod = factory.createMethod();

    testMethod.addThrownType(factory.Type().<Exception>get(Exception.class).getReference());
    testMethod.setSimpleName(testName(invocation.recordedMethod(), "parameter"));
    testMethod.setType(factory.Type().VOID_PRIMITIVE);
    testMethod.addAnnotation(
        factory.createAnnotation(factory.createReference("org.junit.jupiter.api.Test"))
    );
    testMethod.addAnnotation(getDisplaynameAnnotation(factory, invocation));

    return testMethod;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static CtAnnotation<?> getDisplaynameAnnotation(
      Factory factory,
      RecordedInvocation invocation
  ) {
    return ((CtAnnotation) factory.createAnnotation())
        .setValues(Map.of(
            "value",
            "Parameter Oracle for " + invocation.recordedMethod().signature()
        ))
        .setAnnotationType(factory.createReference("org.junit.jupiter.api.DisplayName"));
  }

  private static List<CtStatement> generateArrange(
      Factory factory,
      RockySerializer serializer,
      LoadedInvocation loadedInvocation,
      GenerationContext generationContext,
      CtMethod<?> mut
  ) {
    RecordedInvocation invocation = loadedInvocation.invocation();
    List<CtStatement> statements = new ArrayList<>();

    CtClass<?> receiverType = factory.Class().get(invocation.receiverPre().dynamicType());
    statements.addAll(
        new FieldMocker(receiverType, factory, serializer).createMockedInstance("receiver")
    );
    statements.addAll(generateMethodParameters(factory, loadedInvocation, generationContext, mut));
    List<RecordedTargetedInvocation> allInvocations = new ArrayList<>(
        loadedInvocation.nestedInvocations()
    );
    allInvocations.addAll(loadedInvocation.mockedInvocations());
    statements.addAll(doForMocksPerTargetAndPerMethod(
        mut,
        invocation,
        generationContext,
        allInvocations,
        (name, list) -> generateDoReturnForMethod(factory, name, list, generationContext)
    ));

    statements.get(0).addComment(factory.createInlineComment("Arrange"));

    return statements;
  }

  private static List<CtStatement> generateMethodParameters(
      Factory factory,
      LoadedInvocation loadedInvocation,
      GenerationContext generationContext,
      CtMethod<?> mut
  ) {
    RecordedInvocation invocation = loadedInvocation.invocation();
    List<CtStatement> statements = new ArrayList<>();

    for (int i = 0; i < invocation.parameters().size(); i++) {
      CtParameter<?> mutParameter = mut.getParameters().get(i);

      if (isBasicallyPrimitive(mutParameter.getType())) {
        statements.add(
            invocation.parameters().get(i)
                .asSnippet(factory, mutParameter.getSimpleName(), generationContext)
        );
      } else {
        statements.add(generateMockedParameter(factory, mutParameter));
      }
    }

    return statements;
  }

  private static CtStatement generateAct(
      Factory factory,
      RecordedInvocation invocation,
      CtMethod<?> mut
  ) {
    return factory.createCodeSnippetStatement("""
            receiver.%s(%s)""".formatted(
            invocation.recordedMethod().methodName(),
            IntStream.range(0, invocation.parameters().size())
                .mapToObj(i -> mut.getParameters().get(i).getSimpleName())
                .collect(Collectors.joining(", "))
        ))
        .addComment(factory.createInlineComment("Act"));
  }

  private static List<CtStatement> generateVerify(
      Factory factory,
      LoadedInvocation loadedInvocation,
      GenerationContext generationContext,
      CtMethod<?> mut
  ) {
    List<RecordedTargetedInvocation> allInvocations = new ArrayList<>(
        loadedInvocation.nestedInvocations()
    );
    allInvocations.addAll(loadedInvocation.mockedInvocations());

    List<CtStatement> statements = doForMocksPerTargetAndPerMethod(
        mut,
        loadedInvocation.invocation(),
        generationContext,
        allInvocations,
        (name, invocations) -> generateVerifyForMethod(factory, name, invocations)
    );

    if (!statements.isEmpty()) {
      statements.get(0).addComment(factory.createInlineComment("Verify"));
    }
    return statements;
  }

  @SuppressWarnings({"rawtypes"})
  private static List<CtStatement> generateVerifyForMethod(
      Factory factory,
      String targetVariableName,
      List<? extends RecordedTargetedInvocation> invocations
  ) {
    CtMethod<?> invokedMethod = getCtMethod(factory, invocations.get(0).recordedMethod());
    List<CtStatement> statements = new ArrayList<>();

    CtInvocation verify = factory.createInvocation(
        factory.createTypeAccess(getMockito(factory)),
        mockitoVerify(factory),
        factory.createVariableRead(
            factory.createLocalVariableReference(factory.Type().OBJECT, targetVariableName),
            false
        ),
        factory.createInvocation(
            factory.createTypeAccess(getMockito(factory)),
            mockitoAtLeast(factory),
            factory.createLiteral(1)
        )
    );
    // TODO: Verify the arguments?
    statements.add(invokeWithAnyMatchers(factory, invokedMethod, verify));

    return statements;
  }

}
