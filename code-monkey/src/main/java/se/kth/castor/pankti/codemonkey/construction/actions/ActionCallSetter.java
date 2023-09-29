package se.kth.castor.pankti.codemonkey.construction.actions;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import se.kth.castor.pankti.codemonkey.construction.solving.Costs;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;

public record ActionCallSetter(
    CtMethod<?> setter,
    Map<CtParameter<?>, List<CtField<?>>> parameters
) implements Action {

  @Override
  public Collection<CtField<?>> handledFields() {
    return parameters.values().stream().flatMap(Collection::stream).toList();
  }

  @Override
  public boolean constructsInstance() {
    return false;
  }

  @Override
  public boolean needsInstance() {
    return true;
  }

  @Override
  public int cost() {
    return Costs.CALL_SETTER;
  }

  @Override
  public String toString() {
    return """
        ActionCallSetter{
         ## SETTER
           %s
         ## FIELD
           %s
        }""".formatted(setter.getSignature(), handledFields());
  }
}
