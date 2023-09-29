package se.kth.castor.pankti.codemonkey.construction.actions;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.construction.solving.Costs;
import se.kth.castor.pankti.codemonkey.util.ClassUtil.ConstructorMapping;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtParameter;

public final class ActionCallConstructor implements Action {

  private final CtConstructor<?> constructor;
  private final Map<CtParameter<?>, List<CtField<?>>> parameters;

  public ActionCallConstructor(
      CtConstructor<?> constructor,
      ConstructorMapping parameters
  ) {
    this.constructor = constructor;
    this.parameters = parameters.toSortedFieldMap();
  }

  @Override
  public Collection<CtField<?>> handledFields() {
    return parameters.values().stream().flatMap(Collection::stream).toList();
  }

  @Override
  public boolean needsInstance() {
    return false;
  }

  @Override
  public boolean constructsInstance() {
    return true;
  }

  @Override
  public int cost() {
    return constructor.getParameters().isEmpty()
        ? Costs.CALL_DEFAULT_CONSTRUCTOR
        : Costs.CALL_CONSTRUCTOR;
  }

  @Override
  public String toString() {
    String collect = parameters.entrySet()
        .stream()
        .map(Object::toString)
        .collect(Collectors.joining("\n"));
    String template = """
        ActionCallConstructor{
         ## CONSTRUCTOR
           %s
         ## PARAMETERS
        %s
        }""";
    return template
        .formatted(
            constructor.getSignature(),
            collect
                .indent(3)
                .stripTrailing()
        );
  }

  public CtConstructor<?> constructor() {
    return constructor;
  }

  public Map<CtParameter<?>, List<CtField<?>>> parameters() {
    return parameters;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj == null || obj.getClass() != this.getClass()) {
      return false;
    }
    var that = (ActionCallConstructor) obj;
    return Objects.equals(this.constructor, that.constructor) &&
           Objects.equals(this.parameters, that.parameters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(constructor, parameters);
  }

}
