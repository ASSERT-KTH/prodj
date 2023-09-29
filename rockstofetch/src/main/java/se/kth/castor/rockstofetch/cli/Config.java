package se.kth.castor.rockstofetch.cli;

import java.nio.file.Path;
import java.util.Set;

public record Config(
    boolean ignoreCoverage,
    Path projectPath,
    Path methodsJson,
    Path dataPath,
    Path testBasePath,
    String productionCommand,
    Set<String> additionalInstrumentedPackages,
    EqualityFunction usedEquality,
    boolean filterTests
) {

  public enum EqualityFunction {
    DEEP_REFLECTIVE,
    ASSERT_J_DEEP,
    JUNIT
  }
}
