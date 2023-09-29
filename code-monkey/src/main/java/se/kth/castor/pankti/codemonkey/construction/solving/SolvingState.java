package se.kth.castor.pankti.codemonkey.construction.solving;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.construction.actions.Action;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;
import spoon.reflect.reference.CtFieldReference;

public record SolvingState(
    Set<CtField<?>> fields,
    List<Action> actions,
    CtClass<?> type
) {

  private static final Map<String, Set<CtField<?>>> FIELD_CACHE = new ConcurrentHashMap<>();

  public SolvingState {
    fields = Set.copyOf(fields);
    actions = List.copyOf(actions);
  }

  public SolvingState withAction(Action action) {
    List<Action> newActions = new ArrayList<>(actions());
    newActions.add(action);

    return new SolvingState(
        fields,
        newActions,
        type
    );
  }

  @Override
  public String toString() {
    return """
        SolvingState{
         ## TYPE
           %s
         ## ACTIONS
        %s
        }
        """
        .formatted(
            type().getQualifiedName(),
            actions().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"))
                .indent(3)
                .stripTrailing()
        );
  }

  public static SolvingState constructType(CtClass<?> type) {
    Set<CtField<?>> fieldsToSolve = FIELD_CACHE.computeIfAbsent(
        type.getQualifiedName(),
        name -> {
          Set<CtField<?>> fields = type.getAllFields()
              .stream()
              .map(CtFieldReference::getFieldDeclaration)
              .filter(Objects::nonNull)
              .filter(it -> !(it instanceof CtEnumValue<?>))
              .filter(it -> !it.isStatic())
              .collect(Collectors.toCollection(HashSet::new));

          if (type.isEnum()) {
            type.getFactory().Class().get(Enum.class).getFields().forEach(fields::remove);
          }

          return Set.copyOf(fields);
        }
    );

    return new SolvingState(
        fieldsToSolve,
        List.of(),
        type
    );
  }
}
