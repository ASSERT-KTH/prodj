package se.kth.castor.rockstofetch.cli;

import static se.kth.castor.pankti.codemonkey.util.Statistics.addStatDuration;

import se.kth.castor.rockstofetch.cli.Config.EqualityFunction;
import se.kth.castor.rockstofetch.generate.DataReader;
import se.kth.castor.rockstofetch.generate.DataReader.LoadedInvocation;
import se.kth.castor.rockstofetch.generate.EventSequence;
import se.kth.castor.rockstofetch.generate.GenerationContext;
import se.kth.castor.rockstofetch.generate.GenerationContext.AssertionType;
import se.kth.castor.rockstofetch.generate.GenerationException;
import se.kth.castor.rockstofetch.generate.GenerationException.Type;
import se.kth.castor.rockstofetch.generate.JunitTestClass;
import se.kth.castor.rockstofetch.generate.JunitTestMethodOutputOracle;
import se.kth.castor.rockstofetch.generate.JunitTestMethodParameterOracle;
import se.kth.castor.rockstofetch.generate.PostProcessor;
import se.kth.castor.rockstofetch.generate.TestFilterer;
import se.kth.castor.rockstofetch.instrument.InstrumentationConfiguration;
import se.kth.castor.rockstofetch.serialization.Json;
import se.kth.castor.rockstofetch.serialization.RockySerializer;
import se.kth.castor.rockstofetch.util.SpoonAccessor;
import se.kth.castor.rockstofetch.util.Spoons;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import se.kth.castor.pankti.codemonkey.serialization.UnknownActionHandler;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.Launcher;
import spoon.MavenLauncher;
import spoon.MavenLauncher.SOURCE_TYPE;
import spoon.processing.Processor;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.DefaultImportComparator;
import spoon.reflect.visitor.DefaultJavaPrettyPrinter;
import spoon.reflect.visitor.ForceImportProcessor;
import spoon.reflect.visitor.ImportCleaner;
import spoon.reflect.visitor.ImportConflictDetector;
import spoon.support.compiler.VirtualFile;
import spoon.support.compiler.jdt.JDTBasedSpoonCompiler;

public class Generation {

  private static final String ASSERTJ_HELPER_PATH = "se/kth/castor/AssertJEqualityHelper.java";
  private static final String ASSERTJ_HELPER_FQN = ASSERTJ_HELPER_PATH
      .replace(".java", "")
      .replace("/", ".");

  public static void generate(
      Path methodsJson, Path testBasePath, EqualityFunction equality, boolean filterTests,
      Statistics statistics
  ) throws IOException {
    InstrumentationConfiguration instrumentationConfiguration = Objects.requireNonNull(
        new Json().fromJson(Files.readString(methodsJson), InstrumentationConfiguration.class)
    );
    Path projectPath = instrumentationConfiguration.projectPath();
    Path dataPath = instrumentationConfiguration.dataPath();

    Instant invocationReadStart = Instant.now();
    List<LoadedInvocation> invocations = new DataReader()
        .loadInvocations(dataPath)
        .stream()
        .sorted(Comparator.comparing(it -> Spoons.testName(it.invocation().recordedMethod(), "")))
        .toList();
    addStatDuration(statistics, "invocationRead", invocationReadStart);
    Instant eventSequenceReadStart = Instant.now();
    EventSequence events = EventSequence.fromSequence(
        new DataReader().readEvents(dataPath),
        statistics
    );
    addStatDuration(statistics, "eventSequenceRead", eventSequenceReadStart);

    SpoonAccessor spoonAccessor = new SpoonAccessor(projectPath);

    RockySerializer noMockSerializer = new RockySerializer(
        spoonAccessor, Set.of(), Set.of(), Set.of(), UnknownActionHandler.fail(), null
    );

    BiFunction<AssertionType, CtTypeReference<?>, CtStatement> equalityFunction;
    if (equality == EqualityFunction.DEEP_REFLECTIVE) {
      equalityFunction = Spoons.getDeepReflectiveAssertFunction();
    } else if (equality == EqualityFunction.ASSERT_J_DEEP) {
      equalityFunction = Spoons.getDeepAssertJAssertFunction(
          ASSERTJ_HELPER_FQN,
          statistics != null
      );
    } else {
      equalityFunction = GenerationContext.defaultAssertFunction();
    }

    Map<JunitTestClass, List<LoadedInvocation>> loadedInvocations = invocations.stream()
        .collect(Collectors.groupingBy(
            loadedInvocation -> loadedInvocation.invocation().recordedMethod().declaringClassName())
        )
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            entry -> new JunitTestClass(
                spoonAccessor.getFactory(),
                entry.getKey().replace("$", "") + "RockyTest"
            ),
            Entry::getValue
        ));
    Instant generateTestsStart = Instant.now();
    List<Integer> objPerTest = new ArrayList<>();
    for (var entry : loadedInvocations.entrySet()) {
      for (LoadedInvocation loadedInvocation : entry.getValue()) {
        tryAddMethod(
            () -> {
              objPerTest.add(new JunitTestMethodOutputOracle(
                  spoonAccessor.getFactory(), loadedInvocation, events, equalityFunction
              ).buildTest(entry.getKey().getMethodCache(), statistics));
            }
        );

        tryAddMethod(
            () -> JunitTestMethodParameterOracle.forInvocation(
                spoonAccessor.getFactory(),
                noMockSerializer,
                loadedInvocation,
                events,
                statistics
            )
        );
      }
      entry.getKey().finalizeMethodCache();
    }
    loadedInvocations.keySet().removeIf(JunitTestClass::isEmpty);
    // Dependency hell, just not worth it.
    loadedInvocations.keySet()
        .removeIf(it -> it.getQualifiedName().endsWith("PDFToImageRockyTest"));

    addStatDuration(statistics, "generateTests", generateTestsStart);

    objPerTest.sort(Comparator.naturalOrder());
    System.out.println(objPerTest);
    System.out.println("I got median of " + objPerTest.get(objPerTest.size() / 2));

    for (JunitTestClass testClass : loadedInvocations.keySet()) {
      if (equality == EqualityFunction.DEEP_REFLECTIVE) {
        Spoons.getReflectiveDeepEqualsMethods(spoonAccessor.getFactory())
            .forEach(testClass::addMethod);
      }
    }

    try (Stream<Path> paths = Files.walk(testBasePath)) {
      for (Path path : paths.filter(it -> it.toString().endsWith("RockyTest.java")).toList()) {
        Files.delete(path);
      }
    }
    Files.deleteIfExists(testBasePath.resolve(ASSERTJ_HELPER_PATH));

    if (equality == EqualityFunction.ASSERT_J_DEEP) {
      Files.createDirectories(testBasePath.resolve(ASSERTJ_HELPER_PATH).getParent());
      Files.writeString(
          testBasePath.resolve(ASSERTJ_HELPER_PATH),
          "package " + ASSERTJ_HELPER_FQN.substring(0, ASSERTJ_HELPER_FQN.lastIndexOf('.')) + ";\n"
          + Spoons.getAssertJDeepEqualsClass()
      );
    }

    Instant postProcessStart = Instant.now();
    List<CtClass<?>> processed = postProcess(
        statistics, projectPath, loadedInvocations.keySet(), filterTests,
        testBasePath.resolve(ASSERTJ_HELPER_PATH)
    );
    addStatDuration(statistics, "postProcess", postProcessStart);

    Instant writeStart = Instant.now();
    for (CtClass<?> testClass : processed) {
      Path testPath = testBasePath.resolve(
          testClass.getTopLevelType().getQualifiedName().replace(".", "/") + ".java"
      );
      System.out.println("Writing " + testPath.toAbsolutePath().normalize());
      Files.createDirectories(testPath.getParent());
      Files.writeString(testPath, testClass.toStringWithImports());
    }
    addStatDuration(statistics, "write", writeStart);
  }

  private static void tryAddMethod(Runnable creationAction) {
    try {
      creationAction.run();
    } catch (GenerationException e) {
      // TODO: Maybe try fixing some...
      if (e.getType() == Type.REFERENCED_OBJECT_NOT_FOUND) {
        System.out.println(e.getMessage());
      }
    } catch (RuntimeException e) {
      e.printStackTrace();
    }
  }

  private static List<CtClass<?>> postProcess(
      Statistics statistics, Path projectPath, Collection<JunitTestClass> testClasses,
      boolean filterTests, Path... extraFiles
  ) {
    List<CtClass<?>> tests = buildModel(
        testClasses.stream()
            .collect(Collectors.toMap(JunitTestClass::getQualifiedName, JunitTestClass::serialize)),
        projectPath,
        (launcher, ctClass) -> new PostProcessor(statistics).process(ctClass),
        extraFiles
    );
    if (filterTests) {
      tests = buildModel(
          tests.stream()
              .collect(Collectors.toMap(CtType::getQualifiedName, CtType::toStringWithImports)),
          projectPath,
          (launcher, ctClass) -> new TestFilterer().filter(
              ctClass,
              ((JDTBasedSpoonCompiler) launcher.getModelBuilder()).getProblems()
          ),
          extraFiles
      );
    }

    return tests;
  }

  private static List<CtClass<?>> buildModel(
      Map<String, String> testClasses,
      Path projectPath,
      BiConsumer<Launcher, CtClass<?>> hook,
      Path... extraFiles
  ) {
    Launcher launcher = new MavenLauncher(projectPath.toString(), SOURCE_TYPE.APP_SOURCE);
    Arrays.stream(extraFiles).map(Path::toString).forEach(launcher::addInputResource);
    launcher.getEnvironment().setComplianceLevel(11);
    launcher.getEnvironment().setAutoImports(true);
    launcher.getEnvironment().setNoClasspath(true);
    launcher.getEnvironment().setSourceClasspath(
        withOwnClassPath(
            new MavenLauncher(
                projectPath.toString(), SOURCE_TYPE.ALL_SOURCE, Pattern.compile(".*")
            ).getEnvironment().getSourceClasspath()
        )
    );
    for (var entry : testClasses.entrySet()) {
      launcher.addInputResource(new VirtualFile(
          entry.getValue(),
          entry.getKey().replace(".", "/") + ".java"
      ));
    }
    launcher.getEnvironment()
        .setPrettyPrinterCreator(() -> new DefaultJavaPrettyPrinter(launcher.getEnvironment()) {
          {
            setMinimizeRoundBrackets(true);
            List<Processor<CtElement>> preprocessors = List.of(
                //try to import as much types as possible
                new ForceImportProcessor(),
                //remove unused imports first. Do not add new imports at time when conflicts are not resolved
                new ImportCleaner().setCanAddImports(false),
                //solve conflicts, the current imports are relevant too
                new ImportConflictDetector(),
                //compute final imports
                new ImportCleaner().setImportComparator(new DefaultImportComparator())
            );
            setIgnoreImplicit(false);
            setPreprocessors(preprocessors);
          }
        });
    launcher.buildModel();

    List<CtClass<?>> tests = new ArrayList<>();
    for (String testName : testClasses.keySet()) {
      CtClass<?> test = launcher.getFactory().Class().get(testName);
      hook.accept(launcher, test);

      tests.add(test);
    }

    return tests;
  }

  private static String[] withOwnClassPath(String[] existing) {
    String ourDirectory = ".";
    URL ourLocation = Generation.class.getProtectionDomain()
        .getCodeSource()
        .getLocation();
    if (ourLocation.toString().endsWith("target/classes/")) {
      ourDirectory = "rockstofetch";
    }
    List<String> ours = new ArrayList<>(Arrays.asList(
        new MavenLauncher(ourDirectory, SOURCE_TYPE.ALL_SOURCE).getEnvironment()
            .getSourceClasspath()
    ));
    ours.addAll(Arrays.asList(existing));
    return ours.toArray(String[]::new);
  }
}
