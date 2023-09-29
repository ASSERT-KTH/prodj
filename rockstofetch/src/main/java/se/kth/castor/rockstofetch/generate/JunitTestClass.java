package se.kth.castor.rockstofetch.generate;

import se.kth.castor.rockstofetch.generate.GenerationContext.MethodCache;
import java.util.Objects;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;

public class JunitTestClass {

  private final CtClass<?> testClass;
  private final MethodCache methodCache;

  public JunitTestClass(Factory factory, String name) {
    this.testClass = factory.createClass(name);
    this.methodCache = new MethodCache();
  }

  public void addMethod(CtMethod<?> method) {
    testClass.addMethod(method);
  }

  public String serialize() {
    return testClass.toStringWithImports();
  }

  public MethodCache getMethodCache() {
    return methodCache;
  }

  public boolean isEmpty() {
    return testClass.getMethods().isEmpty();
  }

  public void finalizeMethodCache() {
    methodCache.getMethods().forEach(this::addMethod);
    methodCache.clear();
  }

  public String getQualifiedName() {
    return testClass.getQualifiedName();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    JunitTestClass that = (JunitTestClass) o;
    return Objects.equals(testClass.getQualifiedName(), that.testClass.getQualifiedName());
  }

  @Override
  public int hashCode() {
    return Objects.hash(testClass.getQualifiedName());
  }
}
