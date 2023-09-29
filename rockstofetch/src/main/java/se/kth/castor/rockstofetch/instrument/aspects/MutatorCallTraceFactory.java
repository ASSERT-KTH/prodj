package se.kth.castor.rockstofetch.instrument.aspects;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.MemberSubstitution.Substitution.Chain.Step;
import net.bytebuddy.asm.MemberSubstitution.Substitution.Chain.Step.Factory;
import net.bytebuddy.asm.MemberSubstitution.Substitution.Chain.Step.ForInvocation;
import net.bytebuddy.asm.MemberSubstitution.Substitution.Chain.Step.OfOriginalExpression;
import net.bytebuddy.description.ByteCodeElement.Member;
import net.bytebuddy.description.method.MethodDescription.ForLoadedMethod;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList.Generic;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.StackManipulation.Trivial;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaConstant.MethodHandle;

public class MutatorCallTraceFactory {

  private final static Method TRACE_METHOD;

  static {
    try {
      TRACE_METHOD = MutationTracingSubstitutionPointcut.class.getMethod(
          "onMutatorCalled", Object.class
      );
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  public static List<Factory> getChain(ElementMatcher<TypeDescription> mutationTraceTypes) {
    return List.of(
        (assigner, typing, instrumentedType, instrumentedMethod) -> {
          boolean shouldTrace = mutationTraceTypes.matches(instrumentedType);
          if (!shouldTrace) {
            System.out.println(
                "\033[2mSkipped method call instrumentation."
                + " assigner = " + assigner
                + ", typing = " + typing
                + ", instrumentedType = " + instrumentedType
                + ", instrumentedMethod = " + instrumentedMethod + "\033[0m"
            );
            return new Nop();
          }
          System.out.println(
              "\033[32mInstrumented (invoke) "
              + instrumentedType.getTypeName() + "#" + instrumentedMethod.getName()
              + "\033[0m"
          );
          return new ForInvocation.Factory(
              new ForLoadedMethod(TRACE_METHOD), Map.of(1, 0)
          ).make(assigner, typing, instrumentedType, instrumentedMethod);
        },
        OfOriginalExpression.INSTANCE
    );
  }

  public static class Nop implements Step {

    @Override
    public Resolution resolve(TypeDescription receiver, Member original, Generic parameters,
        TypeDescription.Generic result, MethodHandle methodHandle,
        StackManipulation stackManipulation, TypeDescription.Generic current,
        Map<Integer, Integer> offsets, int freeOffset) {
      return new Simple(Trivial.INSTANCE, current);
    }
  }
}
