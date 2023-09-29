package se.kth.castor.rockstofetch.serialization;

import java.util.List;
import se.kth.castor.pankti.codemonkey.util.SolveFailedException;
import spoon.reflect.reference.CtTypeReference;

public class Serializers {


  public static JavaSnippet toSnippet(
      RockySerializer serializer, Object o, Class<?> targetType, String why
  ) {
    return doWithErrorHandling(why, o, () -> {
      if (targetType == void.class) {
        return new JavaSnippet(List.of(), void.class, void.class, 0);
      }
      return serializer.serialize(o, targetType, "$OBJ$");
    });
  }

  public static JavaSnippet toSnippet(
      RockySerializer serializer, Object o, CtTypeReference<?> assignedType, String why
  ) {
    return doWithErrorHandling(why, o, () -> {
      if (assignedType.getSimpleName().equals("void")) {
        return new JavaSnippet(List.of(), void.class, void.class, 0);
      }
      return serializer.serialize(o, assignedType, "$OBJ$");
    });
  }

  private static JavaSnippet doWithErrorHandling(
      String why, Object o, UncheckedSupplier<JavaSnippet> supplier
  ) {
    try {
      return supplier.get();
    } catch (SolveFailedException e) {
      return new JavaSnippet(List.of(), o.getClass(), o.getClass(), 0);
    } catch (Throwable e) {
      System.err.println("Serialization error: " + why + " " + e.getMessage());
      throw new RuntimeException(e);
    }
  }

  private interface UncheckedSupplier<T> {

    T get() throws Exception;

  }
}
