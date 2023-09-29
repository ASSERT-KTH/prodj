package se.kth.castor.pankti.codemonkey.construction.actions;

import java.util.Collection;
import se.kth.castor.pankti.codemonkey.construction.solving.Costs;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;

public record ActionUseEnumConstant(CtEnum<?> type) implements Action {

  @Override
  public Collection<CtField<?>> handledFields() {
    return type.getFields().stream().filter(it -> !(it instanceof CtEnumValue<?>)).toList();
  }

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
    return Costs.USE_ENUM_CONSTANT;
  }

  @Override
  public String toString() {
    return """
        ActionUseEnumConstant{
        }""";
  }
}
