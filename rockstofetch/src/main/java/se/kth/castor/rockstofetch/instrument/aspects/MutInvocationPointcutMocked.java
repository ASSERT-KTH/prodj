package se.kth.castor.rockstofetch.instrument.aspects;

import se.kth.castor.rockstofetch.instrument.CaptureContextHolder;
import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import se.kth.castor.rockstofetch.instrument.RecordedMockedInvocation;
import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import java.lang.reflect.Method;
import java.util.UUID;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class MutInvocationPointcutMocked {

  @Advice.OnMethodEnter(inline = false)
  public static PendingRecordedMockedInvocation onBefore(
      @Advice.This Object receiver,
      @Advice.Origin Method method
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
            + " (mock)"
        );
        return null;
      }

      RecordedMethod recordedMethod = RecordedMethod.fromReflectMethod(method);
      PendingRecordedMockedInvocation invocation = new PendingRecordedMockedInvocation(
          CaptureContextHolder.getCurrentMut().invocationId(),
          recordedMethod,
          targetId
      );

      System.err.print(
          "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(+) "
          + method.getName()
          + " (mock)"
      );

      System.out.println(
          " (<- " + CaptureContextHolder.getCurrentMut().method().methodName() + ")"
      );

      CaptureContextHolder.pushMockedInvocation();

      return invocation;
    }
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, inline = false)
  public static void onReturn(
      @Advice.This Object receiverPost,
      @Advice.Origin("#m") String methodName,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
      @Advice.Thrown Throwable thrown,
      @Advice.Enter PendingRecordedMockedInvocation pendingInvocation
  ) {
    if (pendingInvocation == null) {
      return;
    }
    try (var ignored = CaptureContextHolder.enterAgentCode()) {
      CaptureContextHolder.popMockedInvocation();
      if (thrown != null) {
        System.err.println(
            "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(x) "
            + methodName
            + " (mock)"
        );
        return;
      }

      System.err.println(
          "  ".repeat(CaptureContextHolder.peekMutDepth()) + "(-) "
          + methodName
          + " (mock)"
      );

      JavaSnippet returnSnippet = CaptureContextHolder.getReturnValueSnippet(
          receiverPost, returned, pendingInvocation.method()
      );
      CaptureContextHolder.persistInvocation(pendingInvocation.finished(returnSnippet));
    }
  }

  public record PendingRecordedMockedInvocation(
      UUID parentInvocationId,
      RecordedMethod method,
      int targetId
  ) {

    public RecordedMockedInvocation finished(JavaSnippet returned) {
      // TODO: Check if we already know the receiver and return a reference to it instead
      return new RecordedMockedInvocation(
          parentInvocationId,
          method,
          returned,
          targetId
      );
    }
  }

}
