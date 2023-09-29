package se.kth.castor.pankti.codemonkey.serialization;

import static se.kth.castor.pankti.codemonkey.util.SpoonUtil.getClassLiteral;
import static se.kth.castor.pankti.codemonkey.util.SpoonUtil.isAccessible;
import static se.kth.castor.pankti.codemonkey.util.SpoonUtil.typeVarToRawtype;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.construction.actions.Action;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallConstructor;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallFactoryMethod;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallSetter;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionFixmeConstructObject;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionSetField;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionUseEnumConstant;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionUseStaticFieldInstance;
import se.kth.castor.pankti.codemonkey.construction.solving.SolvingState;
import se.kth.castor.pankti.codemonkey.serialization.Serializer.Serialized;
import se.kth.castor.pankti.codemonkey.util.ClassUtil;
import se.kth.castor.pankti.codemonkey.util.InheritanceUtil;
import se.kth.castor.pankti.codemonkey.util.SerializationFailedException;
import se.kth.castor.pankti.codemonkey.util.SolveFailedException;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import se.kth.castor.pankti.codemonkey.util.TodoError;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.support.adaption.TypeAdaptor;

@SuppressWarnings({"rawtypes", "unchecked"})
final class SerializerImpl {

  private final Factory factory;
  private final BiFunction<CtClass<?>, Object, Optional<SolvingState>> solverFactory;

  private final List<CtStatement> statements;
  private final IdentityHashMap<Object, String> complexValueNameMap;
  private final Function<Field, String> namingFunction;
  private final String myVariableName;
  private final UnknownActionHandler unknownActionHandler;
  private final Statistics statistics;

  private CtTypeReference<?> assignedType;
  private final Object object;
  private final CtClass<?> objectClass;

  public SerializerImpl(
      Statistics statistics,
      Factory factory,
      BiFunction<CtClass<?>, Object, Optional<SolvingState>> solverFactory,
      String myVariableName,
      Function<Field, String> namingFunction,
      UnknownActionHandler unknownActionHandler,
      CtTypeReference<?> assignedType,
      Object object
  ) {
    this(
        statistics,
        factory,
        solverFactory,
        myVariableName,
        namingFunction,
        unknownActionHandler,
        assignedType,
        object,
        new IdentityHashMap<>()
    );
  }

  private SerializerImpl(
      Statistics statistics,
      Factory factory,
      BiFunction<CtClass<?>, Object, Optional<SolvingState>> solverFactory,
      String myVariableName,
      Function<Field, String> namingFunction,
      UnknownActionHandler unknownActionHandler,
      CtTypeReference<?> assignedType,
      Object object,
      IdentityHashMap<Object, String> complexValueNameMap
  ) {
    this.statistics = statistics;
    this.factory = factory;
    this.solverFactory = solverFactory;
    this.myVariableName = myVariableName;
    this.namingFunction = namingFunction;
    this.unknownActionHandler = unknownActionHandler;
    this.object = object;
    this.objectClass = object == null ? null : factory.Class().get(object.getClass());
    if (isAnonymous(assignedType)) {
      this.assignedType = typeVarToRawtype(
          assignedTypeFromAnonymousType(assignedType).setImplicit(false)
      );
    } else {
      this.assignedType = typeVarToRawtype(assignedType);
    }
    this.statements = new ArrayList<>();
    this.complexValueNameMap = complexValueNameMap;
  }

  private static boolean isAnonymous(CtTypeReference<?> assignedType) {
    String simpleName = assignedType.getSimpleName();
    for (int i = 0; i < simpleName.length(); i++) {
      char c = simpleName.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  private CtTypeReference<?> assignedTypeFromAnonymousType(CtTypeReference<?> type) {
    if (type.getSuperclass() != null) {
      CtTypeReference<?> newType = type.getSuperclass();
      newType.setActualTypeArguments(type.getActualTypeArguments());
      return newType;
    }
    if (type.getSuperInterfaces().size() == 1) {
      CtTypeReference<?> newType = type.getSuperInterfaces().iterator().next();
      newType.setActualTypeArguments(type.getActualTypeArguments());
      return newType;
    }
    throw new IllegalStateException(
        "Anonymous type '" + type + "' has no superclass and the following interfaces: "
        + type.getSuperInterfaces()
    );
  }

  private CtTypeReference<?> typeArgumentOrObject(int index) {
    return index < assignedType.getActualTypeArguments().size()
        ? assignedType.getActualTypeArguments().get(index)
        : factory.Type().objectType();
  }

  Serialized serialize() throws SerializationFailedException {
    if (object != null && object.getClass().isSynthetic()) {
      System.out.println("SKIPPED SYNTHETIC " + object);
      throw new SolveFailedException(object.getClass(), objectClass);
    }
    if (object == null || ClassUtil.isBasicallyPrimitive(object.getClass())) {
      if (statistics != null) {
        statistics.getStructureBased().addBasicallyPrimitive();
        statistics.getMixed()
            // random fallback class...
            .addStructureSerializedInProd(object == null ? Class.class : object.getClass());
      }
      return Serializer.handleNullOrPrimitiveOrString(
          factory,
          assignedType,
          myVariableName,
          object
      );
    }
    if (isBlacklistedClass()) {
      if (statistics != null) {
        statistics.getMixed().addBlacklisted();
      }
      throw new SolveFailedException(object.getClass(), objectClass);
    }

    List<CtStatement> asInbuilt = serializeAsInbuiltType();
    if (!asInbuilt.isEmpty()) {
      if (statistics != null && !(object instanceof Map.Entry<?,?>)) {
        statistics.getMixed().addInternallySerializedInProd(object.getClass());
      }
      return new Serialized(asInbuilt, assignedType);
    }

    Optional<SolvingState> solvingState = solverFactory.apply(objectClass, object);

    if (solvingState.isEmpty()) {
      if (statistics != null) {
        statistics.getMixed().addFailed(object.getClass());
      }
      throw new SolveFailedException(object.getClass(), objectClass);
    }

    assignedType = InheritanceUtil.findLeastSpecificTypeOrInitial(
        objectClass,
        assignedType,
        solvingState.get().actions().stream()
            .filter(it -> it instanceof ActionCallSetter)
            .map(it -> ((ActionCallSetter) it).setter())
            .collect(Collectors.toSet()),
        solvingState.get().actions().stream()
            .filter(it -> it instanceof ActionSetField)
            .map(it -> ((ActionSetField) it).field())
            .collect(Collectors.toSet())
    );

    boolean serializedUsingDynamicFeature = false;

    for (Action action : solvingState.get().actions()) {
      CtStatement statement;
      if (action instanceof ActionCallConstructor callConstructor) {
        statement = handleCallConstructor(callConstructor);
      } else if (action instanceof ActionCallFactoryMethod callFactoryMethod) {
        statement = handleCallFactoryMethod(callFactoryMethod);
      } else if (action instanceof ActionUseEnumConstant useEnumConstant) {
        statement = handleUseEnumConstant(useEnumConstant);
      } else if (action instanceof ActionUseStaticFieldInstance useStaticFieldInstance) {
        statement = handleUseStaticFieldInstance(useStaticFieldInstance);
        serializedUsingDynamicFeature = true;
      } else if (action instanceof ActionCallSetter callSetter) {
        statement = handleCallSetter(callSetter);
      } else if (action instanceof ActionSetField setField) {
        statement = handleSetField(setField);
      } else if (action instanceof ActionFixmeConstructObject) {
        statement = handleFixmeConstructObject();
      } else {
        statement = unknownActionHandler.handleAction(action, assignedType, myVariableName, object);
        serializedUsingDynamicFeature = true;
      }
      if (statement != null) {
        statements.add(statement);
      }
    }

    if (statements.isEmpty()) {
      if (statistics != null) {
        statistics.getMixed().addFailed(object.getClass());
      }
      throw new TodoError(
          "Somehow, no statements returned for " + object + " " + objectClass.getQualifiedName()
      );
    }

    if (statistics != null) {
      if (!serializedUsingDynamicFeature) {
        statistics.getMixed().addStructureSerializedInProd(object.getClass());
      }
    }
    return new Serialized(statements, assignedType);
  }

  private boolean isBlacklistedClass() {
    String name = object.getClass().getName();
    return name.startsWith("org.hibernate.collection");
  }

  private List<CtStatement> serializeAsInbuiltType() throws SerializationFailedException {
    if (object.getClass().isArray()) {
      return serializeAsArray();
    }
    if (object instanceof List<?> list && object.getClass().getDeclaringClass() != null) {
      if (object.getClass()
          .getDeclaringClass()
          .getName()
          .equals("java.util.ImmutableCollections")) {
        return serializeAsImmutableList(list);
      }
    }
    if (object instanceof List<?> list) {
      return serializeAsList(list);
    }
    if (object instanceof Set<?> set) {
      return serializeAsSet(set);
    }
    if (object instanceof Queue<?> queue) {
      return serializeAsQueue(queue);
    }
    if (object instanceof Map<?, ?> map) {
      return serializeAsMap(map);
    }
    if (object instanceof Map.Entry<?, ?> entry) {
      return serializeAsMapEntry(entry);
    }
    if (object instanceof Class<?> clazz && !clazz.isSynthetic()) {
      return List.of(createMyVariable(
          getClassLiteral(factory, factory.createCtTypeReference(clazz))
      ));
    }
    if (object instanceof File file) {
      return List.of(createMyVariable(factory.createConstructorCall(
          factory.createCtTypeReference(File.class),
          factory.createLiteral(file.getPath())
      )));
    }
    if (object instanceof Path path) {
      CtTypeReference<?> pathRef = factory.createCtTypeReference(Path.class);
      assignedType = pathRef;
      return List.of(createMyVariable(
          factory.createInvocation(
              factory.createTypeAccess(pathRef),
              factory.createExecutableReference()
                  .setDeclaringType(pathRef)
                  .setSimpleName("of"),
              factory.createLiteral(path.toString())
          ))
      );
    }
    if (object instanceof StringBuilder) {
      return List.of(createMyVariable(
          factory.createConstructorCall(
              factory.createCtTypeReference(StringBuilder.class),
              factory.createLiteral(object.toString())
          )
      ));
    }
    if (object instanceof BigDecimal decimal) {
      return List.of(createMyVariable(factory.createConstructorCall(
          factory.createCtTypeReference(BigDecimal.class),
          factory.createLiteral(decimal.toString())
      )));
    }
    if (object instanceof BigInteger integer) {
      return List.of(createMyVariable(factory.createConstructorCall(
          factory.createCtTypeReference(BigInteger.class),
          factory.createLiteral(integer.toString())
      )));
    }
    if (object instanceof Locale locale) {
      return List.of(createMyVariable(factory.createConstructorCall(
          factory.createCtTypeReference(Locale.class),
          factory.createLiteral(locale.getLanguage()),
          factory.createLiteral(locale.getCountry()),
          factory.createLiteral(locale.getVariant())
      )));
    }
    if (object instanceof Optional<?> optional) {
      if (optional.isEmpty()) {
        return serializeWithArgumentsList(
            assignedType,
            typeArgumentOrObject(0),
            "java.util.Optional.empty()",
            List.of()
        );
      } else {
        return serializeWithArgumentsList(
            assignedType,
            typeArgumentOrObject(0),
            "java.util.Optional.of({args})",
            List.of(optional.get())
        );
      }
    }

    return List.of();
  }

  private List<CtStatement> serializeAsArray() throws SerializationFailedException {
    CtTypeReference<?> innerType = factory.Type()
        .get(object.getClass().getComponentType())
        .getReference();

    List<Object> values = new ArrayList<>();
    for (int i = 0; i < Math.min(Array.getLength(object), 25); i++) {
      Object entry = Array.get(object, i);
      values.add(entry);
    }

    return serializeListToArray(assignedType, myVariableName, innerType, values);
  }

  private List<CtStatement> serializeListToArray(
      CtTypeReference<?> arrayType,
      String name,
      CtTypeReference<?> componentType,
      List<Object> entries
  ) throws SerializationFailedException {
    CtNewArray newArray = factory.createNewArray();
    newArray.setElements(serializeListToArguments(name, componentType, entries));

    statements.add(factory.Code().createLocalVariable(
        arrayType,
        name,
        newArray
    ));

    return statements;
  }

  private List<CtVariableAccess<?>> serializeListToArguments(
      String namePrefix,
      CtTypeReference<?> componentType,
      List<?> entries
  ) throws SerializationFailedException {
    List<CtVariableAccess<?>> reads = new ArrayList<>();

    for (int i = 0; i < entries.size(); i++) {
      Object entry = entries.get(i);
      String entryVariableName = namePrefix + "_" + i;
      reads.add(serializeValueAndGetRead(entryVariableName, componentType, entry));
    }

    return reads;
  }

  private CtVariableAccess<?> serializeValueAndGetRead(
      String name,
      CtTypeReference<?> componentType,
      Object value
  ) throws SerializationFailedException {
    CtVariableAccess<?> read = factory.createVariableRead(
        factory.createLocalVariableReference(componentType, name),
        false
    );

    statements.addAll(
        new SerializerImpl(
            statistics,
            factory,
            solverFactory,
            name,
            namingFunction,
            unknownActionHandler,
            componentType,
            value,
            complexValueNameMap
        )
            .serialize()
            .statements()
    );

    return read;
  }

  private List<CtStatement> serializeAsList(List<?> elements) throws SerializationFailedException {
    CtTypeReference<?> listTypeRef = implementingCollection(List.class, ArrayList.class);
    if (listTypeRef.getQualifiedName().endsWith(".support.util.EmptyClearableList")) {
      listTypeRef = factory.createCtTypeReference(ArrayList.class);
    }
    String listType = listTypeRef.getQualifiedName();
    if (listTypeRef.getTypeDeclaration() != null &&
        listTypeRef.getTypeDeclaration().getFormalCtTypeParameters().isEmpty()) {
      listType += "";
    } else {
      listType += "<>";
    }

    return serializeWithArgumentsList(
        assignedType,
        typeArgumentOrObject(0),
        "new " + listType + "(java.util.List.of({args}))",
        elements
    );
  }

  private List<CtStatement> serializeAsSet(Set<?> elements) throws SerializationFailedException {
    CtTypeReference<?> setTypeRef = implementingCollection(Set.class, HashSet.class);
    String setType = setTypeRef.getQualifiedName();
    if (setTypeRef.getTypeDeclaration() != null &&
        setTypeRef.getTypeDeclaration().getFormalCtTypeParameters().isEmpty()) {
      setType += "";
    } else {
      setType += "<>";
    }

    return serializeWithArgumentsList(
        assignedType,
        typeArgumentOrObject(0),
        "new " + setType + "(java.util.Set.of({args}))",
        List.copyOf(elements)
    );
  }

  private List<CtStatement> serializeAsQueue(Queue<?> elements)
      throws SerializationFailedException {
    CtTypeReference<?> queueTypeRef = implementingCollection(Queue.class, ArrayDeque.class);
    String queueType = queueTypeRef.getQualifiedName();
    if (queueTypeRef.getTypeDeclaration() != null &&
        queueTypeRef.getTypeDeclaration().getFormalCtTypeParameters().isEmpty()) {
      queueType += "";
    } else {
      queueType += "<>";
    }

    return serializeWithArgumentsList(
        assignedType,
        typeArgumentOrObject(0),
        "new " + queueType + "(java.util.List.of({args}))",
        List.copyOf(elements)
    );
  }

  private List<CtStatement> serializeAsMap(Map<?, ?> elements) throws SerializationFailedException {
    CtTypeReference<?> mapTypeRef = implementingCollection(Map.class, HashMap.class);
    String mapType = mapTypeRef.getQualifiedName();
    if (mapTypeRef.getTypeDeclaration() != null &&
        mapTypeRef.getTypeDeclaration().getFormalCtTypeParameters().isEmpty()) {
      mapType += "";
    } else {
      mapType += "<>";
    }

    // Some collections do not implement `toArray()`. This method is used by the ArrayList
    // constructor and Collections#addAll though. Therefore, we manually perform the loop here...
    List<Entry<?, ?>> entries = new ArrayList<>();
    for (Entry<?, ?> entry : elements.entrySet()) {
      //noinspection UseBulkOperation
      entries.add(entry);
    }

    return serializeWithArgumentsList(
        assignedType,
        factory.createCtTypeReference(Map.Entry.class)
            .setActualTypeArguments(List.of(
                typeArgumentOrObject(0),
                typeArgumentOrObject(1)
            )),
        "new " + mapType + "(java.util.Map.ofEntries({args}))",
        entries
    );
  }

  private <T> CtTypeReference<?> implementingCollection(
      Class<T> targetClass, Class<? extends T> defaultType
  ) {
    CtTypeReference<?> targetRef = factory.createCtTypeReference(targetClass);
    if (isAccessible(objectClass) && TypeAdaptor.isSubtype(objectClass, targetRef)) {
      return objectClass.getReference();
    }
    if (!assignedType.isInterface() &&
        TypeAdaptor.isSubtype(assignedType.getTypeDeclaration(), targetRef)) {
      return assignedType;
    }
    return factory.createCtTypeReference(defaultType);
  }

  private List<CtStatement> serializeAsMapEntry(Map.Entry<?, ?> entry)
      throws SerializationFailedException {
    CtVariableAccess<?> key = serializeValueAndGetRead(
        myVariableName + "_key", typeArgumentOrObject(0), entry.getKey()
    );
    CtVariableAccess<?> value = serializeValueAndGetRead(
        myVariableName + "_value", typeArgumentOrObject(1), entry.getValue()
    );
    statements.add(createMyVariable(
        factory.createInvocation(
            factory.createTypeAccess(factory.createCtTypeReference(Map.class)),
            factory.createExecutableReference()
                .setDeclaringType(factory.createCtTypeReference(Map.class))
                .setSimpleName("entry"),
            key,
            value
        )
    ));

    return statements;
  }

  private List<CtStatement> serializeAsImmutableList(List<?> elements)
      throws SerializationFailedException {
    return serializeWithArgumentsList(
        assignedType,
        typeArgumentOrObject(0),
        "java.util.List.of({args})",
        elements
    );
  }

  private List<CtStatement> serializeWithArgumentsList(
      CtTypeReference<?> assignedType,
      CtTypeReference<?> componentType,
      String template,
      List<?> elements
  ) throws SerializationFailedException {
    List<CtVariableAccess<?>> reads = serializeListToArguments(
        myVariableName,
        componentType,
        elements
    );
    String argumentString = reads.stream()
        .map(it -> it.getVariable().getSimpleName())
        .collect(Collectors.joining(", "));

    statements.add(factory.Code().createLocalVariable(
        assignedType,
        myVariableName,
        factory.createCodeSnippetExpression(
            template.replace("{args}", argumentString)
        )
    ));

    return statements;
  }

  private CtStatement handleCallConstructor(
      ActionCallConstructor callConstructor
  ) throws SerializationFailedException {
    CtConstructorCall call = factory.Core().createConstructorCall();
    call.setExecutable(callConstructor.constructor().getReference());
    if (objectClass.isGenerics()) {
      // Force a diamond operator
      call.getExecutable().getType().addActualTypeArgument(
          factory.Type().objectType().setImplicit(true)
      );
    }
    for (CtParameter<?> parameter : callConstructor.constructor().getParameters()) {
      // They all map to the same, pick a random one
      call.addArgument(getFieldValue(callConstructor.parameters().get(parameter).get(0), object));
    }
    return createMyVariable(
        call
    );
  }

  private CtStatement handleCallFactoryMethod(
      ActionCallFactoryMethod callFactoryMethod
  ) throws SerializationFailedException {
    CtInvocation invocation = factory.Code().createInvocation(
        // static call, so target is a type access
        factory.Code().createTypeAccess(
            callFactoryMethod.method().getDeclaringType().getReference()
        ),
        callFactoryMethod.method().getReference()
    );
    for (CtParameter<?> parameter : callFactoryMethod.method().getParameters()) {
      invocation.addArgument(
          // They all map to the same, pick a random one
          getFieldValue(callFactoryMethod.parameters().get(parameter).get(0), object)
      );
    }
    return createMyVariable(
        invocation
    );
  }

  private CtStatement handleUseEnumConstant(ActionUseEnumConstant useEnumConstant) {
    Class<? extends Enum> enumClass = (Class<? extends Enum>) object.getClass();
    String myName = null;
    for (Enum enumConstant : enumClass.getEnumConstants()) {
      if (enumConstant == object) {
        myName = enumConstant.name();
        break;
      }
    }
    if (myName == null) {
      throw new RuntimeException("Enum instance for " + object + " not found!");
    }
    CtEnumValue<?> enumValue = useEnumConstant.type().getEnumValue(myName);
    return createMyVariable(
        ((CtFieldRead) factory.createFieldRead()).setVariable(enumValue.getReference())
    );
  }

  private CtStatement handleUseStaticFieldInstance(
      ActionUseStaticFieldInstance useStaticFieldInstance
  ) {
    return createMyVariable(
        ((CtFieldRead) factory.createFieldRead())
            .setVariable(useStaticFieldInstance.field().getReference())
    );
  }

  private CtInvocation<?> handleCallSetter(
      ActionCallSetter callSetter
  ) throws SerializationFailedException {
    CtInvocation<?> invocation = factory.Code().createInvocation(
        factory.createVariableRead(
            factory.createCatchVariableReference().setSimpleName(myVariableName),
            false
        ),
        callSetter.setter().getReference()
    );
    int skippedArguments = 0;
    for (CtParameter<?> parameter : callSetter.setter().getParameters()) {
      // They all map to the same, pick a random one
      CtField<?> field = callSetter.parameters().get(parameter).get(0);
      CtExpression<?> value = getFieldValue(field, object);
      if (value instanceof CtLiteral<?> literal && SpoonUtil.isDefaultValue(literal)) {
        // FIXME: This is not strictly correct if the constructor sets a value
        if (field.getDefaultExpression() == null) {
          skippedArguments++;
        }
      }
      invocation.addArgument(value);
    }
    if (skippedArguments == callSetter.setter().getParameters().size()) {
      return null;
    }
    return invocation;
  }

  private CtAssignment handleSetField(
      ActionSetField setField
  ) throws SerializationFailedException {
    // e.g. `name.foo = "bar";`
    CtExpression<?> value = getFieldValue(setField.field(), object);
    if (value instanceof CtLiteral<?> literal && SpoonUtil.isDefaultValue(literal)) {
      if (setField.field().getDefaultExpression() == null) {
        // FIXME: This is not strictly correct if the constructor sets a value
        return null;
      }
    }
    return (CtAssignment) ((CtAssignment) factory.createAssignment())
        .setAssigned(
            factory.createCodeSnippetExpression(
                myVariableName + "." + setField.field().getSimpleName())
        )
        .setAssignment(
            value
        );
  }

  private CtStatement handleFixmeConstructObject() {
    return createMyVariable(factory.createLiteral(null))
        .addComment(factory.createInlineComment("FIXME: Construct instance"));
  }

  private <T> CtLocalVariable<T> createMyVariable(CtExpression<?> expr) {
    return factory.createLocalVariable(
        (CtTypeReference) assignedType,
        myVariableName,
        expr
    );
  }

  private CtExpression<?> getFieldValue(
      CtField<?> ctField,
      Object handle
  ) throws SerializationFailedException {
    // Find value via reflection
    try {
      Field field = ctField.getDeclaringType().getActualClass()
          .getDeclaredField(ctField.getSimpleName());
      // A bit risky, but what can you do. We only read, which makes this slightly better.
      field.setAccessible(true);

      Object value = field.get(handle);

      // Simple values can be inlined
      if (field.getType().isPrimitive() || field.getType() == String.class || value == null) {
        return SpoonUtil.getLiteral(factory, value);
      }

      // Complex (=Object) values need to be handled carefully. We need to recursively create an
      // object of that type and consider request cycles.

      return getComplexFieldValue(field, value);
    } catch (ReflectiveOperationException e) {
      throw new SerializationFailedException(objectClass, e);
    }
  }

  private CtVariableAccess<Object> getComplexFieldValue(
      Field field,
      Object value
  ) throws SerializationFailedException {
    if (!complexValueNameMap.containsKey(value)) {
      String nestedObjectName = getNestedObjectName(field);
      CtTypeReference<?> fieldType = factory.Type().get(field.getDeclaringClass())
          .getField(field.getName())
          .getType();

      // Note down what we decided to call this nested object, so we can find it again :^)
      complexValueNameMap.put(value, nestedObjectName);

      if (value != null && !ClassUtil.isBasicallyPrimitive(field.getType())) {
        SerializerImpl nestedSerializer = new SerializerImpl(
            statistics,
            factory,
            solverFactory,
            nestedObjectName,
            namingFunction,
            unknownActionHandler,
            fieldType,
            value,
            complexValueNameMap
        );

        // First serialize that object before we do anything else with our life
        // TODO: Should we put nested objects before any of our statements?
        statements.addAll(nestedSerializer.serialize().statements());
      } else {
        if (statistics != null) {
          statistics.getStructureBased().addBasicallyPrimitive();
        }
        statements.addAll(Serializer.handleNullOrPrimitiveOrString(
            factory,
            fieldType,
            nestedObjectName,
            value
        ).statements());
      }
    }

    // We already built a variable for this object, return a reference to it
    return factory.createVariableRead(
        factory.Core().createLocalVariableReference().setSimpleName(complexValueNameMap.get(value)),
        false
    );
  }

  private String getNestedObjectName(Field field) {
    String nestedObjectName = namingFunction.apply(field);

    if (complexValueNameMap.containsValue(nestedObjectName)) {
      throw new IllegalArgumentException(
          "Naming function is not unique, produced '" + nestedObjectName + "' for " + field
      );
    }

    return nestedObjectName;
  }

}
