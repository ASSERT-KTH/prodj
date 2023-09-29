package se.kth.castor.pankti.codemonkey.util;

import spoon.reflect.declaration.CtClass;

public class SerializationFailedException extends Exception {

  private final CtClass<?> type;

  public SerializationFailedException(CtClass<?> type) {
    super("Serialization failed for " + type.getQualifiedName());
    this.type = type;
  }

  public SerializationFailedException(CtClass<?> type, Throwable cause) {
    super("Serialization failed impossible for " + type.getQualifiedName(), cause);
    this.type = type;
  }

  public CtClass<?> getType() {
    return type;
  }
}
