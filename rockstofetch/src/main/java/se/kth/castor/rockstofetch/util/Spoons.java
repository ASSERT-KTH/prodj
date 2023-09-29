package se.kth.castor.rockstofetch.util;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.Sets;
import se.kth.castor.rockstofetch.generate.GenerationContext.AssertionType;
import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import spoon.Launcher;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.factory.TypeFactory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.CtScanner;
import spoon.support.compiler.VirtualFile;

public class Spoons {

  private static final Pattern VARIABLE_NAME_PATTERN = Pattern.compile(" ([^ ]+?) = ");
  private static final Pattern VARIABLE_TYPE_PATTERN = Pattern.compile(
      "^(.+?) [^ ]+ = ", Pattern.MULTILINE
  );
  private static final Set<String> KEYWORDS = Set.of(
      "abstract", "continue", "for", "new", "switch", "default", "if", "package", "synchronized",
      "do", "goto", "private", "this", "break", "implements", "protected", "throw", "else",
      "import", "public", "throws", "case", "instanceof", "return", "transient", "catch", "extends",
      "try", "final", "interface", "static", "finally", "volatile", "const", "native", "super",
      "while", "boolean", "char", "byte", "short", "int", "long", "float", "double",
      "false", "true", "null", "strictfp", "assert", "enum"
  );

  public static CtMethod<?> getCtMethod(Factory factory, RecordedMethod method) {
    CtType<?> type = factory.Type().get(method.declaringClassName());
    return getCtMethod(factory, type, method);
  }

  public static CtMethod<?> getCtMethod(Factory factory, CtType<?> type, RecordedMethod method) {
    if (type == null) {
      type = factory.Type()
          .get(Classes.getClassFromString(
              factory.getEnvironment().getInputClassLoader(), method.declaringClassName()
          ));
    }
    for (CtMethod<?> candidate : type.getMethodsByName(method.methodName())) {
      if (parametersMatch(method.parameterTypes(), candidate.getParameters())) {
        return candidate;
      }
    }
    for (CtMethod<?> candidate : type.getAllMethods()) {
      if (!candidate.getSimpleName().equals(method.methodName())) {
        continue;
      }
      if (parametersMatch(method.parameterTypes(), candidate.getParameters())) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("No method found for " + method);
  }

  private static boolean parametersMatch(List<String> expected, List<CtParameter<?>> actual) {
    if (expected.size() != actual.size()) {
      return false;
    }
    for (int i = 0; i < expected.size(); i++) {
      String expectedType = expected.get(i);
      CtTypeReference<?> actualType = actual.get(i).getType();
      if (actualType instanceof CtTypeParameterReference) {
        actualType = actualType.getTypeErasure();
      }
      if (!expectedType.equals(actualType.getQualifiedName())) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@return Sanitizes a fqn so it can be used as a java identifier.}
   *
   * @param fqn the fully qualified name of a method
   */
  public static String sanitizeName(String fqn) {
    return fqn
        .replace(".", "_")
        .replace("#", "__")
        .replace("[]", "arr");
  }

  /**
   * {@return the name of a test for the given method with the given prefix}
   *
   * @param method the method under test
   * @param prefix the prefix for the method (e.g. {@literal "output"} or
   *     {@literal "parameter"})
   */
  public static String testName(RecordedMethod method, String prefix) {
    return sanitizeName(
        "test" + prefix + StringUtils.capitalize(method.methodName())
        + "$" + method.parameterTypes()
            .stream()
            .map(Spoons::unqualify)
            .map(it -> it.replace("[]", "__"))
            .collect(Collectors.joining("_"))
    );
  }

  private static String unqualify(String type) {
    if (!type.contains(".")) {
      return type;
    }
    return type.substring(type.lastIndexOf('.') + 1);
  }

  /**
   * {@return true if a type is primitive, a primitive wrapper or String}
   *
   * @param type the type
   */
  public static boolean isBasicallyPrimitive(CtTypeReference<?> type) {
    return type.isPrimitive() || type.unbox().isPrimitive()
           || type.equals(type.getFactory().Type().STRING);
  }

  public static boolean isFloatingPoint(CtTypeReference<?> type) {
    TypeFactory factory = type.getFactory().Type();
    if (type.equals(factory.doublePrimitiveType()) || type.equals(factory.floatPrimitiveType())) {
      return true;
    }
    type = type.unbox();
    return type.equals(factory.doublePrimitiveType()) || type.equals(factory.floatPrimitiveType());
  }

  // The casts are potentially illegal but Spoon is really annoying to use otherwise
  @SuppressWarnings("unchecked")
  public static <B> CtTypeReference<? extends B> getLowestSupertype(
      CtTypeReference<? extends B> a,
      CtTypeReference<? extends B> b
  ) {
    if (a.equals(b)) {
      return a;
    }
    Set<CtTypeReference<?>> aSuper = supertypes(a);
    Set<CtTypeReference<?>> bSuper = supertypes(b);

    // aSuper now contains the shared supertypes
    aSuper.retainAll(bSuper);

    // Remove the parents of all types so we end up with the lowest element.
    for (CtTypeReference<?> ref : Set.copyOf(aSuper)) {
      aSuper.removeAll(ref.getSuperInterfaces());
      aSuper.remove(ref.getSuperclass());
    }

    if (aSuper.isEmpty()) {
      return (CtTypeReference<? extends B>) a.getFactory().Type().objectType();
    }
    return (CtTypeReference<? extends B>) aSuper.iterator().next();
  }

  private static Set<CtTypeReference<?>> supertypes(CtTypeReference<?> input) {
    Set<CtTypeReference<?>> result = new HashSet<>();

    Queue<CtTypeReference<?>> queue = new ArrayDeque<>();
    queue.add(input);

    CtTypeReference<?> current;
    while ((current = queue.poll()) != null) {
      result.add(current);
      queue.addAll(current.getSuperInterfaces());
      if (current.getSuperclass() != null) {
        queue.add(current.getSuperclass());
      }
    }

    return result;
  }

  /**
   * A very minimalistic function inliner. Requires a single return with a value at the end of the
   * method. Can not handle (in-)direct recursive calls.
   *
   * @param toInline the method to inline
   * @param call a call to the method
   */
  public static void inline(CtMethod<?> toInline, CtInvocation<?> call) {
    Factory factory = toInline.getFactory();
    long returnCount = toInline.getBody()
        .getStatements()
        .stream()
        .filter(it -> it instanceof CtReturn<?>)
        .count();

    if (!(toInline.getBody().getLastStatement() instanceof CtReturn<?>)) {
      throw new IllegalArgumentException("Method did not end with return " + toInline);
    }
    if (returnCount != 1) {
      throw new IllegalArgumentException("Not exactly one return statement in " + toInline);
    }
    if (toInline.getType().equals(factory.Type().voidPrimitiveType())) {
      throw new IllegalArgumentException("Void return type in " + toInline);
    }
    if (!toInline.getParameters().isEmpty()) {
      throw new IllegalArgumentException("Methods to inline must not have parameters");
    }

    if (call.getParent(CtExecutable.class).equals(toInline)) {
      throw new IllegalArgumentException("Can not inline recursively!");
    }

    // Deduplicate variable names
    Set<String> takenVariableNames = new HashSet<>();
    call.getParent(CtExecutable.class).accept(new CtScanner() {
      @Override
      public <T> void visitCtLocalVariable(CtLocalVariable<T> localVariable) {
        super.visitCtLocalVariable(localVariable);
        takenVariableNames.add(localVariable.getSimpleName());
      }

      @Override
      public <T> void visitCtParameter(CtParameter<T> parameter) {
        super.visitCtParameter(parameter);
        takenVariableNames.add(parameter.getSimpleName());
      }
    });

    Set<String> ourVariableNames = new HashSet<>();
    toInline.accept(new CtScanner() {
      @Override
      public <T> void visitCtLocalVariable(CtLocalVariable<T> localVariable) {
        super.visitCtLocalVariable(localVariable);
        ourVariableNames.add(localVariable.getSimpleName());
      }
    });

    if (!Sets.intersection(takenVariableNames, ourVariableNames).isEmpty()) {
      // we need to rename our variables
      Map<String, String> renames = new HashMap<>();
      for (String name : ourVariableNames) {
        String current = name;
        for (int counter = 0; true; counter++) {
          boolean hasConflict = takenVariableNames.contains(current);
          hasConflict |= (ourVariableNames.contains(current) && counter > 0);
          if (!hasConflict) {
            break;
          }
          current = current + counter;
        }
        renames.put(name, current);
      }

      toInline.accept(new CtScanner() {
        @Override
        public <T> void visitCtLocalVariable(CtLocalVariable<T> localVariable) {
          super.visitCtLocalVariable(localVariable);
          if (renames.containsKey(localVariable.getSimpleName())) {
            localVariable.setSimpleName(renames.get(localVariable.getSimpleName()));
          }
        }

        @Override
        public <T> void visitCtLocalVariableReference(CtLocalVariableReference<T> reference) {
          super.visitCtLocalVariableReference(reference);
          if (renames.containsKey(reference.getSimpleName())) {
            reference.setSimpleName(renames.get(reference.getSimpleName()));
          }
        }
      });
    }

    // Copy method statements over
    List<CtStatement> methodStatements = toInline.getBody().clone().getStatements();
    CtStatement callStatement = call.getParent(CtStatement.class);
    for (int i = 0; i < methodStatements.size() - 1; i++) {
      callStatement.insertBefore(methodStatements.get(i));
    }

    // Replace return value
    CtExpression<?> ourReturn = toInline.getBody()
        .<CtReturn<?>>getLastStatement()
        .getReturnedExpression()
        .clone();
    call.replace(ourReturn);
  }

  public static BiFunction<AssertionType, CtTypeReference<?>, CtStatement> getDeepAssertJAssertFunction(
      String helperFqn, boolean statistics
  ) {
    return (equality, type) -> {
      String text;
      boolean isSimple = isBasicallyPrimitive(type);
      if (type instanceof CtArrayTypeReference<?> arrayRef) {
        isSimple |= isBasicallyPrimitive(arrayRef.getArrayType());
      }
      isSimple |= type.getQualifiedName().equals("java.lang.Class");
      isSimple |= type.getQualifiedName().equals("java.util.Date");
      if (isFloatingPoint(type)) {
        text = """
            org.assertj.core.api.Assertions.assertThat(%1$s)
                        .usingComparator({{helper}}.{{type}}NanAwareComparator())
                        .isEqualTo(%2$s);"""
            .replace("{{type}}", type.getSimpleName().toLowerCase())
            .replace("{{helper}}", helperFqn);
      } else if (isSimple) {
        text = """
            org.assertj.core.api.Assertions.assertThat(%1$s).isEqualTo(%2$s);""";
      } else {
        text = """
            org.assertj.core.api.Assertions.assertThat(%1$s)
                    .usingRecursiveComparison({{helper}}.nanAwareComparison())
                    .isEqualTo(%2$s);"""
            .replace("{{helper}}", helperFqn);
      }

      if (statistics) {
        String prefix = """
            {{helper}}.collectStatistics(%1$s, %2$s);"""
            .replace("{{helper}}", helperFqn);
        text = prefix + text;
      }

      return type.getFactory()
          .createCodeSnippetStatement(
              text.formatted(equality.getActualName(), equality.getExpectedName())
          );
    };
  }

  public static String getAssertJDeepEqualsClass() {
    return """

        import java.util.Comparator;
        import java.util.List;
        import org.assertj.core.api.recursive.comparison.ComparisonDifference;
        import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
        import org.assertj.core.api.recursive.comparison.RecursiveComparisonDifferenceCalculator;
        import org.assertj.core.util.DoubleComparator;
        import org.assertj.core.util.FloatComparator;

        public class AssertJEqualityHelper {

          public static void collectStatistics(Object actual, Object expected) {
            List<ComparisonDifference> differences = new RecursiveComparisonDifferenceCalculator()
                .determineDifferences(actual, expected, nanAwareComparison());
            if (!differences.isEmpty()) {
              String caller = StackWalker.getInstance()
                  .walk(it -> it.skip(1).limit(1).findFirst())
                  .orElseThrow()
                  .toString();
              System.out.println("DIFF@ " + caller + " " + differences.size());
            }
          }

          public static RecursiveComparisonConfiguration nanAwareComparison() {
            return RecursiveComparisonConfiguration.builder()
                .withComparatorForType(doubleNanAwareComparator(), Double.class)
                .withComparatorForType(floatNanAwareComparator(), Float.class)
                .build();
          }

          public static Comparator<Double> doubleNanAwareComparator() {
            return (o1, o2) -> {
              if (Double.isNaN(o1) && Double.isNaN(o2)) {
                return 0;
              }
              return new DoubleComparator(1e-15).compare(o1, o2);
            };
          }

          public static Comparator<Float> floatNanAwareComparator() {
            return (o1, o2) -> {
              if (Float.isNaN(o1) && Float.isNaN(o2)) {
                return 0;
              }
              return new FloatComparator(1e-6f).compare(o1, o2);
            };
          }
        }
        """;
  }

  public static BiFunction<AssertionType, CtTypeReference<?>, CtStatement> getDeepReflectiveAssertFunction() {
    return (equality, type) -> type.getFactory()
        .createCodeSnippetStatement(
            "org.junit.jupiter.api.Assertions.assertTrue("
            + "reflectiveDeepEquals(%s, %s), \"Object equality check failed\"".formatted(
                equality.getExpectedName(), equality.getActualName()
            )
            + ")"
        );
  }

  public static Collection<CtMethod<?>> getReflectiveDeepEqualsMethods(Factory factory) {
    StringBuilder result = new StringBuilder();
    try (
        var inputStream = Spoons.class.getResourceAsStream("/ReflectiveDeepEquals.java");
        InputStreamReader inputStreamReader = new InputStreamReader(requireNonNull(inputStream));
        BufferedReader reader = new BufferedReader(inputStreamReader)
    ) {
      String line;
      while ((line = reader.readLine()) != null) {
        result.append(line).append("\n");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    Set<CtMethod<?>> methods = Launcher.parseClass(result.toString()).getMethods();
    for (CtMethod<?> method : methods) {
      fixInvocationTargets(method);
      Spoons.changeFactory(factory, method);
    }
    return methods;
  }

  private static void fixInvocationTargets(CtMethod<?> method) {
    String declaringType = method.getDeclaringType().getQualifiedName();
    method.accept(new CtScanner() {
      @Override
      public <T> void visitCtInvocation(CtInvocation<T> invocation) {
        super.visitCtInvocation(invocation);
        CtExecutableReference<T> executable = invocation.getExecutable();

        if (executable.getDeclaringType() == null) {
          return;
        }

        if (executable.getDeclaringType().getQualifiedName().equals(declaringType)) {
          invocation.setTarget(null);
        }
      }
    });
  }

  public static List<String> replaceVariableNames(
      List<String> statements, Function<String, String> rename
  ) {
    List<String> newStatements = new ArrayList<>();
    LinkedHashMap<String, String> replacements = new LinkedHashMap<>();
    for (String statement : statements) {
      Matcher matcher = VARIABLE_NAME_PATTERN.matcher(statement);
      if (matcher.find() && !matcher.group(1).equals("$OBJ$")) {
        replacements.put(
            Pattern.quote(matcher.group(1)) + "\\b",
            rename.apply(matcher.group(1))
                .replace("$", "\\$")
        );
      }
      String newStatement = statement;
      for (Entry<String, String> entry : replacements.entrySet()) {
        newStatement = newStatement.replaceAll(entry.getKey(), entry.getValue());
      }
      newStatements.add(newStatement);
    }

    return newStatements;
  }

  public static List<String> getStringStatements(List<CtStatement> statements) {
    return statements
        .stream()
        .map(Object::toString)
        .flatMap(it -> Arrays.stream(it.split(";")))
        .map(String::strip)
        .toList();
  }

  public static List<String> anonymizeVariableTypes(List<String> statements) {
    List<String> newStatements = new ArrayList<>();
    for (String statement : statements) {
      Matcher matcher = VARIABLE_TYPE_PATTERN.matcher(statement);
      if (!matcher.find()) {
        newStatements.add(statement);
        continue;
      }
      newStatements.add("var " + statement.substring(matcher.end(1) + 1));
    }

    return newStatements;
  }

  public static <T extends CtElement> T changeFactory(Factory factory, T element) {
    element.accept(new CtScanner() {
      @Override
      public void scan(CtElement element) {
        if (element != null) {
          element.setFactory(factory);
        }
        super.scan(element);
      }
    });
    element.setFactory(factory);
    return element;
  }

  @SuppressWarnings("unchecked")
  public static <R> CtTypeReference<R> getTypeFromString(Factory factory, String type) {
    Launcher launcher = new Launcher();
    launcher.getEnvironment().setComplianceLevel(17);
    launcher.getEnvironment().setNoClasspath(true);
    launcher.getEnvironment().setAutoImports(false);
    launcher.addInputResource(new VirtualFile(
        "class Foo { <type> foo; }".replace("<type>", type)
    ));
    CtTypeReference<?> typeRef = launcher.buildModel().getAllTypes().iterator().next()
        .getField("foo")
        .getType();

    Spoons.changeFactory(factory, typeRef);

    fixInnerClasses(factory, typeRef);
    typeRef.accept(new CtScanner() {
      @Override
      public <T> void visitCtTypeReference(CtTypeReference<T> reference) {
        super.visitCtTypeReference(reference);
        fixInnerClasses(factory, reference);
      }
    });

    return (CtTypeReference<R>) typeRef;
  }

  private static void fixInnerClasses(Factory factory, CtTypeReference<?> reference) {
    if (reference.getTypeDeclaration() != null) {
      return;
    }
    List<String> parts = Arrays.asList(reference.getQualifiedName().split("\\."));
    CtType<?> type = null;
    int lastIndex;
    for (lastIndex = parts.size(); lastIndex > 0; lastIndex--) {
      type = factory.Type().get(String.join(".", parts.subList(0, lastIndex)));
      if (type != null) {
        break;
      }
    }

    for (int i = lastIndex; type != null && i < parts.size(); i++) {
      type = type.getNestedType(parts.get(i));
    }

    if (type != null) {
      reference.setPackage(null);
      reference.setDeclaringType(type.getDeclaringType().getReference());
      reference.setSimpleName(type.getSimpleName());
    }
  }

  public static boolean isKeyword(String name) {
    return KEYWORDS.contains(name);
  }
}
