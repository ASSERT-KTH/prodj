package se.kth.castor.rockstofetch.instrument.aspects;

import se.kth.castor.rockstofetch.instrument.aspects.MutatorCallTraceFactory.Nop;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.MemberSubstitution.Substitution.Chain.Step;
import net.bytebuddy.asm.MemberSubstitution.Substitution.Chain.Step.Factory;
import net.bytebuddy.asm.MemberSubstitution.Substitution.Chain.Step.OfOriginalExpression;
import net.bytebuddy.description.ByteCodeElement.Member;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription.ForLoadedMethod;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeDescription.Generic.OfNonGenericType.ForLoadedType;
import net.bytebuddy.description.type.TypeList.Generic;
import net.bytebuddy.implementation.bytecode.Removal;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.StackManipulation.Compound;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;
import net.bytebuddy.implementation.bytecode.assign.primitive.PrimitiveTypeAwareAssigner;
import net.bytebuddy.implementation.bytecode.constant.NullConstant;
import net.bytebuddy.implementation.bytecode.member.FieldAccess;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaConstant.MethodHandle;

public class SetFieldTraceFactory {

  private static final Method TRACE_METHOD;

  static {
    try {
      TRACE_METHOD = MutationTracingSubstitutionPointcut.class.getMethod(
          "onFieldWrite", Object.class, Object.class, Object.class
      );
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Did not find mutation trace method", e);
    }
  }

  public static List<Factory> getChain(ElementMatcher<TypeDescription> mutationTraceTypes) {
    return List.of(
        (assigner, typing, instrumentedType, instrumentedMethod) -> {
          // FIXME: This should take the matcher from the agent
          boolean shouldTrace = mutationTraceTypes.matches(instrumentedType);
          if (!shouldTrace) {
            System.out.println(
                "\033[2mSkipped write instrumentation."
                + " assigner = " + assigner
                + ", typing = " + typing
                + ", instrumentedType = " + instrumentedType
                + ", instrumentedMethod = " + instrumentedMethod + "\033[0m"
            );
            return new Nop();
          }
          System.out.println(
              "\033[32mInstrumented (field ) "
              + instrumentedType.getTypeName() + "#" + instrumentedMethod.getName()
              + "\033[0m"
          );
          return new TraceMethodStep(assigner, typing);
        },
        OfOriginalExpression.INSTANCE
    );
  }

  private record TraceMethodStep(Assigner assigner, Typing typing) implements Step {

    @Override
    public Resolution resolve(
        TypeDescription receiver, Member original, Generic parameters,
        TypeDescription.Generic result, MethodHandle methodHandle,
        StackManipulation stackManipulation, TypeDescription.Generic current,
        Map<Integer, Integer> offsets, int freeOffset
    ) {
      FieldDescription fieldDescription = (FieldDescription) original;
      List<StackManipulation> manipulations = new ArrayList<>();

      manipulations.add(Removal.of(current));

      if (fieldDescription.isStatic()) {
        manipulations.add(MethodVariableAccess.of(parameters.get(0)).loadFrom(offsets.get(0)));
        manipulations.add(new PrimitiveTypeAwareAssigner(assigner).assign(
            fieldDescription.getType(),
            ForLoadedType.of(Object.class),
            typing
        ));
        manipulations.add(NullConstant.INSTANCE);
      } else {
        manipulations.add(MethodVariableAccess.of(parameters.get(0)).loadFrom(offsets.get(0)));
        manipulations.add(MethodVariableAccess.of(parameters.get(1)).loadFrom(offsets.get(1)));
        manipulations.add(new PrimitiveTypeAwareAssigner(assigner).assign(
            fieldDescription.getType(),
            ForLoadedType.of(Object.class),
            typing
        ));
      }

      if (!fieldDescription.isStatic()) {
        manipulations.add(MethodVariableAccess.of(parameters.get(0)).loadFrom(offsets.get(0)));
      }
      manipulations.add(FieldAccess.forField(fieldDescription).read());
      manipulations.add(new PrimitiveTypeAwareAssigner(assigner).assign(
          fieldDescription.getType(),
          ForLoadedType.of(Object.class),
          typing
      ));

      manipulations.add(MethodInvocation.invoke(new ForLoadedMethod(TRACE_METHOD)));

      return new Simple(new Compound(manipulations), new ForLoadedType(void.class));
    }
  }

}
