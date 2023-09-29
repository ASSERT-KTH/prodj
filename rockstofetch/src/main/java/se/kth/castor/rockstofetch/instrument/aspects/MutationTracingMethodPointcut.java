package se.kth.castor.rockstofetch.instrument.aspects;

import se.kth.castor.rockstofetch.instrument.CaptureContextHolder;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;
import se.kth.castor.pankti.codemonkey.util.ClassUtil;

public class MutationTracingMethodPointcut {

  @Advice.OnMethodEnter(inline = false)
  public static PendingInvocation onMethodEnter(
      @Advice.This(optional = true) Object receiver,
      @Advice.Origin Method method,
      @Advice.AllArguments Object[] parameters
  ) {
    if (CaptureContextHolder.isInAgentCode()) {
      return null;
    }
    try (var ignored = CaptureContextHolder.enterAgentCode()) {
      int invocationId = MutationTracingContextHolder.startMethodCall(
          receiver, method, MutationTracingContextHolder.getMethodParams(parameters, method)
      );
      if (invocationId < 0) {
        return null;
      }
      return new PendingInvocation(
          receiver == null ? -1 : MutationTracingContextHolder.getObjectId(receiver),
          invocationId
      );
    } catch (Exception e) {
      e.printStackTrace();
      Runtime.getRuntime().halt(1);
      throw e;
    }
  }

  @Advice.OnMethodExit(inline = false)
  public static void onMethodExit(
      @Advice.Return(typing = Typing.DYNAMIC) Object returned,
      @Advice.Enter PendingInvocation pendingInvocation
  ) {
    if (pendingInvocation == null) {
      return;
    }

    try (var ignored = CaptureContextHolder.enterAgentCode()) {
      Object processedReturnValue = null;
      if (returned != null && pendingInvocation.receiver() == -1) {
        if (!ClassUtil.isBoxed(returned.getClass()) && returned.getClass() != String.class) {
          processedReturnValue = returned;
        }
      }
      MutationTracingContextHolder.endMethodCall(
          pendingInvocation.receiver(),
          pendingInvocation.invocationId(),
          processedReturnValue
      );
    } catch (Exception e) {
      e.printStackTrace();
      Runtime.getRuntime().halt(2);
      throw e;
    }
  }

  public record PendingInvocation(
      int receiver,
      int invocationId
  ) {

  }
}
