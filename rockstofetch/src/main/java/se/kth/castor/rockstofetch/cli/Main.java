package se.kth.castor.rockstofetch.cli;

import static java.util.function.Predicate.not;
import static se.kth.castor.pankti.codemonkey.util.Statistics.addStatDuration;

import se.kth.castor.rockstofetch.extract.CandidateMethodExtractor;
import se.kth.castor.rockstofetch.extract.ExtractCandidateMethod;
import se.kth.castor.rockstofetch.extract.RecordingCandidateMethod;
import se.kth.castor.rockstofetch.extract.coverage.JacocoFacade;
import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import se.kth.castor.rockstofetch.serialization.Json;
import se.kth.castor.rockstofetch.util.SpoonAccessor;
import se.kth.castor.rockstofetch.util.Spoons;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.reflect.declaration.CtModule;

public class Main {

  private static String heading(String text) {
    return "\n" + " ".repeat(0) + "\033[94;1m==== \033[36;1m" + text
           + " \033[94;1m====\033[0m";
  }

  public static void main(String[] args) throws Exception {
    System.err.println(heading("Parsing CLI arguments"));
    MainCliArgs arguments = new MainCliArgsParser().parseOrExit(args);

    Path configPath = arguments.config();
    System.err.println(heading("Parsing config from " + configPath.toAbsolutePath()));

    Config config = Objects.requireNonNull(
        new Json().fromJson(Files.readString(configPath), Config.class)
    );
    Path projectPath = resolveAgainstPath(configPath.getParent(), config.projectPath());
    Path dataPath = resolveAgainstPath(projectPath, config.dataPath());
    Path methodsJsonPath = resolveAgainstPath(projectPath, config.methodsJson());
    Path testBasePath = resolveAgainstPath(projectPath, config.testBasePath());

    System.out.println("projectPath = " + projectPath);

    if (arguments.runTests().isPresent()) {
      runTests(arguments.runTests().get());
      return;
    }

    if (arguments.productionCoverage()) {
      List<RecordedMethod> executedInProduction = computeProductionCoverage(
          configPath,
          projectPath,
          config.productionCommand(),
          arguments.printAllCoveredMethods()
      );
      Set<RecordedMethod> coveredInTest = new HashSet<>(
          getCoveredTestMethods(testBasePath, projectPath)
      );
      System.out.println(heading("Executed uncovered methods (MUTs)"));
      long executedUncovered = executedInProduction.stream()
          .filter(not(coveredInTest::contains))
          .count();
      System.out.println("A total of " + executedUncovered + ".");
      SpoonAccessor spoonAccessor = new SpoonAccessor(projectPath);
      executedInProduction.stream().filter(not(coveredInTest::contains))
          .map(it -> Spoons.getCtMethod(spoonAccessor.getFactory(), it))
          .map(it -> it.getType() + " " + it.getSimpleName() + " " + it.getSignature())
          .sorted()
          .forEach(System.out::println);
      return;
    }

    if (Files.exists(dataPath)) {
      FileUtils.cleanDirectory(dataPath.toFile());
    }
    Files.createDirectories(dataPath);

    System.out.println(heading("Finding (partially) covered methods"));
    List<RecordedMethod> coveredMethods = new ArrayList<>();
    if (!config.ignoreCoverage()) {
      coveredMethods = getCoveredTestMethods(testBasePath, projectPath);
      if (arguments.printAllCoveredMethods()) {
        printCoveredMethods(coveredMethods);
      }
      System.out.println("Found " + coveredMethods.size() + " covered methods");
    } else {
      System.out.println("Ignored by config");
    }

    System.err.println(heading("Preparing capture"));
    Statistics statistics = arguments.statistics() ? new Statistics() : null;
    Instant prepareStart = Instant.now();
    int numberOfTypes = Prepare.prepare(
        projectPath, methodsJsonPath, dataPath, coveredMethods,
        config.additionalInstrumentedPackages(), statistics
    );

    if (statistics != null) {
      addStatDuration(statistics, "prepare", prepareStart);
      statistics.getGeneral().addDuration(
          "preparePerType_" + numberOfTypes,
          Duration.between(prepareStart, Instant.now()).dividedBy(numberOfTypes)
      );
      Files.writeString(dataPath.resolve("stats.json"), new Json().prettyPrint(statistics));
    }

    System.err.println(heading("Figuring out own data"));
    Path agentJar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String agentCommand = "'-javaagent:%s=%s'".formatted(
        agentJar.toAbsolutePath().toString(),
        methodsJsonPath.toAbsolutePath().toString()
    );

    System.err.println(heading("Running production workload..."));
    System.out.println("Setting workdir to project directory: " + projectPath);
    Instant productionRunStart = Instant.now();
    ProcessBuilder processBuilder = new ProcessBuilder(
        "bash",
        "-m",
        "-c",
        config.productionCommand()
            .replace("{{agent_call}}", agentCommand)
            .replace("{{config_dir}}", configPath.getParent().toAbsolutePath().toString())
    )
        .directory(projectPath.toFile());
    if (arguments.hideProgramOutput()) {
      processBuilder
          .redirectError(Redirect.DISCARD)
          .redirectOutput(Redirect.DISCARD);
    } else {
      processBuilder.inheritIO();
    }

    Process process = processBuilder.start();

    try {
      process.waitFor();
    } catch (InterruptedException e) {
      System.out.println("Interrupted! Killing...");
      process.destroy();
      if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
      return;
    }

    statistics = arguments.statistics()
        ? new Json().fromJson(Files.readString(dataPath.resolve("stats.json")), Statistics.class)
        : null;

    addStatDuration(statistics, "executeProduction", productionRunStart);

    System.err.println(heading("Generating test"));
    System.out.println("testBasePath = " + testBasePath);
    Instant generateStart = Instant.now();
    Generation.generate(
        methodsJsonPath, testBasePath,
        config.usedEquality(),
        config.filterTests(),
        statistics
    );
    addStatDuration(statistics, "generate", generateStart);
    if (statistics != null) {
      Files.writeString(
          dataPath.resolve("stats.json"),
          new Json().prettyPrint(statistics)
      );
    }
  }

  private static void runTests(Path testPath) throws IOException, InterruptedException {
    System.out.println(heading("Running tests and capturing output"));

    Path tempFile = Files.createTempFile("run-log", ".txt");
    try {
      Process process = new ProcessBuilder("mvn", "test")
          .directory(testPath.toFile())
          .redirectError(Redirect.DISCARD)
          .redirectOutput(Redirect.to(tempFile.toFile()))
          .start();
      process.waitFor(2, TimeUnit.MINUTES);

      List<Map.Entry<String, Integer>> parts = Files.readAllLines(tempFile).stream()
          .filter(it -> it.startsWith("DIFF@"))
          .map(it -> it.split(" "))
          .map(it -> Map.entry(it[1], Integer.parseInt(it[2])))
          .toList();
      parts.forEach(System.out::println);
      System.out.println("Total known failures: " + parts.stream().mapToInt(Entry::getValue).sum());

      String summaryLine = Files.readAllLines(tempFile).stream()
          .filter(it -> it.contains("Tests run: "))
          .reduce((a, b) -> b)
          .orElseThrow();
      System.out.println(summaryLine);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  private static List<RecordedMethod> getCoveredTestMethods(Path testBasePath, Path projectPath)
      throws IOException, InterruptedException {
    removeOldTests(testBasePath);
    return new JacocoFacade("tests")
        .getCoveredMethods(projectPath.resolve("rtf-coverage-tests"), projectPath, false);
  }

  private static List<RecordedMethod> computeProductionCoverage(
      Path configPath, Path projectPath, String productionCommand,
      boolean printAllCoveredMethods
  )
      throws IOException, InterruptedException {
    Path dataPath = projectPath.resolve("rtf-coverage-prod");
    System.out.println(heading("Extracting MUT candidates"));
    SpoonAccessor spoonAccessor = new SpoonAccessor(projectPath);

    CandidateMethodExtractor candidateMethodExtractor = new CandidateMethodExtractor();
    for (CtModule module : spoonAccessor.getFactory().getModel().getAllModules()) {
      module.getRootPackage().accept(candidateMethodExtractor);
    }
    Set<RecordedMethod> candidateMuts = candidateMethodExtractor.getCandidates()
        .stream()
        .map(ExtractCandidateMethod::recordingCandidate)
        .map(RecordingCandidateMethod::toRecordedMethod)
        .collect(Collectors.toSet());

    System.out.println(heading("Finding production coverage"));
    List<RecordedMethod> coveredMethods = getCoveredProductionMethods(
        configPath, dataPath, projectPath, productionCommand
    );

    List<RecordedMethod> coveredMuts = coveredMethods.stream()
        .filter(candidateMuts::contains)
        .toList();

    System.out.println(heading("Covered methods (all)"));
    if (printAllCoveredMethods) {
      printCoveredMethods(coveredMethods);
    }
    System.out.println("A total of " + coveredMethods.size() + ".");
    System.out.println(heading("Covered methods (MUTs)"));
    if (printAllCoveredMethods) {
      printCoveredMethods(coveredMuts);
    }
    System.out.println("A total of " + coveredMuts.size() + ".");

    return coveredMuts;
  }

  private static List<RecordedMethod> getCoveredProductionMethods(
      Path configPath, Path dataPath, Path projectPath, String productionCommand
  ) throws IOException, InterruptedException {
    JacocoFacade facade = new JacocoFacade("production");
    return facade.getCoveredMethods(
        dataPath,
        projectPath,
        true,
        "bash",
        "-m",
        "-c",
        productionCommand
            .replace("{{config_dir}}", configPath.getParent().toAbsolutePath().toString())
    );
  }

  private static void printCoveredMethods(Collection<RecordedMethod> coveredMethods) {
    coveredMethods.stream()
        .map(RecordedMethod::fqnWithSignature)
        .sorted()
        .forEach(System.out::println);
  }

  private static Path resolveAgainstPath(Path base, Path path) {
    return base.resolve(path).toAbsolutePath().normalize();
  }

  private static void removeOldTests(Path testBasePath) throws IOException {
    try (Stream<Path> paths = Files.walk(testBasePath)) {
      for (Path path : paths.filter(it -> it.toString().endsWith("RockyTest.java")).toList()) {
        Files.delete(path);
      }
    }
  }

}
