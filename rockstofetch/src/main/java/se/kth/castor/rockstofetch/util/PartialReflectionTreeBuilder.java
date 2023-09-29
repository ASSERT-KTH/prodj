package se.kth.castor.rockstofetch.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.path.CtRole;
import spoon.reflect.reference.CtTypeReference;
import spoon.support.visitor.java.JavaReflectionTreeBuilder;
import spoon.support.visitor.java.internal.RuntimeBuilderContext;
import spoon.support.visitor.java.reflect.RtMethod;

public class PartialReflectionTreeBuilder extends JavaReflectionTreeBuilder {

  private final Factory factory;

  public PartialReflectionTreeBuilder(Factory factory, Factory shadowFactory) {
    super(shadowFactory);
    this.factory = factory;
  }

  public CtExecutable<?> asCtMethod(Executable executable) {
    var holder = new Object() {
      CtExecutable<?> result;
    };
    Class<?> declaringClass = executable.getDeclaringClass();

    enter(new RuntimeBuilderContext() {
      private final Map<String, CtTypeParameter> mapTypeParameters = new HashMap<>();

      @Override
      public void addPackage(CtPackage ctPackage) {

      }

      @Override
      public void addType(CtType<?> aType) {

      }

      @Override
      public void addAnnotation(CtAnnotation<Annotation> ctAnnotation) {

      }

      @Override
      public void addConstructor(CtConstructor<?> ctConstructor) {
        addExecutable(ctConstructor);
      }

      @Override
      public void addMethod(CtMethod<?> ctMethod) {
        addExecutable(ctMethod);
      }

      private void addExecutable(CtExecutable<?> ctExecutable) {
        if (holder.result != null) {
          throw new IllegalStateException(
              "Found two executables: " + holder.result + " and now " + ctExecutable
          );
        }
        holder.result = ctExecutable;
      }

      @Override
      public void addField(CtField<?> ctField) {

      }

      @Override
      public void addEnumValue(CtEnumValue<?> ctEnumValue) {

      }

      @Override
      public void addParameter(CtParameter ctParameter) {

      }

      @Override
      public void addTypeReference(CtRole role, CtTypeReference<?> ctTypeReference) {

      }

      @Override
      public void addFormalType(CtTypeParameter parameterRef) {
        this.mapTypeParameters.put(parameterRef.getSimpleName(), parameterRef);
      }

      @Override
      public void addRecordComponent(CtRecordComponent ctRecordComponent) {

      }

      @Override
      public CtTypeParameter getTypeParameter(GenericDeclaration genericDeclaration,
          String string) {
        return declaringClass == genericDeclaration ? this.mapTypeParameters.get(string) : null;
      }
    });

    if (executable instanceof Method method) {
      visitMethod(RtMethod.create(method));
    } else if (executable instanceof Constructor<?> constructor) {
      visitConstructor(constructor);
    } else {
      throw new IllegalArgumentException("Unknown executable type: " + executable);
    }
    exit();

    Spoons.changeFactory(factory, holder.result);

    return holder.result;
  }

}
