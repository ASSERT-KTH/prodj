package se.kth.castor.pankti.codemonkey.construction.solving;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MutationUseStandardCharset extends MutationUseStaticFieldInstance {

  @Override
  public Result register(SolvingState state, Object instance) {
    if (!(instance instanceof Charset)) {
      return Result.failedStatic();
    }
    return super.register(state, instance);
  }

  @Override
  protected List<Class<?>> getPotentialClasses(SolvingState state, Object instance) {
    return List.of(StandardCharsets.class);
  }
}
