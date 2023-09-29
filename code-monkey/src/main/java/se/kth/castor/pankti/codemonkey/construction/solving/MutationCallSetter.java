package se.kth.castor.pankti.codemonkey.construction.solving;

import static se.kth.castor.pankti.codemonkey.util.SpoonUtil.isAccessible;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallSetter;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil.DirectFieldParameterWrite;
import se.kth.castor.pankti.codemonkey.util.SpoonUtil.DirectFieldParameterWriteMethod;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

public class MutationCallSetter implements MutationStrategy {

  @Override
  public boolean isStatic() {
    return true;
  }

  @Override
  public Result register(SolvingState state, Object instance) {
    SolvingState current = state;
    boolean foundAction = false;

    List<CtClass<?>> superTypes = SpoonUtil.getSuperclasses(state.type());
    Set<CtTypeReference<?>> superTypeReferences = superTypes.stream()
        .map(CtType::getReference)
        .collect(Collectors.toSet());

    Map<CtMethod<?>, List<DirectFieldParameterWrite>> writes = superTypes
        .stream()
        .flatMap(it -> it.getElements(new TypeFilter<>(CtFieldWrite.class)).stream())
        .flatMap(it -> SpoonUtil.getFieldParameterAssignment(it, superTypeReferences).stream())
        .flatMap(it -> it.asMethodWrite().stream())
        .filter(it -> isAccessible(it.executableWithWrite()))
        .collect(Collectors.groupingBy(
            DirectFieldParameterWriteMethod::executableWithWrite,
            Collectors.toList()
        ));

    for (List<DirectFieldParameterWrite> write : writes.values()) {
      Map<CtParameter<?>, List<CtField<?>>> parameters = write.stream()
          .collect(Collectors.groupingBy(
              DirectFieldParameterWrite::readParameter,
              Collectors.mapping(DirectFieldParameterWrite::writtenField, Collectors.toList())
          ));
      // This method call requires other parameters as well
      if (parameters.size() != write.get(0).executableWithWrite().getParameters().size()) {
        continue;
      }
      current = current.withAction(
          new ActionCallSetter((CtMethod<?>) write.get(0).executableWithWrite(), parameters)
      );
      foundAction = true;
    }

    return foundAction ? Result.successStatic(current) : Result.failedStatic();
  }
}
