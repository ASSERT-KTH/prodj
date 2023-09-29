package se.kth.castor.rockstofetch.extract;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import se.kth.castor.rockstofetch.util.Spoons;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModule;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeInformation;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.CtScanner;
import spoon.reflect.visitor.filter.TypeFilter;

public class MutationChainExtractor {

  private final Set<CtTypeReference<?>> toTrace;
  private final Set<String> seenReferences;
  private final Set<String> seenTypes;
  private final Cache<String, Set<String>> supertypeCache;
  private final Set<CtType<?>> abstractTypes;

  public MutationChainExtractor() {
    this.toTrace = new HashSet<>();
    this.seenReferences = new HashSet<>();
    this.seenTypes = new HashSet<>();
    this.abstractTypes = new HashSet<>();
    this.supertypeCache = CacheBuilder.newBuilder().build();
  }

  public void trace(CtType<?> type) {
    type.accept(new Scanner());
  }

  public void addInterfaceImplementations(Factory factory) {
    InheritanceGraph inheritanceGraph = new InheritanceGraph();
    abstractTypes.forEach(inheritanceGraph::addType);
    Set<CtType<?>> interestingSuperTypes = Set.copyOf(abstractTypes);

    for (CtModule module : factory.getModel().getAllModules()) {
      List<CtClass<?>> types = module.getRootPackage().getElements(new TypeFilter<>(CtClass.class));
      for (CtClass<?> type : types) {
        inheritanceGraph.addType(type);
        if (inheritanceGraph.hasSupertype(type, interestingSuperTypes)) {
          trace(type);
        }
      }
    }
  }

  public Set<CtTypeReference<?>> getToTrace() {
    return toTrace;
  }

  private class Scanner extends CtScanner {

    private Set<String> superTypes;

    @Override
    public <T> void visitCtClass(CtClass<T> ctClass) {
      if (visitType(ctClass)) {
        if (ctClass.isAbstract()) {
          abstractTypes.add(ctClass);
        }
        super.visitCtClass(ctClass);
      }
    }

    @Override
    public <T extends Enum<?>> void visitCtEnum(CtEnum<T> ctEnum) {
      if (visitType(ctEnum)) {
        super.visitCtEnum(ctEnum);
      }
    }

    @Override
    public <T> void visitCtInterface(CtInterface<T> intrface) {
      if (visitType(intrface)) {
        abstractTypes.add(intrface);
        super.visitCtInterface(intrface);
      }
    }

    @Override
    public void visitCtRecord(CtRecord recordType) {
      if (visitType(recordType)) {
        super.visitCtRecord(recordType);
      }
    }

    private boolean visitType(CtType<?> type) {
      try {
        if (!seenTypes.add(type.getQualifiedName())) {
          return false;
        }
        addTypeRef(type);
        superTypes = supertypeCache.get(
            type.getQualifiedName(),
            () -> SpoonUtil.getSuperclasses(type)
                .stream()
                .map(CtTypeInformation::getQualifiedName)
                .collect(Collectors.toSet())
        );
        return true;
      } catch (ExecutionException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public <T> void visitCtConstructor(CtConstructor<T> c) {
      visitExecutable(c);
      super.visitCtConstructor(c);
    }

    @Override
    public <T> void visitCtFieldWrite(CtFieldWrite<T> fieldWrite) {
      super.visitCtFieldWrite(fieldWrite);
      if (!(fieldWrite.getParent(CtExecutable.class) instanceof CtMethod<?> method)) {
        return;
      }
      CtField<T> field = fieldWrite.getVariable().getFieldDeclaration();
      if (field == null || !superTypes.contains(field.getDeclaringType().getQualifiedName())) {
        return;
      }

      visitExecutable(method);
    }

    @Override
    public <T> void visitCtThisAccess(CtThisAccess<T> thisAccess) {
      super.visitCtThisAccess(thisAccess);
      if (thisAccess.getParent() instanceof CtInvocation<?> invocation) {
        trace(invocation.getExecutable().getDeclaringType().getTypeDeclaration());
      }
    }

    private void visitExecutable(CtExecutable<?> executable) {
      visitType(executable.getType());
      executable.getParameters().forEach(it -> visitType(it.getType()));
    }

    private void visitType(CtTypeReference<?> reference) {
      if (reference instanceof CtArrayTypeReference<?> arrayRef) {
        reference = arrayRef.getArrayType();
      }
      if (Spoons.isBasicallyPrimitive(reference)) {
        return;
      }
      if (!seenReferences.add(reference.getQualifiedName())) {
        return;
      }
      CtType<?> typeDeclaration;
      if (reference instanceof CtArrayTypeReference<?> arrayReference) {
        typeDeclaration = arrayReference.getArrayType().getTypeDeclaration();
      } else {
        typeDeclaration = reference.getTypeDeclaration();
      }
      if (typeDeclaration instanceof CtTypeParameter typeParameter) {
        CtType<?> erased = typeParameter.getTypeErasure().getTypeDeclaration();
        if (erased != null) {
          addTypeRef(erased);
          erased.accept(new Scanner());
        }
      } else if (typeDeclaration != null) {
        addTypeRef(typeDeclaration);
        typeDeclaration.accept(new Scanner());
      }
    }

    private void addTypeRef(CtType<?> type) {
      if (!type.getSimpleName().isBlank() && !type.isShadow()) {
        toTrace.add(type.getReference());
      }
    }

  }

}
