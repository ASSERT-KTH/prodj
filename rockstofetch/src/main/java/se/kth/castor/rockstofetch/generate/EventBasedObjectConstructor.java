package se.kth.castor.rockstofetch.generate;

import se.kth.castor.rockstofetch.generate.EventSequence.ObjectContext;
import se.kth.castor.rockstofetch.generate.EventSequence.ObjectCreateEvent;
import se.kth.castor.rockstofetch.generate.GenerationException.Type;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.CallMethodStartEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.IdentityValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.PrimitiveValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.NullValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.ReferenceValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.SerializedValue;
import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import se.kth.castor.rockstofetch.util.Spoons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.castor.pankti.codemonkey.util.InheritanceUtil;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil;
import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class EventBasedObjectConstructor {

  private final long timestamp;
  private final ObjectContext objectContext;
  private final List<PotentiallyDelayedValue<CtStatement>> statements;
  private final GenerationContext context;
  private final Factory factory;
  private final Map<Integer, GeneratedIdentityObject> generatedIdentityObjects;
  private final Map<Integer, List<CtStatement>> delayedStatements;

  private EventBasedObjectConstructor(
      GenerationContext context,
      Factory factory,
      long timestamp,
      ObjectContext objectContext,
      Map<Integer, GeneratedIdentityObject> generatedIdentityObjects,
      Map<Integer, List<CtStatement>> delayedStatements
  ) {
    this.context = context;
    this.factory = factory;
    this.timestamp = timestamp;
    this.objectContext = objectContext;
    this.generatedIdentityObjects = generatedIdentityObjects;
    this.delayedStatements = delayedStatements;

    this.statements = new ArrayList<>();
  }

  public List<CtStatement> construct(String name, CtTypeReference<?> type) {
    constructInternal(name, type);

    List<CtStatement> result = new ArrayList<>();
    Set<Integer> unblocked = new HashSet<>();
    List<PotentiallyDelayedValue<CtStatement>> waiting = new ArrayList<>();
    for (PotentiallyDelayedValue<CtStatement> statement : statements) {
      unblocked.addAll(statement.unblocks());

      if (unblocked.containsAll(statement.delayedOn())) {
        result.add(statement.value());
      } else {
        waiting.add(statement);
      }

      List<CtStatement> newlyUnblocked = waiting.stream()
          .filter(it -> unblocked.containsAll(it.delayedOn()))
          .map(PotentiallyDelayedValue::value)
          .toList();
      waiting.removeIf(it -> unblocked.containsAll(it.delayedOn()));

      result.addAll(newlyUnblocked);
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private List<PotentiallyDelayedValue<CtStatement>> constructInternal(
      String name, CtTypeReference<?> type
  ) {
    ObjectCreateEvent constructEvent = objectContext.createEvent();
    generatedIdentityObjects.put(constructEvent.newObject(), new GeneratedIdentityObject(name));

    CtAbstractInvocation<Object> constructInvocation = objectContext.createEvent()
        .createCall(factory);

    for (int i = 0; i < constructEvent.parameters().size(); i++) {
      Value parameter = constructEvent.parameters().get(i);
      ConvertedValue value = valueAsCtExpression(parameter, name + "_" + i);
      if (value.read().isDelayed()) {
        throw new IllegalStateException("Can not be delayed on argument read: " + value);
      }
      statements.addAll(value.statements());
      CtExpression<?> argument = value.read().value();
      if (parameter instanceof NullValue nullValue) {
        argument.addTypeCast(factory.createReference(nullValue.clazz()));
      }
      constructInvocation.addArgument(argument);
    }

    CtLocalVariable<?> createdVariable = factory.createLocalVariable(
        (CtTypeReference<Object>) type,
        name,
        (CtExpression<Object>) constructInvocation
    ).addComment(factory.createInlineComment("Recreated from trace"));
    statements.add(PotentiallyDelayedValue.unblocking(createdVariable, objectContext.id()));

    generatedIdentityObjects.get(objectContext.id()).setConstructed();

    Set<CtMethod<?>> calledMethods = addMutatorCalls(name);

    if (!calledMethods.isEmpty()) {
      createdVariable.setType(InheritanceUtil.findLeastSpecificTypeOrInitial(
          factory.Type().get(constructEvent.clazz()),
          type,
          calledMethods,
          Set.of()
      ));
    }

    CtTypeReference<?> assignedType = ((CtExpression<?>) constructInvocation).getType();
    if (!assignedType.isSubtypeOf(createdVariable.getType())) {
      ((CtExpression<?>) constructInvocation).addTypeCast(createdVariable.getType());
    }

    return statements;
  }

  private ConvertedValue valueAsCtExpression(Value value, String name) {
    if (value instanceof PrimitiveValue primitiveValue) {
      return ConvertedValue.ofPure(SpoonUtil.getLiteral(
          factory, primitiveValue.asValue()
      ));
    }
    if (value instanceof SerializedValue serializedValue) {
      return ConvertedValue.ofImmediate(
          readLocalVar(name, serializedValue.clazz()),
          addVariableSuffix(serializedValue.snippet()).asSnippet(factory, name, context)
      );
    }
    if (value instanceof IdentityValue identityValue
        && generatedIdentityObjects.containsKey(identityValue.id())) {
      GeneratedIdentityObject identityObject = generatedIdentityObjects.get(identityValue.id());
      CtVariableAccess<?> rawExpr = readLocalVar(
          identityObject.name(),
          Object.class.getName()
      );
      if (identityObject.constructed()) {
        return ConvertedValue.ofPure(PotentiallyDelayedValue.immediate(rawExpr));
      }
      return ConvertedValue.ofPure(PotentiallyDelayedValue.delayed(rawExpr, identityValue.id()));
    }
    if (value instanceof ReferenceValue referenceValue) {
      GeneratedIdentityObject identityObject = generatedIdentityObjects.get(referenceValue.id());

      CtVariableAccess<?> rawExpr = readLocalVar(name, referenceValue.clazz());
      PotentiallyDelayedValue<CtExpression<?>> expr;
      if (identityObject != null && !identityObject.constructed()) {
        expr = PotentiallyDelayedValue.delayed(rawExpr, referenceValue.id());
      } else {
        expr = PotentiallyDelayedValue.immediate(rawExpr);
      }

      return new ConvertedValue(
          expr,
          EventBasedObjectConstructor
              .fromEvents(
                  context, factory, referenceValue.referenceId(), timestamp,
                  generatedIdentityObjects, delayedStatements
              )
              .constructInternal(name, factory.createReference(referenceValue.clazz()))
      );
    }
    throw new GenerationException(Type.UNKNOWN_VALUE, value.toString());
  }

  /**
   * The same serialized snippet might be used *multiple times* in the same method. In these cases
   * we want to create it fresh.
   *
   * @param input the snippet to modify
   * @return the snippet with adjusted variable names
   */
  private JavaSnippet addVariableSuffix(JavaSnippet input) {
    List<String> newStatements = Spoons.replaceVariableNames(
        input.statements(),
        s -> s + context.freshIds().getAndIncrement()
    );

    return new JavaSnippet(
        newStatements,
        input.dynamicType(),
        input.staticType(),
        input.containedObjects()
    );
  }

  private CtVariableAccess<Object> readLocalVar(String name, String clazz) {
    return factory.createVariableRead(
        factory.createLocalVariableReference(
            factory.createReference(clazz),
            name
        ),
        false
    );
  }

  private Set<CtMethod<?>> addMutatorCalls(String name) {
    Set<CtMethod<?>> methods = new HashSet<>();

    for (CallMethodStartEvent call : objectContext.mutatorCalls()) {
      CtMethod<?> invokedMethod = Spoons.getCtMethod(factory, call.method());
      methods.add(invokedMethod);
      List<CtParameter<?>> parameters = invokedMethod.getParameters();
      List<Integer> delayedOn = new ArrayList<>();

      CtInvocation<?> invocation = factory.createInvocation(
          factory.createVariableRead(
              factory.createLocalVariableReference(
                  factory.createReference(objectContext.createEvent().clazz()),
                  name
              ),
              false
          ),
          invokedMethod.getReference()
      );
      // record this
      for (int j = 0; j < call.parameters().size(); j++) {
        Value parameter = call.parameters().get(j);
        String paramName =
            parameters.get(j).getSimpleName() + "_" + j + "_" + call.methodInvocationId();
        ConvertedValue value = valueAsCtExpression(parameter, paramName);
        delayedOn.addAll(value.read().delayedOn());
        for (PotentiallyDelayedValue<CtStatement> statement : value.statements()) {
          statements.add(statement);
          delayedOn.addAll(statement.delayedOn());
        }
        invocation.addArgument(value.read().value());
      }
      statements.add(new PotentiallyDelayedValue<>(invocation, delayedOn, List.of()));
    }

    return methods;
  }

  public static EventBasedObjectConstructor fromEvents(
      GenerationContext context, Factory factory, int objectId, long timestamp
  ) {
    return fromEvents(
        context, factory, objectId, timestamp, new HashMap<>(), new HashMap<>()
    );
  }

  private static EventBasedObjectConstructor fromEvents(
      GenerationContext context, Factory factory, int objectId, long timestamp,
      Map<Integer, GeneratedIdentityObject> generatedIdentityObjects,
      Map<Integer, List<CtStatement>> delayedStatements
  ) {
    ObjectContext objectContext = context.events().getContext(objectId, timestamp);

    return new EventBasedObjectConstructor(
        context,
        factory,
        timestamp,
        objectContext,
        generatedIdentityObjects,
        delayedStatements
    );
  }

  private record PotentiallyDelayedValue<T>(
      T value,
      List<Integer> delayedOn,
      List<Integer> unblocks
  ) {

    public boolean isDelayed() {
      return !delayedOn().isEmpty();
    }

    public static <T> PotentiallyDelayedValue<T> unblocking(T value, int first, int... rest) {
      List<Integer> unblocking = new ArrayList<>();
      unblocking.add(first);
      for (int i : rest) {
        unblocking.add(i);
      }
      return new PotentiallyDelayedValue<>(value, List.of(), unblocking);
    }

    public static <T> PotentiallyDelayedValue<T> immediate(T value) {
      return new PotentiallyDelayedValue<>(value, List.of(), List.of());
    }

    public static <T> PotentiallyDelayedValue<T> delayed(T value, int first, int... rest) {
      List<Integer> delayedOn = new ArrayList<>();
      delayedOn.add(first);
      for (int i : rest) {
        delayedOn.add(i);
      }
      return new PotentiallyDelayedValue<>(value, delayedOn, List.of());
    }
  }

  private record ConvertedValue(
      PotentiallyDelayedValue<CtExpression<?>> read,
      List<PotentiallyDelayedValue<CtStatement>> statements
  ) {

    public static ConvertedValue ofPure(CtExpression<?> expression) {
      return new ConvertedValue(PotentiallyDelayedValue.immediate(expression), List.of());
    }

    public static ConvertedValue ofPure(PotentiallyDelayedValue<CtExpression<?>> expression) {
      return new ConvertedValue(expression, List.of());
    }

    public static ConvertedValue ofImmediate(CtExpression<?> expression, CtStatement statement) {
      return new ConvertedValue(
          PotentiallyDelayedValue.immediate(expression),
          List.of(PotentiallyDelayedValue.immediate(statement))
      );
    }

  }

  private static class GeneratedIdentityObject {

    private final String name;
    private boolean constructed;

    private GeneratedIdentityObject(String name) {
      this.name = name;
    }

    public String name() {
      return name;
    }

    public boolean constructed() {
      return constructed;
    }

    public void setConstructed() {
      this.constructed = true;
    }

  }

}
