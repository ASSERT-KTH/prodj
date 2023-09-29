package se.kth.castor.pankti.codemonkey.construction.actions;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.construction.solving.Costs;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtNamedElement;
import spoon.reflect.declaration.CtParameter;

public record ActionCallFactoryMethod(
    CtMethod<?> method,
    // TODO: Might not want a field there but another value (e.g. a temporary)
    Map<CtParameter<?>, List<CtField<?>>> parameters
) implements Action {

  public ActionCallFactoryMethod {
    parameters = parameters.entrySet().stream()
        .map(entry -> Map.entry(
            entry.getKey(),
            entry.getValue()
                .stream()
                .sorted(Comparator.comparing(CtNamedElement::getSimpleName))
                .toList()
        ))
        .collect(Collectors.toMap(
            Entry::getKey,
            Entry::getValue
        ));
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
    return Costs.CALL_FACTORY_METHOD;
  }

  @Override
  public String toString() {
    String collect = parameters.entrySet()
        .stream()
        .map(Object::toString)
        .collect(Collectors.joining("\n"));
    String template = """
        ActionCallFactoryMethod{
         ## METHOD
           %s
         ## PARAMETERS
        %s
        }""";
    return template
        .formatted(
            method.getSignature(),
            collect
                .indent(3)
                .stripTrailing()
        );
  }
}
