package se.kth.castor.rockstofetch.serialization;

import se.kth.castor.rockstofetch.util.SpoonAccessor;
import se.kth.castor.rockstofetch.util.Spoons;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import se.kth.castor.pankti.codemonkey.construction.solving.ClassConstructionSolver;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationBindConstructorParameter;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationCallDefaultConstructor;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationCallSetter;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationCallSimpleFactoryMethod;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationFixmeConstructObject;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationMockObject;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationSetField;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationStrategy;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationUseEnumConstant;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationUseStandardCharset;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationUseStaticFieldInstance;
import se.kth.castor.pankti.codemonkey.construction.solving.SolvingState;
import se.kth.castor.pankti.codemonkey.serialization.Serializer;
import se.kth.castor.pankti.codemonkey.serialization.Serializer.Serialized;
import se.kth.castor.pankti.codemonkey.serialization.UnknownActionHandler;
import se.kth.castor.pankti.codemonkey.util.SerializationFailedException;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import se.kth.castor.pankti.codemonkey.util.TodoError;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;

public class RockySerializer {

  private final Serializer serializer;
  private final List<MutationStrategy> mutations;
  private final AtomicInteger uniqueVariableSuffix;
  private final SpoonAccessor spoonAccessor;
  private final Statistics statistics;
  private final ThreadLocal<Statistics> activeStatistics;

  public RockySerializer(
      SpoonAccessor spoonAccessor,
      Set<String> mockConstructTypes,
      Set<String> fixmeConstructTypes,
      Set<String> mutationTraceTypes,
      UnknownActionHandler unknownActionHandler,
      Statistics statistics
  ) {
    this(
        spoonAccessor, mockConstructTypes, fixmeConstructTypes, mutationTraceTypes,
        unknownActionHandler, new AtomicInteger(),
        statistics
    );
  }

  public RockySerializer(
      SpoonAccessor spoonAccessor,
      Set<String> mockConstructTypes,
      Set<String> fixmeConstructTypes,
      Set<String> mutationTraceTypes,
      UnknownActionHandler unknownActionHandler,
      AtomicInteger uniqueVariableSuffix,
      Statistics statistics
  ) {
    this.activeStatistics = new ThreadLocal<>();
    this.statistics = statistics;
    this.mutations = new ArrayList<>(List.of(
        new MutationBindConstructorParameter(),
        new MutationCallDefaultConstructor(),
        new MutationCallSetter(),
        new MutationCallSimpleFactoryMethod(),
        new MutationSetField(),
        new MutationUseEnumConstant(),
        new MutationUseStaticFieldInstance(),
        new MutationUseStandardCharset()
    ));
    if (!mutationTraceTypes.isEmpty()) {
      this.mutations.add(new MutationUseObjectReference(mutationTraceTypes));
    }
    if (!mockConstructTypes.isEmpty()) {
      this.mutations.add(new MutationMockObject(mockConstructTypes));
    }
    if (!fixmeConstructTypes.isEmpty()) {
      this.mutations.add(new MutationFixmeConstructObject(fixmeConstructTypes));
    }

    this.serializer = new Serializer(
        spoonAccessor.getFactory(),
        ClassConstructionSolver.cached((ctClass, instance) -> new ClassConstructionSolver(
            mutations,
            SolvingState.constructType(ctClass),
            activeStatistics::get
        )),
        unknownActionHandler
    );
    this.uniqueVariableSuffix = uniqueVariableSuffix;
    this.spoonAccessor = spoonAccessor;
  }

  public boolean canSerializeStructurally(CtTypeReference<?> type) {
    if (type instanceof CtArrayTypeReference<?> arrayTypeRef) {
      type = arrayTypeRef.getArrayType();
    }
    if (type.isGenerics()) {
      type = type.getTypeErasure();
    }
    String name = type.getQualifiedName();
    Factory factory = type.getFactory();

    CtType<?> ctType = factory.Type().get(name);
    if (ctType == null
        && Spoons.isBasicallyPrimitive(factory.createTypeReference().setSimpleName(name))) {
      return true;
    }
    if (ctType == null) {
      try {
        ctType = factory.Type()
            .get(Class.forName(name, false, factory.getEnvironment().getInputClassLoader()));
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }
    Objects.requireNonNull(ctType);

    if (Spoons.isBasicallyPrimitive(ctType.getReference())) {
      return true;
    }
    if (ctType instanceof CtClass<?> ctClass) {
      return solveStaticSerialization(ctClass).isPresent();
    }

    return false;
  }

  public Optional<SolvingState> solveStaticSerialization(CtClass<?> type) {
    try {
      if (type.isArray()) {
        throw new IllegalArgumentException(
            "Arrays not supported in solve static serialization. Ask for the component type."
        );
      }
      return new ClassConstructionSolver(this.mutations, SolvingState.constructType(type), null)
          .solveStatic()
          .state();
    } catch (TodoError e) {
      return Optional.empty();
    }
  }

  public JavaSnippet serialize(
      Object o,
      Class<?> targetJavaType,
      String name
  ) throws SerializationFailedException {
    if (targetJavaType.getName().contains("/")) {
      return new JavaSnippet(List.of(), targetJavaType, targetJavaType, 0);
    }
    CtTypeReference<?> assignedType = spoonAccessor.createTypeReference(targetJavaType);

    return serialize(o, assignedType, name);
  }

  public JavaSnippet serialize(
      Object o,
      CtTypeReference<?> assignedType,
      String name
  ) throws SerializationFailedException {
    if (o != null && o.getClass()
        .getName()
        .equals("com.graphhopper.routing.weighting.custom.JaninoCustomWeightingHelperSubclass2")) {
      return new JavaSnippet(List.of(), o.getClass().getName(), o.getClass().getName(), 0);
    }
    // TODO: Remove this expensive sanity check
    if (o != null && !targetTypeCongruent(o, assignedType.getActualClass())) {
      throw new RuntimeException(
          "Inconsistent target type: " + o.getClass() + " -> " + assignedType.getActualClass()
      );
    }

//    Statistics thisObjStats = new Statistics();
    Statistics thisObjStats = statistics;
    try {
      activeStatistics.set(thisObjStats);
      Serialized serialized = serializer.serialize(
          thisObjStats,
          o,
          name,
          assignedType,
          field -> field.getName() + uniqueVariableSuffix.incrementAndGet()
      );

      return new JavaSnippet(
          serialized.statements().stream().map(Object::toString).toList(),
          o != null ? o.getClass() : void.class,
          serialized.staticType().getQualifiedName(),
          thisObjStats.getMixed().getAllObjects() - thisObjStats.getStructureBased()
              .getBasicallyPrimitive()
      );
    } finally {
//      this.statistics.assimilateOther(thisObjStats);
    }
  }

  private static boolean targetTypeCongruent(Object o, Class<?> targetJavaType) {
    if (targetJavaType.isAssignableFrom(o.getClass())) {
      return true;
    }
    if (!targetJavaType.isPrimitive()) {
      return false;
    }
    return o.getClass()
        .getName()
        .toLowerCase(Locale.ROOT)
        .replace("java.lang.", "")
        .startsWith(targetJavaType.getName());
  }
}
