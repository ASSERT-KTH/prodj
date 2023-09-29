package se.kth.castor.pankti.codemonkey.construction.solving;

import static se.kth.castor.pankti.codemonkey.util.SpoonUtil.isAccessible;

import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallConstructor;
import se.kth.castor.pankti.codemonkey.util.ClassUtil.ConstructorMapping;
import spoon.reflect.declaration.CtConstructor;

public class MutationCallDefaultConstructor implements MutationStrategy {

  @Override
  public boolean isStatic() {
    return true;
  }

  @Override
  public Result register(SolvingState state, Object instance) {
    CtConstructor<?> defaultConstructor = state.type().getConstructor();
    if (defaultConstructor == null || !isAccessible(defaultConstructor)) {
      return Result.failedStatic();
    }

    return Result.successStatic(state.withAction(
        new ActionCallConstructor(
            defaultConstructor,
            new ConstructorMapping(defaultConstructor)
        )
    ));
  }
}
