package se.kth.castor.pankti.codemonkey.serialization;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import se.kth.castor.pankti.codemonkey.construction.solving.SolvingState;
import se.kth.castor.pankti.codemonkey.util.ClassUtil;
import se.kth.castor.pankti.codemonkey.util.SerializationFailedException;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public class Serializer {

  private final Factory factory;
  private final BiFunction<CtClass<?>, Object, Optional<SolvingState>> solverFactory;
  private final UnknownActionHandler unknownActionHandler;

  public Serializer(
      Factory factory,
      BiFunction<CtClass<?>, Object, Optional<SolvingState>> solverFactory,
      UnknownActionHandler unknownActionHandler
  ) {
    this.factory = factory;
    this.unknownActionHandler = unknownActionHandler;
    this.solverFactory = solverFactory;
  }

  public Serialized serialize(
      Statistics statistics,
      Object object,
      String resultVariableName,
      CtTypeReference<?> targetType
  ) throws SerializationFailedException {
    return serialize(statistics, object, resultVariableName, targetType, namingUseFieldName());
  }

  public Serialized serialize(
      Statistics statistics,
      Object object,
      String resultVariableName,
      CtTypeReference<?> targetType,
      Function<Field, String> namingFunction
  ) throws SerializationFailedException {
    Instant start = Instant.now();
    Serialized serialized = new SerializerImpl(
        statistics,
        factory,
        solverFactory,
        resultVariableName,
        namingFunction,
        unknownActionHandler,
        targetType,
        object
    ).serialize();
    if (statistics != null) {
      statistics.getMixed()
          .addTimeSpentSerializing(ChronoUnit.MILLIS.between(start, Instant.now()));
    }
    return serialized;
  }

  @SuppressWarnings({"unchecked"})
  public static Serialized handleNullOrPrimitiveOrString(
      Factory factory,
      CtTypeReference<?> type,
      String name,
      Object o
  ) {
    List<CtStatement> statements = new ArrayList<>();
    if (o == null) {
      statements.add(factory.Code().createLocalVariable(
          type,
          name,
          factory.createLiteral(null)
      ));
    } else if (ClassUtil.isBasicallyPrimitive(o.getClass())) {
      statements.add(factory.Code().createLocalVariable(
          (CtTypeReference<? super Object>) type.unbox(),
          name,
          SpoonUtil.getLiteral(factory, o)
      ));
    }

    return new Serialized(statements, type);
  }

  public static Function<Field, String> namingUseFieldName() {
    Set<String> dispensedNames = new HashSet<>();
    return field -> {
      String name = field.getName();
      for (int i = 0; !dispensedNames.add(name); i++) {
        name = field.getName() + i;
      }
      return name;
    };
  }

  public record Serialized(List<CtStatement> statements, CtTypeReference<?> staticType) {

  }

}
