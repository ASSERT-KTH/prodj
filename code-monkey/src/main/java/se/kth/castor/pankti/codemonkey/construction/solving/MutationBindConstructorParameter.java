package se.kth.castor.pankti.codemonkey.construction.solving;

import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallConstructor;
import se.kth.castor.pankti.codemonkey.util.ClassUtil;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil;

public class MutationBindConstructorParameter implements MutationStrategy {

  @Override
  public boolean isStatic() {
    return true;
  }

  @Override
  public Result register(SolvingState state, Object instance) {
    var constructors = ClassUtil.getConstructorFieldAssignments(
        state.type(),
        SpoonUtil::isAccessible
    );

    if (constructors.isEmpty()) {
      return Result.failedStatic();
    }

    SolvingState current = state;
    for (var entry : constructors.entrySet()) {
      current = current.withAction(new ActionCallConstructor(entry.getKey(), entry.getValue()));
    }

    return Result.successStatic(current);
  }

}
