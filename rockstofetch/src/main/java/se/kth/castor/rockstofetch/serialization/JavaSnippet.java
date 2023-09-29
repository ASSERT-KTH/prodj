package se.kth.castor.rockstofetch.serialization;

import static se.kth.castor.rockstofetch.util.Classes.className;

import se.kth.castor.rockstofetch.generate.EventBasedObjectConstructor;
import se.kth.castor.rockstofetch.generate.GenerationContext;
import se.kth.castor.rockstofetch.generate.GenerationException;
import se.kth.castor.rockstofetch.generate.GenerationException.Type;
import se.kth.castor.rockstofetch.util.Mocks;
import se.kth.castor.rockstofetch.util.Spoons;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import se.kth.castor.rockstofetch.util.Classes;
import spoon.reflect.code.CtCodeSnippetStatement;
import spoon.reflect.code.CtExpression;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

public record JavaSnippet(
    List<String> statements,
    String dynamicType,
    String staticType,
    int containedObjects
) {

  private static final Pattern NAME_SUFFIX_PATTERN = Pattern.compile("_([^_]+?)\\s+=");
  private static final Pattern FULL_NAME_PATTERN = Pattern.compile("\\s+(.+?)_\\d+");
  private static final Pattern OBJECT_REF_PATTERN = Pattern.compile(
      "(.+?)(?:<.+?>)? (.+) = \"ref:(\\d+?)@(\\d+?)\"");

  public JavaSnippet(List<String> statements, Class<?> dynamicType, String staticType, int containedObjects) {
    this(
        statements,
        Classes.className(dynamicType),
        staticType,
        containedObjects
    );
  }

  public JavaSnippet(List<String> statements, Class<?> dynamicType, Class<?> staticType, int containedObjects) {
    this(
        statements,
        Classes.className(dynamicType),
        Classes.className(staticType),
        containedObjects
    );
  }

  public JavaSnippet {
    Objects.requireNonNull(statements);
    Objects.requireNonNull(dynamicType);
    Objects.requireNonNull(staticType);
  }

  /**
   * Converts this snippet to a {@link spoon.reflect.code.CtCodeSnippetStatement}, creating a local
   * variable with the given name. This also rewrites mock variables to remove the target suffix and
   * instead generates the necessary mock calls.
   *
   * @param factory the factory to use
   * @param targetVariableName the name the new variable should have
   * @param context the generation context
   * @return a {@link CtCodeSnippetStatement} creating a local variable
   */
  public CtCodeSnippetStatement asSnippet(
      Factory factory,
      String targetVariableName,
      GenerationContext context
  ) {
    StringJoiner result = new StringJoiner(";" + System.lineSeparator());
    String mockitoName = Mocks.getMockito(factory).getQualifiedName();
    Pattern ourTypePattern = Pattern.compile(
        "(?<type>.+) " + Pattern.quote(targetVariableName) + " = "
    );
    CtTypeReference<?> ourType = null;

    for (String line : asString(targetVariableName).lines().toList()) {
      if (line.contains(mockitoName)) {
        handleMock(context.tempMockNames(), result, line);
        continue;
      }
      Matcher ourTypeMatcher = ourTypePattern.matcher(line);
      if (ourTypeMatcher.find()) {
        ourType = Spoons.getTypeFromString(factory, ourTypeMatcher.group("type"));
      }

      Matcher matcher = OBJECT_REF_PATTERN.matcher(line);
      // performance optimization as the regex exhibits catastrophic backtracking...
      if (!line.contains("=") || !line.contains("\"ref") || !matcher.find()) {
        result.add(stripSemicolon(line));
        continue;
      }

      String typeName = matcher.group(1).strip();
      String variableName = matcher.group(2).strip();
      int id = Integer.parseInt(matcher.group(3));
      long timestamp = Long.parseLong(matcher.group(4));
      String constructed = handleConstruct(factory, context, variableName, typeName, id, timestamp);

      result.add(stripSemicolon(constructed));
    }

    CtExpression<?> call = context.getCallToDeduplicatedMethod(
        ourType,
        targetVariableName,
        List.of(factory.createCodeSnippetStatement(result.toString()))
    );
    context.containedObjects().addAndGet(this.containedObjects);
    return factory.createCodeSnippetStatement(
        ourType + " " + targetVariableName + " = " + call
    );
  }

  private void handleMock(Map<Integer, String> tempMockNames, StringJoiner result, String line) {
    Matcher nameSuffixMatcher = NAME_SUFFIX_PATTERN.matcher(line);
    Matcher fullNameMatcher = FULL_NAME_PATTERN.matcher(line);
    if (!nameSuffixMatcher.find() || !fullNameMatcher.find()) {
      throw new RuntimeException("Unexpected mock call format: '" + line + "'");
    }

    String assignedVariableName = fullNameMatcher.group(1);
    int targetId = Integer.parseInt(nameSuffixMatcher.group(1));
    tempMockNames.put(targetId, assignedVariableName);
    result.add(stripSemicolon(nameSuffixMatcher.replaceAll(" =")));
  }

  private String handleConstruct(
      Factory factory,
      GenerationContext context,
      String name,
      String type,
      int objectId,
      long timestamp
  ) {
    Instant start = Instant.now();
    CtTypeReference<?> typeRef = Spoons.getTypeFromString(factory, type);
    CtExpression<?> read = context.getCallToDeduplicatedMethod(
        typeRef,
        name,
        EventBasedObjectConstructor.fromEvents(context, factory, objectId, timestamp)
            .construct(name, typeRef)
    );
    if (context.statistics() != null) {
      context.statistics()
          .getGeneral()
          .addToAveragedDuration("constructFromEvents", Duration.between(start, Instant.now()));
    }
    return type + " " + name + " = " + read;
  }

  private String asString(String variableName) {
    if (statements.isEmpty()) {
      // TODO: Do not even serialize them?
      throw new GenerationException(Type.SNIPPET_EMPTY);
    }
    return String.join(";\n", statements).replace("$OBJ$", variableName);
  }

  private String stripSemicolon(Object statement) {
    String asString = statement.toString().strip();

    if (asString.endsWith(";")) {
      return asString.substring(0, asString.length() - 1);
    }
    return asString;
  }
}
