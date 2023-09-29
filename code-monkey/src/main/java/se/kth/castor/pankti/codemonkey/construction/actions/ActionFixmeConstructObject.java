package se.kth.castor.pankti.codemonkey.construction.actions;

import java.util.Collection;
import se.kth.castor.pankti.codemonkey.construction.solving.Costs;
import spoon.reflect.declaration.CtField;

public record ActionFixmeConstructObject(Collection<CtField<?>> handledFields) implements Action {

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
    return Costs.FIXME_CONSTRUCT_OBJECT;
  }

  @Override
  public String toString() {
    return """
        ActionUseEnumConstant{
        }""";
  }
}
