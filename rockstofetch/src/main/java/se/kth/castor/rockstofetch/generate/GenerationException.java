package se.kth.castor.rockstofetch.generate;

public class GenerationException extends RuntimeException {

  private final Type type;

  public GenerationException(Type type) {
    super(type.name());
    this.type = type;
  }
  public GenerationException(Type type, String context) {
    super(type.name() + ": " + context);
    this.type = type;
  }

  public Type getType() {
    return type;
  }

  public enum Type {
    REFERENCED_OBJECT_NOT_FOUND,
    UNKNOWN_VALUE,
    SNIPPET_EMPTY,
  }

}
