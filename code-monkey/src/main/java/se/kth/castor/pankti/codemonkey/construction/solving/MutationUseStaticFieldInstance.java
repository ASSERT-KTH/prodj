package se.kth.castor.pankti.codemonkey.construction.solving;

import com.google.common.collect.MapMaker;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionUseStaticFieldInstance;
import spoon.reflect.declaration.CtField;
import spoon.reflect.factory.Factory;

public class MutationUseStaticFieldInstance implements MutationStrategy {

  private final ConcurrentMap<Class<?>, ConcurrentMap<Class<?>, Collection<Field>>> candidateFieldsCache;

  public MutationUseStaticFieldInstance() {
    this.candidateFieldsCache = new MapMaker().weakKeys().weakValues().makeMap();
  }

  @Override
  public boolean isStatic() {
    return false;
  }

  @Override
  public Result register(SolvingState state, Object instance) {
    if (state.type().isEnum()) {
      return Result.failedStatic();
    }

    Set<Field> candidates = new HashSet<>();
    for (Class<?> potentialClass : getPotentialClasses(state, instance)) {
      Class<?> currentClass = potentialClass;
      while (currentClass != null) {
        candidates.addAll(addCandidatesForClass(currentClass, instance.getClass()));
        currentClass = currentClass.getEnclosingClass();
      }
    }

    if (candidates.isEmpty()) {
      return Result.failedStatic();
    }

    return tryFindInstance(state, instance, candidates);
  }

  protected List<Class<?>> getPotentialClasses(SolvingState state, Object instance) {
    return List.of(instance.getClass());
  }

  private Collection<Field> addCandidatesForClass(
      Class<?> targetClass,
      Class<?> instanceClass
  ) {
    var cache = candidateFieldsCache.computeIfAbsent(
        targetClass,
        ignored -> new MapMaker().weakKeys().weakValues().makeMap()
    );
    return cache.computeIfAbsent(
        instanceClass,
        ignored -> {
          Collection<Field> candidates = new HashSet<>();
          for (Field field : targetClass.getFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) {
              continue;
            }
            if (!field.getType().isAssignableFrom(instanceClass)) {
              continue;
            }
            candidates.add(field);
          }
          return candidates;
        }
    );
  }

  private static Result tryFindInstance(
      SolvingState state,
      Object instance,
      Collection<Field> candidates
  ) {
    Factory factory = state.type().getFactory();
    try {
      SolvingState current = state;
      boolean foundAction = false;

      for (Field candidate : candidates) {
        if (!candidate.canAccess(null)) {
          candidate.setAccessible(true);
        }
        Object value = candidate.get(null);
        // TODO: Or use equals and ignore it in aspects?
        if (value == instance) {
          CtField<?> ctField = factory.Type()
              .get(candidate.getDeclaringClass())
              .getField(candidate.getName());
          current = current.withAction(new ActionUseStaticFieldInstance(
              current.type(), current.fields(), ctField
          ));
          foundAction = true;
        }
      }

      return foundAction ? Result.successDynamic(current) : Result.failedDynamic();
    } catch (InaccessibleObjectException | IllegalAccessException e) {
      // TODO: Better exception. This should only happen with JDK types
//      throw new RuntimeException(e);
      return Result.failedStatic();
    }
  }

}
