package se.kth.castor.rockstofetch.util;

import java.lang.constant.ClassDesc;
import se.kth.castor.pankti.codemonkey.util.ClassUtil;

public class Classes {

  public static Class<?> getClassFromString(ClassLoader contextClassLoader, String className) {
    Class<?> primitiveClass = ClassUtil.getPrimitiveClass(className);
    if (primitiveClass != null) {
      return primitiveClass;
    }

    try {
      return Class.forName(className, true, contextClassLoader);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public static String className(Class<?> type) {
    String canonicalName = type.getCanonicalName();
    if (canonicalName != null) {
      // Ensure `$` is used as separator, even if the canonical name actually contains a `.`
      if (type.getDeclaringClass() != null) {
        return className(type.getDeclaringClass()) + "$" + type.getSimpleName();
      }
      // and fix the same thing for arrays if inner types...
      if (type.isArray()) {
        return className(type.getComponentType()) + "[]";
      }
      return canonicalName;
    }
    return type.getName();
  }

  public static String className(ClassDesc type) {
    if (type.isPrimitive()) {
      return type.displayName();
    }
    // packageName of arrays is ""
    ClassDesc typeForPackage = type;
    while (typeForPackage.componentType() != null) {
      typeForPackage = typeForPackage.componentType();
    }
    if (!typeForPackage.packageName().isEmpty()) {
      return typeForPackage.packageName() + "." + type.displayName();
    }
    return type.displayName();
  }
}
