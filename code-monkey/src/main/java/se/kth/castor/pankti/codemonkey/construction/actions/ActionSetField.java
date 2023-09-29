package se.kth.castor.pankti.codemonkey.construction.actions;

import java.util.Collection;
import java.util.List;
import se.kth.castor.pankti.codemonkey.construction.solving.Costs;
import spoon.reflect.declaration.CtField;

public record ActionSetField(CtField<?> field) implements Action {

  @Override
  public Collection<CtField<?>> handledFields() {
    return List.of(field);
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
    return Costs.ASSIGN_FIELD;
  }

  @Override
  public String toString() {
    return """
        ActionSetField{
         ## FIELD
           %s
        }""".formatted(field);
  }
}
