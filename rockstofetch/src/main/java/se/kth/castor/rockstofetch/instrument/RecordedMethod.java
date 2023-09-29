package se.kth.castor.rockstofetch.instrument;

import static se.kth.castor.rockstofetch.util.Classes.className;

import se.kth.castor.rockstofetch.util.Classes;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public record RecordedMethod(
    String declaringClassName,
    String methodName,
    List<String> parameterTypes
) {

  public static RecordedMethod fromReflectMethod(Method method) {
    return new RecordedMethod(
        method.getDeclaringClass().getName(),
        method.getName(),
        Arrays.stream(method.getParameters()).map(it -> Classes.className(it.getType())).toList()
    );
  }

  public static RecordedMethod fromType(String declaringClass, String name, MethodTypeDesc type) {
    return new RecordedMethod(
        declaringClass,
        name,
        type.parameterList().stream().map(Classes::className).toList()
    );
  }

  public String fqnWithSignature() {
    return declaringClassName() + "#" + signature();
  }

  public String signature() {
    return methodName() + "(" + String.join(",", parameterTypes()) + ")";
  }

}
