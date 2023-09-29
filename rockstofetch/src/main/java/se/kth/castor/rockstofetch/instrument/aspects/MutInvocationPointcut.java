package se.kth.castor.rockstofetch.instrument.aspects;

import static se.kth.castor.rockstofetch.instrument.CaptureContextHolder.toSnippet;

import se.kth.castor.rockstofetch.instrument.AgentMain;
import se.kth.castor.rockstofetch.instrument.CaptureContextHolder;
import se.kth.castor.rockstofetch.instrument.CaptureContextHolder.ClaimedMethodEntry;
import se.kth.castor.rockstofetch.instrument.RecordedInvocation;
import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;

public class MutInvocationPointcut {

  public static final Map<String, Integer> finishedTracing = new ConcurrentHashMap<>();

  @Advice.OnMethodEnter(inline = false)
  public static PendingRecordedInvocation onBefore(
      @Advice.This Object receiver,
      @Advice.Origin Method method,
      @Advice.Origin("#t") String declaringType,
      @Advice.Origin("#m") String methodName,
      @Advice.Origin("#s") String signature,
      @Advice.AllArguments Object[] parameters
  ) {
    if (CaptureContextHolder.isInAgentCode()) {
      return null;
    }
    int invokeCount = finishedTracing.merge(
        declaringType + methodName + signature, 1, Integer::sum
    );
    if (invokeCount > 10) {
      return null;
    }
    if (invokeCount == 1 && AgentMain.statistics != null) {
      // on first invocation, we count you
      AgentMain.statistics.getGeneral().addInvokedMut();
    }
    try (var ignored = CaptureContextHolder.enterAgentCode()) {
      RecordedMethod recordedMethod = RecordedMethod.fromReflectMethod(method);

      UUID myUuid = UUID.randomUUID();
      System.err.println(
          "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(+) "
          + method.getName()
          + " (normal)"
      );

      ClaimedMethodEntry parentMut = CaptureContextHolder.getEffectiveMappingParent();
      Map<Object, Integer> myObjectToIdMap = new IdentityHashMap<>(
          parentMut != null ? parentMut.objectToIdMap() : Map.of()
      );
      int nextId = parentMut != null ? parentMut.nextId() : 0;
      var idToNameMap = captureParametersAndFields(
          myObjectToIdMap,
          nextId,
          receiver,
          parameters
      );

      CaptureContextHolder.pushMutInvocation(new ClaimedMethodEntry(
          myUuid,
          recordedMethod,
          myObjectToIdMap,
          idToNameMap,
          nextId + idToNameMap.size()
      ));

      return new PendingRecordedInvocation(
          parentMut == null ? null : parentMut.invocationId(),
          myUuid,
          recordedMethod,
          CaptureContextHolder.getParameterSnippets(receiver, parameters, recordedMethod),
          CaptureContextHolder.toSnippet(receiver, receiver.getClass(), "receiver")
      );
    } catch (Exception e) {
      e.printStackTrace();
      Runtime.getRuntime().halt(8);
      throw e;
    }
  }

  public static Map<Integer, String> captureParametersAndFields(
      Map<Object, Integer> objectToIdMap,
      int nextId,
      Object receiver,
      Object[] parameters
  ) {
    Map<Integer, String> idToNameMap = new HashMap<>();

    for (int i = 0; i < parameters.length; i++) {
      int objectId = objectToIdMap.computeIfAbsent(
          parameters[i],
          ignored -> nextId + idToNameMap.size()
      );
      idToNameMap.put(objectId, "param:" + i);
    }
    try {
      // TODO: Doesn't work for methods declared in super classes accessing private fields
      for (Field field : sortedFields(receiver.getClass().getFields())) {
        field.setAccessible(true);
        int objectId = objectToIdMap.computeIfAbsent(
            field.get(receiver),
            ignored -> nextId + idToNameMap.size()
        );
        idToNameMap.put(objectId, "field:" + field.getName());
      }

      for (Field field : sortedFields(receiver.getClass().getDeclaredFields())) {
        if (Modifier.isPublic(field.getModifiers())) {
          continue;
        }
        field.setAccessible(true);
        int objectId = objectToIdMap.computeIfAbsent(
            field.get(receiver),
            ignored -> nextId + idToNameMap.size()
        );
        idToNameMap.put(objectId, "field:" + field.getName());
      }
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }

    return idToNameMap;
  }

  public static Field[] sortedFields(Field[] fields) {
    Arrays.sort(fields, Comparator.comparing(Field::getName));
    return fields;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, inline = false)
  public static void onReturn(
      @Advice.This Object receiverPost,
      @Advice.Origin("#r") String returnType,
      @Advice.Origin("#m") String methodName,
      @Advice.Return(typing = Typing.DYNAMIC) Object returned,
      @Advice.Thrown Throwable thrown,
      @Advice.Enter PendingRecordedInvocation pendingInvocation
  ) {
    if (pendingInvocation == null) {
      return;
    }
    try (var ignored = CaptureContextHolder.enterAgentCode()) {
      ClaimedMethodEntry entry = CaptureContextHolder.getCurrentMut();
      if (!entry.invocationId().equals(pendingInvocation.invocationId())) {
        throw new RuntimeException("Mismatched invocation id nesting in return");
      }

      if (thrown != null) {
        System.err.println(
            "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(x) "
            + methodName
            + " (normal)"
        );
        CaptureContextHolder.popMutInvocation(entry);
        return;
      }

      if (returnType.equals("void")) {
        CaptureContextHolder.persistInvocation(
            pendingInvocation.finishedVoidMethod(receiverPost, entry.idToNameMap())
        );
      } else {
        JavaSnippet returnSnippet = CaptureContextHolder.getReturnValueSnippet(
            receiverPost, returned, pendingInvocation.recordedMethod()
        );
        CaptureContextHolder.persistInvocation(pendingInvocation.finishedWithValue(
            receiverPost,
            returnSnippet,
            entry.idToNameMap()
        ));
      }

      CaptureContextHolder.popMutInvocation(entry);

      System.err.println(
          "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(-) "
          + methodName
          + " (normal)"
      );
    } catch (Exception e) {
      e.printStackTrace();
      Runtime.getRuntime().halt(9);
      throw e;
    }
  }

  public record PendingRecordedInvocation(
      UUID parentInvocationId,
      UUID invocationId,
      RecordedMethod recordedMethod,
      List<JavaSnippet> parameters,
      JavaSnippet receiverPre
  ) {

    public RecordedInvocation finishedVoidMethod(
        Object receiverPost,
        Map<Integer, String> idToNameMap
    ) {
      return new RecordedInvocation(
          parentInvocationId,
          invocationId,
          recordedMethod,
          parameters,
          idToNameMap,
          receiverPre,
          CaptureContextHolder.toSnippet(receiverPost, receiverPost.getClass(), "receiver_post"),
          new JavaSnippet(List.of(), void.class, void.class.getName(), 0)
      );
    }

    public RecordedInvocation finishedWithValue(
        Object receiverPost,
        JavaSnippet returned,
        Map<Integer, String> idToNameMap
    ) {
      return new RecordedInvocation(
          parentInvocationId,
          invocationId,
          recordedMethod,
          parameters,
          idToNameMap,
          receiverPre,
          CaptureContextHolder.toSnippet(receiverPost, receiverPost.getClass(), "receiver_post"),
          returned
      );
    }

  }

}
