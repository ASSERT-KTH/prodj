package se.kth.castor.rockstofetch.extract.coverage;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import se.kth.castor.rockstofetch.extract.coverage.JacocoFacade.JacocoCounter.Type;
import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.lang.constant.MethodTypeDesc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;
import spoon.MavenLauncher;
import spoon.MavenLauncher.SOURCE_TYPE;
import spoon.support.compiler.SpoonPom;

public class JacocoFacade {

  private static final String AGENT_URL = "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/0.8.8/org.jacoco.agent-0.8.8.jar";
  private static final String CLI_URL = "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/0.8.8/org.jacoco.cli-0.8.8-nodeps.jar";

  private static final String AGENT_FILE_NAME = "agent.jar";
  private static final String CLI_FILE_NAME = "cli.jar";
  private static final String DATA_FILE_NAME = "data.exec";
  public static final String REPORT_FILE_NAME = "report.xml";
  public static final String TAG_FILE_NAME = "tag";

  private final String tag;

  public JacocoFacade(String tag) {
    this.tag = tag;
  }

  /**
   * Returns all uncovered methods.
   *
   * @param dataPath the path to store data at
   * @param projectPath the path to the project to execute mvn test inside
   * @param forceRecreate if true existing data will be deleted and re-measured
   * @param command the production workload command to run. An empty array causes the default
   *     command to be run. You can use "{@literal {{agent_call}}}" as a placeholder.
   * @return all at least partially uncovered methods
   * @throws IOException if an error occurs downloading dependencies
   * @throws InterruptedException if an interrupt occurs during downloading
   */
  public List<RecordedMethod> getCoveredMethods(
      Path dataPath,
      Path projectPath,
      boolean forceRecreate,
      String... command
  ) throws IOException, InterruptedException {
    download(dataPath);
    System.out.println("Download complete");

    System.out.println("Testing...       ");

    if (Files.exists(dataPath.resolve(TAG_FILE_NAME))) {
      if (!Files.readString(dataPath.resolve(TAG_FILE_NAME)).strip().equals(tag)) {
        System.out.println("Tag differs, forcing recreation");
        forceRecreate = true;
      }
    } else {
      System.out.println("Tag not found, forcing recreation");
      forceRecreate = true;
    }
    Files.writeString(dataPath.resolve(TAG_FILE_NAME), tag);

    if (forceRecreate) {
      Files.deleteIfExists(dataPath.resolve(REPORT_FILE_NAME));
      Files.deleteIfExists(dataPath.resolve(DATA_FILE_NAME));
    }

    if (!Files.isRegularFile(dataPath.resolve(REPORT_FILE_NAME))) {
      if (command.length == 0) {
        command = new String[]{
            "mvn",
            "clean",
            "test",
            "package",
            "-DargLine='{{agent_call}}'",
            "-DsurefireArgLine='{{agent_call}}'",
            "-DcoverageArgs='{{agent_call}}'"
        };
      }
      command = Arrays.stream(command)
          .map(it -> it.replace("{{agent_call}}", getArgline(dataPath)))
          .toArray(String[]::new);
      System.out.println("\033[2m  Running " + String.join(" ", command) + "\033[0m");
      Process process = new ProcessBuilder(command)
          .directory(projectPath.toFile())
          .redirectError(Redirect.DISCARD)
          .redirectOutput(Redirect.DISCARD)
          .start();
      int exitStatus = process.waitFor();
      if (exitStatus != 0 && exitStatus != 130 /* SIGINT */) {
        throw new RuntimeException("Exit code: " + exitStatus);
      }
    }

    System.out.println("  Done");

    return parseReport(
        dataPath,
        projectPath
    );
  }

  private void download(Path directory) throws IOException, InterruptedException {
    Files.createDirectories(directory);

    HttpClient client = HttpClient.newHttpClient();

    if (Files.notExists(directory.resolve(AGENT_FILE_NAME))) {
      Path tempFile = Files.createTempFile("jacoco-agent", ".zip");
      HttpResponse<Path> response = client.send(
          HttpRequest.newBuilder(URI.create(AGENT_URL)).build(),
          BodyHandlers.ofFile(tempFile)
      );
      if (response.statusCode() != 200) {
        throw new IOException(
            "Failed to download jacoco agent. Got status " + response.statusCode()
        );
      }
      try (FileSystem fileSystem = FileSystems.newFileSystem(response.body())) {
        Files.copy(
            fileSystem.getPath("/jacocoagent.jar"),
            directory.resolve(AGENT_FILE_NAME),
            StandardCopyOption.REPLACE_EXISTING
        );
      }
      Files.deleteIfExists(tempFile);
    }

    if (Files.notExists(directory.resolve(CLI_FILE_NAME))) {
      HttpResponse<Path> response = client.send(
          HttpRequest.newBuilder(URI.create(CLI_URL)).build(),
          BodyHandlers.ofFile(directory.resolve(CLI_FILE_NAME))
      );
      if (response.statusCode() != 200) {
        throw new IOException("Failed to download jacoco cli. Got status " + response.statusCode());
      }
    }
  }

  private String getArgline(Path directory) {
    return "-javaagent:" + directory.resolve(AGENT_FILE_NAME).toAbsolutePath().normalize() + "="
           + "destfile=" + directory.resolve(DATA_FILE_NAME).toAbsolutePath().normalize()
           + ",dumponexit=true"
           + ",output=file";
  }

  private void convertReport(
      Path directory,
      Path projectDirectory,
      Path outputFile
  ) throws IOException, InterruptedException {
    if (Files.exists(outputFile)) {
      return;
    }
    List<String> command = new ArrayList<>(Arrays.asList(
        ProcessHandle.current().info().command().orElseThrow(),
        "-jar",
        directory.resolve(CLI_FILE_NAME).toAbsolutePath().normalize().toString(),
        "report",
        directory.resolve(DATA_FILE_NAME).toAbsolutePath().normalize().toString(),
        "--xml",
        outputFile.toAbsolutePath().normalize().toString()
    ));
    for (Path rawPath : getClassfileArguments(projectDirectory)) {
      Path fullPath = rawPath.resolve("target/classes");
      if (Files.exists(fullPath)) {
        command.add("--classfiles");
        command.add(fullPath.toAbsolutePath().normalize().toString());
      }
    }

    System.out.println("\033[2m  Running " + String.join(" ", command) + "\033[0m");
    int exitCode = new ProcessBuilder(
        command
    )
        .redirectError(Redirect.DISCARD)
        .redirectOutput(Redirect.DISCARD)
        .start()
        .waitFor();
    if (exitCode != 0) {
      throw new IOException("Failed to convert report: " + exitCode);
    }
  }

  private static Collection<Path> getClassfileArguments(Path projectDirectory) {
    SpoonPom pomFile = new MavenLauncher(
        projectDirectory.toAbsolutePath().toString(), SOURCE_TYPE.APP_SOURCE, Pattern.compile(".+")
    ).getPomFile();

    Set<Path> classFilePaths = new HashSet<>();
    Queue<SpoonPom> modules = new ArrayDeque<>();
    modules.add(pomFile);
    SpoonPom current;
    while ((current = modules.poll()) != null) {
      classFilePaths.add(Path.of(current.getPath()).getParent());
      modules.addAll(current.getModules());
    }
    return classFilePaths;
  }

  private List<RecordedMethod> parseReport(
      Path dataDirectory, Path projectDirectory
  ) throws IOException, InterruptedException {
    System.out.println("Parsing report...");
    Path outputFile = dataDirectory.resolve(REPORT_FILE_NAME);
    convertReport(dataDirectory, projectDirectory, outputFile);

    ObjectMapper mapper = new XmlMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    JsonNode tree = mapper.readTree(outputFile.toFile());
    List<JacocoPackage> packages = new ArrayList<>();

    for (JsonNode aPackage : tree.findValues("package")) {
      List<JsonNode> elements = new ArrayList<>();
      if (aPackage.isArray()) {
        aPackage.elements().forEachRemaining(elements::add);
      } else {
        elements.add(aPackage);
      }
      for (JsonNode element : elements) {
        packages.add(mapper.treeToValue(element, JacocoPackage.class));
      }
    }

    List<RecordedMethod> coveredMethods = new ArrayList<>();
    for (JacocoPackage aPackage : packages) {
      coveredMethods.addAll(aPackage.allCoveredMethods());
    }
    System.out.println("  Done");

    return coveredMethods;
  }

  record JacocoPackage(
      @JacksonXmlProperty(localName = "name", isAttribute = true) String name,
      @JsonProperty("class") List<JacocoClass> classes
  ) {

    public List<RecordedMethod> allCoveredMethods() {
      if (classes == null) {
        return List.of();
      }
      return classes.stream()
          .flatMap(it -> it.allCoveredMethods().stream())
          .toList();
    }

  }

  record JacocoClass(
      @JacksonXmlProperty(localName = "name", isAttribute = true) String name,
      @JacksonXmlProperty(localName = "sourcefilename", isAttribute = true) String sourceFileName,
      @JsonProperty(value = "method") List<JacocoMethod> methods
  ) {

    public List<RecordedMethod> allCoveredMethods() {
      if (methods == null) {
        return List.of();
      }
      return methods.stream()
          .filter(JacocoMethod::isCovered)
          .map(it -> RecordedMethod.fromType(name.replace("/", "."), it.name(), it.asMethodType()))
          .toList();
    }

  }

  record JacocoMethod(
      @JacksonXmlProperty(localName = "name", isAttribute = true) String name,
      @JacksonXmlProperty(localName = "desc", isAttribute = true) String desc,
      @JacksonXmlProperty(localName = "counter") List<JacocoCounter> counter
  ) {

    public MethodTypeDesc asMethodType() {
      return MethodTypeDesc.ofDescriptor(desc);
    }

    public boolean isCovered() {
      return counter.stream().noneMatch(it -> it.type() == Type.METHOD && it.missed() > 0);
    }

  }

  record JacocoCounter(
      @JacksonXmlProperty(localName = "type", isAttribute = true) Type type,
      @JacksonXmlProperty(localName = "missed", isAttribute = true) int missed,
      @JacksonXmlProperty(localName = "covered", isAttribute = true) int counter
  ) {

    public enum Type {
      INSTRUCTION, BRANCH, LINE, COMPLEXITY, METHOD
    }
  }


}
