package se.kth.castor.pankti.codemonkey.construction.solving;

import java.util.Set;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionFixmeConstructObject;

public class MutationFixmeConstructObject implements MutationStrategy {

  private final Set<String> allowedTypes;

  public MutationFixmeConstructObject(Set<String> allowedTypes) {
    this.allowedTypes = Set.copyOf(allowedTypes);
  }

  @Override
  public boolean isStatic() {
    return true;
  }

  @Override
  public Result register(SolvingState state, Object instance) {
    if (!allowedTypes.contains(state.type().getQualifiedName())) {
      return Result.failedStatic();
    }
    return Result.successStatic(state.withAction(new ActionFixmeConstructObject(state.fields())));
  }
}
