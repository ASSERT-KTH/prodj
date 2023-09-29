package se.kth.castor.pankti.codemonkey.construction.solving;

import static se.kth.castor.pankti.codemonkey.util.SpoonUtil.isAccessible;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionCallFactoryMethod;
import se.kth.castor.pankti.codemonkey.util.ClassUtil;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.adaption.TypeAdaptor;

public class MutationCallSimpleFactoryMethod implements MutationStrategy {

  @Override
  public boolean isStatic() {
    return true;
  }

  @Override
  public Result register(SolvingState state, Object instance) {
    SolvingState current = state;
    boolean foundAction = false;

    var constructors = ClassUtil.getConstructorFieldAssignments(
        state.type(),
        ignored -> true
    );

    List<CtConstructorCall<?>> constructorCalls = state.type()
        .getElements(new TypeFilter<>(CtConstructorCall.class));

    outer:
    for (CtConstructorCall<?> call : constructorCalls) {
      if (!call.getType().equals(state.type().getReference())) {
        continue;
      }
      if (!(call.getExecutable()
          .getExecutableDeclaration() instanceof CtConstructor<?> constructor)) {
        continue;
      }
      if (!constructors.containsKey(constructor)) {
        continue;
      }
      CtMethod<?> factoryMethod = call.getParent(CtMethod.class);
      if (factoryMethod == null || !isAccessible(factoryMethod) || !factoryMethod.isStatic()) {
        continue;
      }
      if (!TypeAdaptor.isSubtype(state.type(), factoryMethod.getType())) {
        continue;
      }

      List<CtParameter<?>> passedFactoryMethodParameters = new ArrayList<>();
      for (CtExpression<?> argument : call.getArguments()) {
        if (!(argument instanceof CtVariableRead<?> variableRead)) {
          // FIXME: Implement indirect passing of factory method parameters to constructor
          continue outer;
        }
        if (!(variableRead.getVariable().getDeclaration() instanceof CtParameter<?> parameter)) {
          // FIXME: Implement indirect passing of factory method parameters to constructor
          continue outer;
        }
        passedFactoryMethodParameters.add(parameter);
      }

      var parameterMap = constructors.get(constructor);

      Map<CtParameter<?>, List<CtField<?>>> factoryMethodParameterMap = new HashMap<>();

      for (int i = 0; i < passedFactoryMethodParameters.size(); i++) {
        CtParameter<?> factoryMethodParam = passedFactoryMethodParameters.get(i);
        CtParameter<?> constructorParameter = constructor.getParameters().get(i);
        Set<CtField<?>> fields = parameterMap.get(constructorParameter);
        if (fields == null) {
          //FIXME: Constructor does not assign all parameters to field
          continue outer;
        }
        factoryMethodParameterMap.put(factoryMethodParam, new ArrayList<>(fields));
      }

      // We do not have enough input values to actually call the factory
      // This can happen if the factory method does not directly, delegate, e.g.
      //   Foo foo(int a) {
      //     Foo f = new Foo();
      //     f.setA(a);
      //     return f;
      //   }
      if (factoryMethodParameterMap.size() != factoryMethod.getParameters().size()) {
        continue;
      }

      current = current.withAction(
          new ActionCallFactoryMethod(factoryMethod, factoryMethodParameterMap)
      );
      foundAction = true;
    }

    return foundAction ? Result.successStatic(current) : Result.failedStatic();
  }
}
