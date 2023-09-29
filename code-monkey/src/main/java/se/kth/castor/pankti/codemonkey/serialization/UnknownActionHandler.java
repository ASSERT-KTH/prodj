package se.kth.castor.pankti.codemonkey.serialization;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import se.kth.castor.pankti.codemonkey.construction.actions.Action;
import spoon.reflect.code.CtStatement;
import spoon.reflect.reference.CtTypeReference;

public interface UnknownActionHandler {

  CtStatement handleAction(
      Action action,
      CtTypeReference<?> assignedType,
      String name,
      Object object
  );

  static UnknownActionHandler fail() {
    return (action, type, name, value) -> {
      throw new IllegalArgumentException(
          "Did not expect action " + action + ". Was invoked for " + value
      );
    };
  }

  class SeparatingActions implements UnknownActionHandler {

    private final Map<Class<? extends Action>, UnknownActionHandler> handlers;

    public SeparatingActions() {
      this.handlers = new ConcurrentHashMap<>();
    }

    public SeparatingActions addHandler(
        Class<? extends Action> action,
        UnknownActionHandler handler
    ) {
      handlers.put(action, handler);
      return this;
    }

    @Override
    public CtStatement handleAction(
        Action action, CtTypeReference<?> assignedType, String name, Object object
    ) {
      UnknownActionHandler handler = handlers.get(action.getClass());

      return Objects.requireNonNullElseGet(handler, UnknownActionHandler::fail)
          .handleAction(action, assignedType, name, object);
    }
  }
}
