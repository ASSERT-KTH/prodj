package se.kth.castor.rockstofetch.instrument.aspects;

import static se.kth.castor.rockstofetch.instrument.CaptureContextHolder.toSnippet;

import se.kth.castor.rockstofetch.instrument.CaptureContextHolder;
import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import se.kth.castor.rockstofetch.instrument.RecordedNestedInvocation;
import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class MutInvocationPointcutNested {

  @Advice.OnMethodEnter(inline = false)
  public static PendingRecordedNestedInvocation onBefore(
      @Advice.This Object receiver,
      @Advice.Origin Method method,
      @Advice.AllArguments Object[] parameters
  ) {
    if (CaptureContextHolder.isInAgentCode()) {
      return null;
    }

    try (var ignored = CaptureContextHolder.enterAgentCode()) {
      if (CaptureContextHolder.nestedCapturingPaused()) {
        return null;
      }

      Integer targetId = CaptureContextHolder.getCurrentMut().getId(receiver);
      // Might happen for:
      // MUT
      //  `- sth [creates receiver]
      //    `- this method, receiver not found!
      // We can ignore these situations though, as "sth" is either mocked or constructable. Its inner
      // working is irrelevant.
      if (targetId == null) {
        System.err.println(
            "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(🧗) "
            + method.getName()
            + " (nested)"
        );
        return null;
      }

      RecordedMethod recordedMethod = RecordedMethod.fromReflectMethod(method);

      System.err.print(
          "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(+) "
          + method.getName()
          + " (nested)"
      );

      System.out.println(
          " (<- " + CaptureContextHolder.getCurrentMut().method().methodName() + ")"
      );

      return new PendingRecordedNestedInvocation(
          CaptureContextHolder.getCurrentMut().invocationId(),
          recordedMethod,
          CaptureContextHolder.getParameterSnippets(receiver, parameters, recordedMethod),
          CaptureContextHolder.toSnippet(receiver, receiver.getClass(), "receiver"),
          targetId
      );
    }
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, inline = false)
  public static void onReturn(
      @Advice.This Object receiverPost,
      @Advice.Origin("#m") String methodName,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
      @Advice.Thrown Throwable thrown,
      @Advice.Enter PendingRecordedNestedInvocation pendingInvocation
  ) {
    if (pendingInvocation == null) {
      return;
    }
    try (var ignored = CaptureContextHolder.enterAgentCode()) {
      if (thrown != null) {
        System.err.println(
            "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(x) "
            + methodName
            + " (nested)"
        );
        return;
      }

      System.err.println(
          "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(-) "
          + methodName
          + " (nested)"
      );
      JavaSnippet returnSnippet = CaptureContextHolder.getReturnValueSnippet(
          receiverPost, returned, pendingInvocation.method()
      );
      CaptureContextHolder.persistInvocation(pendingInvocation.finished(
          receiverPost,
          returnSnippet
      ));
    }
  }

  public record PendingRecordedNestedInvocation(
      UUID parentInvocationId,
      RecordedMethod method,
      List<JavaSnippet> parameters,
      JavaSnippet receiverPre,
      int targetId
  ) {

    public RecordedNestedInvocation finished(
        Object receiverPost,
        JavaSnippet returned
    ) {
      return new RecordedNestedInvocation(
          parentInvocationId,
          method,
          parameters,
          receiverPre,
          CaptureContextHolder.toSnippet(receiverPost, receiverPost.getClass(), "receiver_post"),
          returned,
          targetId
      );
    }
  }

}
