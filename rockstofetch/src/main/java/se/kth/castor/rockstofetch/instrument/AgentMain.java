package se.kth.castor.rockstofetch.instrument;

import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.hasParameters;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isAccessibleTo;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isEnum;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

import se.kth.castor.rockstofetch.extract.ClassSerializationType;
import se.kth.castor.rockstofetch.instrument.aspects.MutatorCallTraceFactory;
import se.kth.castor.rockstofetch.instrument.aspects.SetFieldTraceFactory;
import se.kth.castor.rockstofetch.serialization.Json;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Listener;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer.ForAdvice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeDescription.ForLoadedType;
import net.bytebuddy.description.type.TypeDescription.Generic;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatcher.Junction;
import net.bytebuddy.matcher.ElementMatcher.Junction.AbstractBase;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import se.kth.castor.pankti.codemonkey.util.GlobalSwitches;
import se.kth.castor.pankti.codemonkey.util.Statistics;

public class AgentMain {

  /**
   * All types that the serializer is allowed to mock (as we record their invocations).
   */
  public static Set<String> mockConstructTypes;
  /**
   * All types that the serializer is allowed to fixme-construct
   */
  public static Set<String> fixmeConstructTypes;
  /**
   * All types that the serializer is allowed to fixme-construct
   */
  public static Set<String> mutationTraceTypes;
  /**
   * The path to the analyzed project
   */
  public static Path projectPath;
  /**
   * The path to store output data in
   */
  public static Path dataPath;
  /**
   * Whether to collect usage statistics
   */
  public static Statistics statistics;

  public static void premain(String arguments, Instrumentation instrumentation) throws IOException {
    Path methodsToInstrumentPath = Path.of(arguments);
    InstrumentationConfiguration instrumentationConfiguration = Objects.requireNonNull(
        new Json().fromJson(
            Files.readString(methodsToInstrumentPath),
            InstrumentationConfiguration.class
        )
    );
    projectPath = instrumentationConfiguration.projectPath();
    dataPath = instrumentationConfiguration.dataPath();
    mockConstructTypes = Set.of();
    fixmeConstructTypes = instrumentationConfiguration.classTypes()
        .entrySet()
        .stream()
        .filter(it -> it.getValue() == ClassSerializationType.FIXME)
        .map(Entry::getKey)
        .collect(Collectors.toSet());
    mutationTraceTypes = instrumentationConfiguration.mutationTraceTypes();
    statistics = instrumentationConfiguration.collectStatistics()
        ? new Json().fromJson(Files.readString(dataPath.resolve("stats.json")), Statistics.class)
        : null;

    if (statistics != null) {
      setupStatisticsDumper(dataPath.resolve("stats.json"));
    }

    Junction<TypeDescription> allTypesMatcher = matcherForRecordedMethodTypes(
        instrumentationConfiguration.methods()
    )
        .or(matcherForTypesIncludingHierarchy(mutationTraceTypes));

    Junction<TypeDescription> packagesToInstrumentMatcher = instrumentationConfiguration
        .packagesToInstrument()
        .stream()
        .reduce(
            none(),
            (Junction<TypeDescription> acc, String next) -> acc.or(nameStartsWith(next)),
            Junction::or
        );
    // put the and restrictions first
    packagesToInstrumentMatcher = not(isSynthetic())
        .and(not(nameContains("CGLIB$")))
        .and(not(TypeDescription::isAnonymousType))
        .and(not(nameContains("$HibernateProxy$")))
        .and(packagesToInstrumentMatcher);

    ElementMatcher<ByteCodeElement> isAccessible =
        GlobalSwitches.ONLY_ALLOW_PUBLIC_ELEMENTS
            ? isAccessibleTo(AgentMain.class)
            : new IsNotPrivateMatcher();

    Junction<TypeDescription> mutationTypeMatcher = not(isEnum())
        .and(isAccessible)
        .and(matcherForTypesIncludingHierarchy(mutationTraceTypes));

    new AgentBuilder.Default()
        // Otherwise the instrumentation swallows any error, which makes things hard to debug
        .with(new LoggingListener())
        // We want to retransform classes and the JVM does not support hotswap with schema changes
        .disableClassFormatChanges()
        .with(RedefinitionStrategy.RETRANSFORMATION)
        // Only re-transform types we either record or record/mock as nested invocations
        .type(packagesToInstrumentMatcher)
        .and(allTypesMatcher)
        .transform(
            new ForAdvice()
                .include(Thread.currentThread().getContextClassLoader())
                .advice(
                    matcherForRecordedMethods(instrumentationConfiguration.methods()),
                    "se.kth.castor.rockstofetch.instrument.aspects.MutInvocationPointcut"
                )
        )
        // Mostly for our ClosableLock
        .type(not(nameStartsWith("se.kth.castor.rockstofetch")))
        .and(packagesToInstrumentMatcher)
        .and(mutationTypeMatcher)
        .transform(
            new ForAdvice()
                .include(Thread.currentThread().getContextClassLoader())
                .advice(
                    isConstructor()
                        .and(not(isDeclaredBy(TypeDescription::isLocalType)))
//                        .and(isAccessible)
                        .and(not(isDeclaredBy(isAbstract()))),
                    "se.kth.castor.rockstofetch.instrument.aspects.MutationTracingConstructorPointcut"
                )
        )
        .transform(
            new ForAdvice()
                .include(Thread.currentThread().getContextClassLoader())
                .advice(
                    isMethod().and(not(isStatic())).and(isAccessible)
                        .or(
                            isMethod().and(isStatic())
                                .and(target -> target.getReturnType()
                                    .asErasure()
                                    .isAssignableFrom(target.getDeclaringType().asErasure()))
                        ),
                    "se.kth.castor.rockstofetch.instrument.aspects.MutationTracingMethodPointcut"
                )
        )
        .transform(
            (builder, typeDescription, classLoader, module, protectionDomain) -> builder.visit(
                MemberSubstitution.relaxed()
                    .field(any())
                    .onWrite()
                    .replaceWithChain(SetFieldTraceFactory.getChain(mutationTypeMatcher))
                    .on(not(isConstructor()).and(not(isStatic())))
                    .writerFlags(ClassWriter.COMPUTE_FRAMES)
            ))
        .transform(
            (builder, typeDescription, classLoader, module, protectionDomain) -> builder.visit(
                MemberSubstitution.relaxed()
                    .method(hardcodedMutatingMethods().and(not(isConstructor())))
                    .replaceWithChain(MutatorCallTraceFactory.getChain(mutationTypeMatcher))
                    .on(not(isConstructor()).and(not(isStatic())))
                    .writerFlags(ClassWriter.COMPUTE_FRAMES)
            ))
        .installOn(instrumentation);
  }

  private static void setupStatisticsDumper(Path statisticsPath) {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        Files.writeString(
            statisticsPath,
            new Json().prettyPrint(statistics)
        );
      } catch (IOException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    }));
  }

  private static Junction<TypeDescription> matcherForRecordedMethodTypes(
      List<RecordedMethod> methods
  ) {
    return new AnyOf<>(
        methods.stream()
            .map(RecordedMethod::declaringClassName)
            .distinct()
            .map(ElementMatchers::<TypeDescription>named)
            .toList()
    );
  }

  private static Junction<TypeDescription> matcherForTypesIncludingHierarchy(
      Collection<String> types
  ) {
    return new AnyOf<>(
        types.stream()
            .map(name -> named(name).or(hasSuperType(named(name))))
            .toList()
    );
  }

  private static Junction<MethodDescription> matcherForRecordedMethods(
      List<RecordedMethod> methods
  ) {
    return new AnyOf<>(
        methods.stream()
            .map(it -> named(it.methodName())
                .and(hasParams(it.parameterTypes()))
                .and(isDeclaredBy(
                    named(it.declaringClassName())
                        .or(hasSuperType(named(it.declaringClassName())))
                ))
            )
            .toList()
    );
  }

  private static Junction<MethodDescription> hasParams(List<String> parameters) {
    return hasParameters(
        target -> {
          int index = 0;
          for (ParameterDescription param : target) {
            if (index >= parameters.size()) {
              return false;
            }
            Generic type = param.getType().asRawType();
            String typeName = type.getActualName().isEmpty()
                ? type.getTypeName()
                : type.getActualName();
            if (!typeName.equals(parameters.get(index))) {
              return false;
            }
            index++;
          }
          return index == parameters.size();
        }
    );
  }

  private static Junction<MethodDescription> hardcodedMutatingMethods() {
    return not(isStatic())
        .and(
            matchMethodsFromType(
                Collection.class,
                "contains", "containsAll", "isEmpty", "listIterator", "iterator", "parallelStream",
                "size", "stream", "get", "toArray", "hashCode", "equals"
            ).or(matchMethodsFromType(
                Map.class,
                "containsKey", "containsValue", "entrySet", "forEach", "get", "getOrDefault",
                "isEmpty",
                "keySet", "size", "values"
            )).or(named("validate"))
        );
  }

  private static Junction<MethodDescription> matchMethodsFromType(
      Class<?> clazz, String... excludedMethods
  ) {
    return not(namedOneOf(excludedMethods)).and(isDeclaredBy(
        named(clazz.getName())
            .or(hasSuperType(named(clazz.getName())))
            .and(not(named(Object.class.getName())))
    ));
  }

  /**
   * Our own custom disjunction as the generics of the existing one are a bit of a pain.
   *
   * @param <T> the type to match
   */
  private static class AnyOf<T> extends AbstractBase<T> {

    private final List<Junction<T>> list;

    public AnyOf(List<Junction<T>> list) {
      this.list = list;
    }

    @Override
    public boolean matches(T target) {
      for (Junction<T> junction : list) {
        if (junction.matches(target)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public String toString() {
      return "AnyOf{" +
             "list=" + list +
             '}';
    }
  }

  @SuppressWarnings("NullableProblems")
  private static class LoggingListener implements Listener {

    @Override
    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module,
        boolean loaded) {
    }

    @Override
    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
        JavaModule module, boolean loaded, DynamicType dynamicType) {
      System.out.println("AgentMain.onTransformation");
      System.out.println(
          "  typeDescription = \033[36m" + typeDescription + "\033[0m, classLoader = " + classLoader
          + ", module = " + module + ", loaded = " + loaded + ", dynamicType = "
          + dynamicType
      );
    }

    @Override
    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
        JavaModule module, boolean loaded) {
    }

    @Override
    public void onError(String typeName, ClassLoader classLoader, JavaModule module,
        boolean loaded, Throwable throwable) {
      System.out.println("AgentMain.onError");
      System.out.println(
          "\033[31m  typeName = " + typeName + ", classLoader = " + classLoader + ", module = "
          + module
          + ", loaded = " + loaded + ", throwable = " + throwable
          + "\033[0m");
    }

    @Override
    public void onComplete(String typeName, ClassLoader classLoader, JavaModule module,
        boolean loaded) {
    }
  }

  private static class IsNotPrivateMatcher implements ElementMatcher<ByteCodeElement> {

    @Override
    public boolean matches(ByteCodeElement target) {
      if (target == null) {
        return true;
      }
      if (target.isAccessibleTo(new ForLoadedType(AgentMain.class))) {
        return true;
      }
      if (target instanceof TypeDescription typeDescription) {
        if (typeDescription.isArray()) {
          typeDescription = typeDescription.getComponentType();
          assert typeDescription != null;
        }
        return !typeDescription.isPrivate() && matches(typeDescription.getDeclaringType());
      }
      if (target instanceof MethodDescription methodDescription) {
        return !methodDescription.isPrivate()
               && matches(methodDescription.getDeclaringType().asErasure());
      }
      return false;
    }
  }

}
