package se.kth.castor.rockstofetch.generate;

import static org.apache.commons.lang3.StringUtils.capitalize;

import se.kth.castor.rockstofetch.util.Spoons;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;
import spoon.support.adaption.TypeAdaptor;

/**
 * @param tempMockNames map with names for temporary mocks
 */
public record GenerationContext(
    Map<Integer, String> tempMockNames,
    EventSequence events,
    AtomicInteger freshIds,
    BiFunction<AssertionType, CtTypeReference<?>, CtStatement> expectedActualEqualityFunction,
    MethodCache methodCache,
    Statistics statistics,
    AtomicInteger containedObjects
) {

  public GenerationContext(
      Map<Integer, String> tempMockNames,
      EventSequence events,
      BiFunction<AssertionType, CtTypeReference<?>, CtStatement> expectedActualEqualityFunction,
      MethodCache methodCache, Statistics statistics
  ) {
    this(
        tempMockNames, events, new AtomicInteger(), expectedActualEqualityFunction,
        methodCache, statistics, new AtomicInteger()
    );
  }

  public GenerationContext(
      Map<Integer, String> tempMockNames, EventSequence events, Statistics statistics
  ) {
    this(
        tempMockNames, events, new AtomicInteger(), defaultAssertFunction(), new MethodCache(),
        statistics, new AtomicInteger()
    );
  }

  public <T> CtExpression<T> getCallToDeduplicatedMethod(
      CtTypeReference<?> type,
      String variableName,
      List<CtStatement> statements
  ) {
    String anonymizedStatements = String.join(
        ";\n",
        removeVariableNamesAndTypes(Spoons.getStringStatements(statements))
    );

    if (statistics != null && methodCache.hasMethod(anonymizedStatements)) {
      statistics.getProcessing().addDeduplicatedMethod();
    }

    CtMethod<?> actualMethod = methodCache.computeIfAbsent(
        anonymizedStatements,
        type,
        name -> createMethodForStatementsWithType(name, type, variableName, statements)
    );

    boolean needsCast = !TypeAdaptor.isSubtype(actualMethod.getType().getTypeDeclaration(), type);
    String cast = needsCast ? "(" + type.getQualifiedName().replace("$", ".") + ") " : "";
    return type.getFactory().createCodeSnippetExpression(
        cast + "this.%s()".formatted(actualMethod.getSimpleName())
    );
  }

  private CtMethod<?> createMethodForStatementsWithType(
      String methodName,
      CtTypeReference<?> type,
      String variableName,
      List<CtStatement> statements
  ) {
    Factory factory = type.getFactory();
    CtMethod<?> method = factory.createMethod();
    method.setType(type);
    method.setSimpleName(methodName);
    method.setThrownTypes(Set.of(factory.createCtTypeReference(Exception.class)));
    method.setBody(factory.createBlock().setStatements(statements));
    method.getBody().addStatement(factory.createCodeSnippetStatement("return " + variableName));

    return method;
  }

  private List<String> removeVariableNamesAndTypes(List<String> statements) {
    statements = Spoons.anonymizeVariableTypes(statements);

    Map<String, String> replacements = new HashMap<>();
    return Spoons.replaceVariableNames(
        statements,
        s -> replacements.computeIfAbsent(s, ignored -> "v" + replacements.size())
    );
  }

  public static BiFunction<AssertionType, CtTypeReference<?>, CtStatement> defaultAssertFunction() {
    return (equality, type) -> {
      Factory factory = type.getFactory();
      if (type.isArray()) {
        return factory.createCodeSnippetStatement("""
            org.junit.jupiter.api.Assertions.assertArrayEquals(%s, %s)"""
            .formatted(equality.getExpectedName(), equality.getActualName()));
      } else if (Set.of("float", "double").contains(type.getSimpleName())) {
        return factory.createCodeSnippetStatement("""
            org.junit.jupiter.api.Assertions.assertEquals(%s, %s, 0.0001f)"""
            .formatted(equality.getExpectedName(), equality.getActualName()));
      } else {
        return factory.createCodeSnippetStatement("""
            org.junit.jupiter.api.Assertions.assertEquals(%s, %s)"""
            .formatted(equality.getExpectedName(), equality.getActualName()));
      }
    };
  }

  public enum AssertionType {
    EXPECTED_ACTUAL("expected", "actual"),
    RECEIVER_PRE_POST("receiverPost", "receiver");
    private final String expectedName;
    private final String actualName;

    AssertionType(String expectedName, String actualName) {
      this.expectedName = expectedName;
      this.actualName = actualName;
    }

    public String getActualName() {
      return actualName;
    }

    public String getExpectedName() {
      return expectedName;
    }
  }

  public static class MethodCache {

    private final Map<String, CtMethod<?>> methodCache;
    private final Set<String> nameCache;
    private final Map<String, Set<String>> fullTypeNameCache;

    public MethodCache(
        Map<String, CtMethod<?>> methodCache,
        Set<String> nameCache,
        Map<String, Set<String>> fullTypeNameCache
    ) {
      this.methodCache = new HashMap<>(methodCache);
      this.nameCache = new HashSet<>(nameCache);
      this.fullTypeNameCache = fullTypeNameCache.entrySet()
          .stream()
          .collect(Collectors.toMap(
              Entry::getKey,
              entry -> new HashSet<>(entry.getValue())
          ));
    }

    public MethodCache() {
      this.methodCache = new HashMap<>();
      this.nameCache = new HashSet<>();
      this.fullTypeNameCache = new HashMap<>();
    }

    public Collection<CtMethod<?>> getMethods() {
      return methodCache.values();
    }

    public CtMethod<?> addMethod(CtMethod<?> method) {
      List<String> statements = Spoons.anonymizeVariableTypes(
          Spoons.getStringStatements(method.getBody().getStatements())
      );
      String key = String.join(";\n", statements);
      if (methodCache.putIfAbsent(key, method) != null) {
        System.out.println("Oh, dedup me");
      }
      return methodCache.get(key);
    }

    private String nameForCreationMethod(CtTypeReference<?> type) {
      Set<String> qualifiedTypesForSimpleName = fullTypeNameCache.computeIfAbsent(
          type.getSimpleName(), ignored -> new HashSet<>()
      );
      qualifiedTypesForSimpleName.add(type.getSimpleName());

      String typeName = qualifiedTypesForSimpleName.size() == 1
          ? type.getSimpleName()
          : type.getQualifiedName();

      for (int i = 0; ; i++) {
        String suffix = i == 0 ? "" : Integer.toString(i - 1);
        String name = Spoons.sanitizeName("create" + capitalize(typeName) + suffix);

        if (!nameCache.contains(name)) {
          return name;
        }
      }
    }

    public CtMethod<?> computeIfAbsent(
        String anonymizedStatements,
        CtTypeReference<?> type,
        Function<String, CtMethod<?>> methodSupplier
    ) {
      CtMethod<?> method = methodCache.computeIfAbsent(
          anonymizedStatements,
          s -> methodSupplier.apply(nameForCreationMethod(type))
      );
      nameCache.add(method.getSimpleName());
      return method;
    }

    boolean hasMethod(String anonymizedStatements) {
      return methodCache.containsKey(anonymizedStatements);
    }

    public MethodCache copy() {
      return new MethodCache(methodCache, nameCache, fullTypeNameCache);
    }

    public void addAll(MethodCache other) {
      methodCache.putAll(other.methodCache);
      nameCache.addAll(other.nameCache);
      fullTypeNameCache.putAll(other.fullTypeNameCache);
    }

    public void clear() {
      methodCache.clear();
      nameCache.clear();
      fullTypeNameCache.clear();
    }
  }
}
