package se.kth.castor.rockstofetch.serialization;

import se.kth.castor.rockstofetch.util.Mocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import se.kth.castor.pankti.codemonkey.construction.actions.Action;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallConstructor;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallFactoryMethod;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallSetter;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionMockObject;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionSetField;
import se.kth.castor.pankti.codemonkey.construction.solving.SolvingState;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class FieldMocker {

  private final CtClass<?> targetType;
  private final Factory factory;
  private final RockySerializer serializer;

  public FieldMocker(CtClass<?> targetType, Factory factory, RockySerializer serializer) {
    this.targetType = targetType;
    this.factory = factory;
    this.serializer = serializer;
  }

  public List<CtStatement> createMockedInstance(String name) {
    Optional<SolvingState> receiverPlan = serializer.solveStaticSerialization(targetType);
    if (receiverPlan.isEmpty()) {
      throw new RuntimeException(
          "This is a bit sad. "
          + "We can not inject the fields of the mock properly, so we have a problem. "
          + "Should we generate mocks for the fields to make it easier for the user?"
      );
    }

    return createInstanceWithPlan(name, receiverPlan.get());
  }

  private List<CtStatement> createInstanceWithPlan(String variableName, SolvingState receiverPlan) {
    List<CtStatement> statements = new ArrayList<>();
    for (Action action : receiverPlan.actions()) {
      // FIXME-construct probably delegates to mocking?
      if (action instanceof ActionCallConstructor callConstructor) {
        statements.addAll(createUsingConstructor(variableName, callConstructor));
      } else if (action instanceof ActionCallFactoryMethod callFactoryMethod) {
        statements.addAll(createUsingFactoryMethod(variableName, callFactoryMethod));
      } else if (action instanceof ActionCallSetter callSetter) {
        statements.addAll(setUsingCallSetter(variableName, callSetter));
      } else if (action instanceof ActionSetField setField) {
        statements.addAll(setUsingSetField(variableName, setField));
      } else if (action instanceof ActionMockObject) {
        throw new IllegalArgumentException(
            "Can not mock an object when mocking its fields " + receiverPlan
        );
      } else {
        throw new AssertionError("Unknown action " + action);
      }
    }

    return statements;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private List<CtStatement> setUsingSetField(String variableName, ActionSetField setField) {
    List<CtStatement> statements = new ArrayList<>();
    CtLocalVariable<?> mockVariable = createMockVariable(
        variableName,
        setField.field().getType()
    );
    statements.add(mockVariable);

    statements.add(
        (CtAssignment) ((CtAssignment) factory.createAssignment())
            .setAssigned(
                factory.createCodeSnippetExpression(
                    variableName + "." + setField.field().getSimpleName())
            )
            .setAssignment(
                factory.createVariableRead(mockVariable.getReference(), false)
            )
    );

    return statements;
  }

  private List<CtStatement> setUsingCallSetter(String variableName, ActionCallSetter callSetter) {
    List<CtStatement> statements = new ArrayList<>();

    CtExpression<?> receiver = factory.createVariableRead(
        factory.createLocalVariableReference(
            callSetter.setter().getDeclaringType().getReference(),
            variableName
        ),
        false
    );
    CtInvocation<?> invocation = factory.createInvocation(
        receiver,
        callSetter.setter().getReference()
    );

    for (CtParameter<?> parameter : callSetter.setter().getParameters()) {
      CtLocalVariable<?> mockVariable = createMockVariable(
          callSetter.parameters().get(parameter).get(0).getSimpleName(),
          parameter.getType()
      );
      statements.add(mockVariable);
      invocation.addArgument(factory.createVariableRead(mockVariable.getReference(), false));
    }

    statements.add(invocation);

    return statements;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private List<CtStatement> createUsingFactoryMethod(
      String variableName,
      ActionCallFactoryMethod callFactoryMethod
  ) {
    List<CtStatement> statements = new ArrayList<>();

    CtInvocation invocation = factory.Code().createInvocation(
        // static call, so target is a type access
        factory.Code().createTypeAccess(
            callFactoryMethod.method().getDeclaringType().getReference()
        ),
        callFactoryMethod.method().getReference()
    );
    for (CtParameter<?> parameter : callFactoryMethod.method().getParameters()) {
      CtLocalVariable<?> mockVariable = createMockVariable(
          callFactoryMethod.parameters().get(parameter).get(0).getSimpleName(),
          parameter.getType()
      );
      statements.add(mockVariable);
      invocation.addArgument(factory.createVariableRead(mockVariable.getReference(), false));
    }
    statements.add(factory.createLocalVariable(
        invocation.getType(),
        variableName,
        invocation
    ));

    return statements;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private List<CtStatement> createUsingConstructor(
      String variableName,
      ActionCallConstructor callConstructor
  ) {
    List<CtStatement> statements = new ArrayList<>();

    CtConstructorCall constructorCall = factory.createConstructorCall();
    constructorCall.setExecutable(callConstructor.constructor().getReference());
    for (CtParameter<?> parameter : callConstructor.constructor().getParameters()) {
      CtLocalVariable<?> mockVariable = createMockVariable(
          callConstructor.parameters().get(parameter).get(0).getSimpleName(),
          parameter.getType()
      );
      statements.add(mockVariable);
      constructorCall.addArgument(
          factory.createVariableRead(mockVariable.getReference(), false)
      );
    }

    statements.add(factory.createLocalVariable(
        constructorCall.getType(),
        variableName,
        constructorCall
    ));

    return statements;
  }

  private CtLocalVariable<?> createMockVariable(String name, CtTypeReference<?> type) {
    return factory.createLocalVariable(
        type,
        name,
        Mocks.mock(factory, type)
    );
  }
}
