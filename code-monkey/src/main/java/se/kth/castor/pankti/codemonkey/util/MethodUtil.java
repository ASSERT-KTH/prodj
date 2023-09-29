package se.kth.castor.pankti.codemonkey.util;

import java.util.Objects;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.visitor.filter.TypeFilter;

public class MethodUtil {

  public static boolean returnsArgumentUnchanged(CtMethod<?> method, CtParameter<?> param) {
    if (method.getDeclaringType().getQualifiedName().equals(Objects.class.getName())) {
      return method.getSimpleName().startsWith("require");
    }
    if (!method.getType().equals(param.getType())) {
      return false;
    }

    CtBlock<?> body = method.getBody();
    if (body == null) {
      return false;
    }
    // FIXME: This would need a proper dataflow implementation...
    for (CtReturn<?> ctReturn : body.getElements(new TypeFilter<>(CtReturn.class))) {
      if (!(ctReturn.getReturnedExpression() instanceof CtVariableRead<?> read)) {
        return false;
      }
      if (!(read.getVariable().getDeclaration() instanceof CtParameter<?> returnedParam)) {
        return false;
      }
      if (!returnedParam.equals(param)) {
        return false;
      }
    }

    return true;
  }
}
