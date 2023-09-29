package se.kth.castor.rockstofetch.instrument.aspects;

import se.kth.castor.rockstofetch.instrument.CaptureContextHolder;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;

public class MutationTracingConstructorPointcut {

  @Advice.OnMethodEnter(inline = false)
  public static PendingCreation onConstructorEnter(
      @Advice.Origin Constructor<?> origin,
      @Advice.AllArguments Object[] parameters
  ) {
    if (CaptureContextHolder.isInAgentCode()) {
      return null;
    }

    try (var ignored = CaptureContextHolder.enterAgentCode()) {
      long nextTimestamp = MutationTracingContextHolder.getNextTimestamp();
      return new PendingCreation(
          nextTimestamp,
          MutationTracingContextHolder.getConstructorParams(parameters, origin)
      );
    } catch (Exception e) {
      e.printStackTrace();
      Runtime.getRuntime().halt(3);
      throw e;
    }
  }

  @Advice.OnMethodExit(inline = false)
  public static void onConstructorExit(
      @Advice.This Object receiverPost,
      @Advice.Enter PendingCreation pendingCreation
  ) {
    if (pendingCreation == null) {
      return;
    }

    try (
        var ignored = CaptureContextHolder.enterAgentCode();
    ) {
      List<Object> fieldValues = new ArrayList<>();
      for (Field field : receiverPost.getClass().getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        if (!field.canAccess(receiverPost)) {
          field.setAccessible(true);
        }
        fieldValues.add(field.get(receiverPost));
      }
      MutationTracingContextHolder.registerObjectCreation(
          pendingCreation.timestamp(), receiverPost, pendingCreation.values(), fieldValues
      );
    } catch (ReflectiveOperationException e) {
      e.printStackTrace();
      Runtime.getRuntime().halt(4);
      throw new RuntimeException("Failed to fetch field", e);
    } catch (Exception e) {
      e.printStackTrace();
      Runtime.getRuntime().halt(5);
      throw e;
    }
  }

  public record PendingCreation(
      long timestamp,
      List<Value> values
  ) {

  }
}
