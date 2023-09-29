import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ReflectiveDeepEquals {

  public static boolean reflectiveDeepEquals(Object a, Object b) {
    // if they are already equal, fine
    if (Objects.equals(a, b)) {
      return true;
    }
    // equals with null is always false
    if (a == null || b == null) {
      return false;
    }
    // were not equal before, so they are not strictly equal. Maybe they are close enough?
    if (a instanceof Number && b instanceof Number) {
      return Math.abs(((Number) a).doubleValue() - ((Number) b).doubleValue()) < 1e-4;
    }
    Class<?> aClass = a.getClass();
    Class<?> bClass = b.getClass();

    if (aClass.isArray() && bClass.isArray()) {
      if (Array.getLength(a) != Array.getLength(b)) {
        return false;
      }
      for (int i = 0; i < Array.getLength(a); i++) {
        if (!reflectiveDeepEquals(Array.get(a, i), Array.get(b, i))) {
          return false;
        }
      }
      return true;
    }

    // no inheritance relationship => not equal
    if (!aClass.isAssignableFrom(bClass) && !bClass.isAssignableFrom(aClass)) {
      return false;
    }

    if (a instanceof List<?> && b instanceof List<?>) {
      List<?> aList = ((List<?>) a);
      List<?> bList = ((List<?>) b);
      if (aList.size() != bList.size()) {
        return false;
      }

      for (int i = 0; i < aList.size(); i++) {
        if (!reflectiveDeepEquals(aList.get(i), bList.get(i))) {
          return false;
        }
      }

      return true;
    }

    if (a instanceof Collection<?> && b instanceof Collection<?>) {
      Collection<?> aColl = ((Collection<?>) a);
      Collection<?> bColl = ((Collection<?>) b);
      if (aColl.size() != bColl.size()) {
        return false;
      }

      for (Object aO : aColl) {
        boolean foundMatch = false;
        for (Object bO : bColl) {
          if (reflectiveDeepEquals(aO, bO)) {
            foundMatch = true;
            break;
          }
        }
        if (!foundMatch) {
          return false;
        }
      }

      return true;
    }

    if (a instanceof Map<?, ?> && b instanceof Map<?, ?>) {
      Map<?, ?> aMap = ((Map<?, ?>) a);
      Map<?, ?> bMap = ((Map<?, ?>) b);
      if (aMap.size() != bMap.size()) {
        return false;
      }

      for (Map.Entry<?, ?> aEntry : aMap.entrySet()) {
        if (!reflectiveDeepEquals(aEntry.getValue(), bMap.get(aEntry.getKey()))) {
          return false;
        }
      }

      return true;
    }

    // no equals methods, we need to recurse in the fields
    List<Field> aFields = getAllFieldsInHierarchy(aClass);
    List<Field> bFields = getAllFieldsInHierarchy(bClass);

    // if they have different fields, that's not going to work out
    if (!aFields.equals(bFields)) {
      return false;
    }

    try {
      for (Field field : aFields) {
        if (!field.trySetAccessible()) {
          // sad story, but the java module system probably got in the way
          return false;
        }
        if (!reflectiveDeepEquals(field.get(a), field.get(b))) {
          return false;
        }
      }
    } catch (IllegalAccessException e) {
      throw new AssertionError("Access denied after setAccessible succeeded", e);
    }

    return true;
  }

  private static List<Field> getAllFieldsInHierarchy(Class<?> root) {
    List<Field> fields = new ArrayList<>();
    Class<?> current = root;
    while (current != null) {
      Collections.addAll(fields, current.getDeclaredFields());
      current = current.getSuperclass();
    }

    return fields.stream()
        .filter(it -> !Modifier.isStatic(it.getModifiers()))
        .collect(Collectors.toList());
  }

}
