package se.kth.castor.pankti.codemonkey.util;

import spoon.reflect.declaration.CtClass;

public class SolveFailedException extends SerializationFailedException {

  private final Class<?> failingType;

  public SolveFailedException(Class<?> failingType, CtClass<?> type) {
    super(type);
    this.failingType = failingType;
  }

  public SolveFailedException(Class<?> failingType, CtClass<?> type, Throwable cause) {
    super(type, cause);
    this.failingType = failingType;
  }

  public Class<?> getFailingType() {
    return failingType;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
