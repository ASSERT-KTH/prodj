package se.kth.castor.pankti.codemonkey.construction.solving;

import java.util.Optional;

public interface MutationStrategy {

  Result register(SolvingState state, Object instance);

  boolean isStatic();

  record Result(Optional<SolvingState> newState, boolean consideredDynamicMutation) {

    public static Result failedDynamic() {
      return new Result(Optional.empty(), true);
    }

    public static Result failedStatic() {
      return new Result(Optional.empty(), false);
    }

    public static Result successDynamic(SolvingState state) {
      return new Result(Optional.of(state), true);
    }

    public static Result successStatic(SolvingState state) {
      return new Result(Optional.of(state), false);
    }
  }

}
