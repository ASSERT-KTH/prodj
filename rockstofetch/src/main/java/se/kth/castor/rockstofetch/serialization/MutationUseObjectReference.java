package se.kth.castor.rockstofetch.serialization;

import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionObjectReference;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationStrategy;
import se.kth.castor.pankti.codemonkey.construction.solving.SolvingState;
import spoon.support.adaption.TypeAdaptor;

public class MutationUseObjectReference implements MutationStrategy {

  private final Set<String> mutationTraceTypes;
  private final Set<String> nonMutationTraceTypes;

  public MutationUseObjectReference(Set<String> mutationTraceTypes) {
    this.mutationTraceTypes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    this.mutationTraceTypes.addAll(mutationTraceTypes);

    this.nonMutationTraceTypes = new HashSet<>();
  }

  @Override
  public boolean isStatic() {
    return false;
  }

  @Override
  public Result register(SolvingState state, Object instance) {
    String typeName = state.type().getQualifiedName();

    if (mutationTraceTypes.contains(typeName)) {
      return resultFromLookup(state, instance);
    }
    if (nonMutationTraceTypes.contains(typeName)) {
      return Result.failedStatic();
    }

    for (String type : mutationTraceTypes) {
      if (TypeAdaptor.isSubtype(state.type(), state.type().getFactory().createReference(type))) {
        mutationTraceTypes.add(typeName);
        return resultFromLookup(state, instance);
      }
    }

    nonMutationTraceTypes.add(typeName);

    return Result.failedStatic();
  }

  private static Result resultFromLookup(SolvingState state, Object instance) {
    long timestamp = MutationTracingContextHolder.getCurrentTime();
    Integer objectId = MutationTracingContextHolder.getObjectId(instance);
    if (objectId == null) {
      return Result.failedDynamic();
    }
    return Result.successDynamic(
        state.withAction(new ActionObjectReference(state.fields(), objectId, timestamp))
    );
  }

}
