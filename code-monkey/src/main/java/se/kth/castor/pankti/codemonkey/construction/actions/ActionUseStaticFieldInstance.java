package se.kth.castor.pankti.codemonkey.construction.actions;

import java.util.Collection;
import se.kth.castor.pankti.codemonkey.construction.solving.Costs;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;

public record ActionUseStaticFieldInstance(
    CtType<?> type,
    Collection<CtField<?>> handledFields,
    CtField<?> field
) implements Action {

  @Override
  public boolean constructsInstance() {
    return true;
  }

  @Override
  public boolean needsInstance() {
    return false;
  }

  @Override
  public int cost() {
    return Costs.USE_STATIC_FIELD;
  }

  @Override
  public String toString() {
    return """
        ActionUseStaticFieldInstance{
         ## FIELD
           %s
        }""".formatted(field);
  }
}
