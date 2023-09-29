package se.kth.castor.rockstofetch.generate;

import static se.kth.castor.rockstofetch.util.Mocks.doForMocksPerTargetAndPerMethod;
import static se.kth.castor.rockstofetch.util.Mocks.generateDoReturnForMethod;

import se.kth.castor.rockstofetch.generate.DataReader.LoadedInvocation;
import se.kth.castor.rockstofetch.generate.GenerationContext.AssertionType;
import se.kth.castor.rockstofetch.generate.GenerationContext.MethodCache;
import se.kth.castor.rockstofetch.instrument.RecordedInvocation;
import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import se.kth.castor.rockstofetch.util.Spoons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class JunitTestMethodOutputOracle {

  private static int counter = 0;

  private final Factory factory;
  private final LoadedInvocation loadedInvocation;
  private final EventSequence events;
  private final BiFunction<AssertionType, CtTypeReference<?>, CtStatement> expectedActualEqualityFunction;
  private final String testMethodName;

  public JunitTestMethodOutputOracle(
      Factory factory,
      LoadedInvocation loadedInvocation,
      EventSequence events,
      BiFunction<AssertionType, CtTypeReference<?>, CtStatement> expectedActualEqualityFunction
  ) {
    this.factory = factory;
    this.loadedInvocation = loadedInvocation;
    this.events = events;
    this.expectedActualEqualityFunction = expectedActualEqualityFunction;
    this.testMethodName = Spoons.testName(
        loadedInvocation.invocation().recordedMethod(),
        "" + counter++
    );
  }

  public int buildTest(MethodCache methodCache, Statistics statistics) {
    MethodCache newMethodCache = methodCache.copy();
    RecordedInvocation invocation = loadedInvocation.invocation();

    CtMethod<?> mut = Spoons.getCtMethod(factory, invocation.recordedMethod());
    GenerationContext generationContext = new GenerationContext(
        new HashMap<>(), events, expectedActualEqualityFunction, newMethodCache, statistics
    );

    CtMethod<?> testMethod = factory.createMethod();
    testMethod.addThrownType(factory.Type().<Exception>get(Exception.class).getReference());
    testMethod.setSimpleName(testMethodName);
    testMethod.setType(factory.Type().VOID_PRIMITIVE);
    testMethod.addAnnotation(
        factory.createAnnotation(factory.createReference("org.junit.jupiter.api.Test"))
    );
    testMethod.addAnnotation(getDisplaynameAnnotation(factory, invocation));

    CtBlock<?> body = factory.createBlock();
    testMethod.setBody(body);

    generateArrange(factory, loadedInvocation, generationContext, mut)
        .forEach(body::addStatement);

    if (invocation.isVoid()) {
      generateActAssertForVoid(factory, loadedInvocation, generationContext, mut)
          .forEach(body::addStatement);
    } else {
      generateActAssertWithReturnValue(factory, loadedInvocation, generationContext, mut)
          .forEach(body::addStatement);
    }

    methodCache.addMethod(testMethod);

    methodCache.addAll(newMethodCache);
    return generationContext.containedObjects().get();
  }

  private List<CtStatement> generateArrange(
      Factory factory,
      LoadedInvocation loadedInvocation,
      GenerationContext generationContext,
      CtMethod<?> mut
  ) {
    RecordedInvocation invocation = loadedInvocation.invocation();
    List<CtStatement> statements = new ArrayList<>();

    statements.add(
        outlineIntoMethod(
            invocation.receiverPre(),
            "receiver",
            generationContext
        )
            .addComment(factory.createInlineComment("Arrange"))
    );
    for (int i = 0; i < invocation.parameters().size(); i++) {
      JavaSnippet parameterValue = invocation.parameters().get(i);
      CtParameter<?> mutParameter = mut.getParameters().get(i);

      if (parameterValue.statements().isEmpty()) {
        throw new RuntimeException(
            "Parameter could not be serialized or mocked. Type: " + parameterValue.dynamicType()
        );
      }

      statements.add(outlineIntoMethod(
          parameterValue,
          mutParameter.getSimpleName(),
          generationContext
      ));
    }

    statements.addAll(doForMocksPerTargetAndPerMethod(
        mut,
        invocation,
        generationContext,
        loadedInvocation.mockedInvocations(),
        (name, list) -> generateDoReturnForMethod(factory, name, list, generationContext)
    ));

    return statements;
  }

  private CtStatement outlineIntoMethod(
      JavaSnippet snippet,
      String targetVariableName,
      GenerationContext context
  ) {
    CtExpression<Object> call = context.getCallToDeduplicatedMethod(
        factory.createReference(snippet.staticType()),
        targetVariableName,
        List.of(snippet.asSnippet(factory, targetVariableName, context))
    );

    return factory.createLocalVariable(
        factory.createReference(snippet.staticType()),
        targetVariableName,
        call
    );
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static CtAnnotation<?> getDisplaynameAnnotation(
      Factory factory,
      RecordedInvocation invocation
  ) {
    return ((CtAnnotation) factory.createAnnotation())
        .setValues(Map.of("value", invocation.recordedMethod().signature()))
        .setAnnotationType(factory.createReference("org.junit.jupiter.api.DisplayName"));
  }

  private List<CtStatement> generateActAssertForVoid(
      Factory factory,
      LoadedInvocation loadedInvocation,
      GenerationContext generationContext,
      CtMethod<?> mut
  ) {
    List<CtStatement> statements = new ArrayList<>();

    RecordedInvocation invocation = loadedInvocation.invocation();
    statements.add(
        factory.createCodeSnippetStatement("""
                receiver.%s(%s)""".formatted(
                invocation.recordedMethod().methodName(),
                IntStream.range(0, invocation.parameters().size())
                    .mapToObj(i -> mut.getParameters().get(i).getSimpleName())
                    .collect(Collectors.joining(", "))
            ))
            .addComment(factory.createInlineComment("Act"))
    );

    statements.add(
        outlineIntoMethod(
            invocation.receiverPost(),
            AssertionType.RECEIVER_PRE_POST.getExpectedName(),
            generationContext
        )
            .addComment(factory.createInlineComment("Assert"))
    );

    statements.add(
        generationContext.expectedActualEqualityFunction()
            .apply(AssertionType.RECEIVER_PRE_POST, mut.getDeclaringType().getReference())
    );

    return statements;
  }

  private List<CtStatement> generateActAssertWithReturnValue(
      Factory factory,
      LoadedInvocation loadedInvocation,
      GenerationContext generationContext,
      CtMethod<?> mut
  ) {
    List<CtStatement> statements = new ArrayList<>();

    RecordedInvocation invocation = loadedInvocation.invocation();
    statements.add(
        factory.createCodeSnippetStatement("""
                %s %s = receiver.%s(%s)""".formatted(
                SpoonUtil.typeVarToRawtype(mut.getType()).getQualifiedName().replace("$", "."),
                AssertionType.EXPECTED_ACTUAL.getActualName(),
                mut.getSimpleName(),
                IntStream.range(0, invocation.parameters().size())
                    .mapToObj(i -> mut.getParameters().get(i).getSimpleName())
                    .collect(Collectors.joining(", "))
            ))
            .addComment(factory.createInlineComment("Act"))
    );

    statements.add(
        outlineIntoMethod(
            invocation.returned(),
            AssertionType.EXPECTED_ACTUAL.getExpectedName(),
            generationContext
        )
            .addComment(factory.createInlineComment("Assert"))
    );
    statements.add(
        generationContext.expectedActualEqualityFunction()
            .apply(AssertionType.EXPECTED_ACTUAL, mut.getType())
    );

    return statements;
  }

}
