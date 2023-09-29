package se.kth.castor.pankti.codemonkey.construction.actions;

import java.util.Collection;
import se.kth.castor.pankti.codemonkey.construction.solving.Costs;
import spoon.reflect.declaration.CtField;

public record ActionObjectReference(
    Collection<CtField<?>> handledFields,
    int id,
    long timestamp
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
    return Costs.USE_OBJECT_REFERENCE;
  }

}
