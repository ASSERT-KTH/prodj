package se.kth.castor.rockstofetch.cli;

import java.nio.file.Path;
import java.util.Optional;
import net.jbock.Command;
import net.jbock.Option;
import net.jbock.Parameter;

@Command(
    name = MainCliArgs.PROGRAM_NAME,
    description = {"A program to capture production data and generate corresponding test cases."}
)
public interface MainCliArgs {

  String PROGRAM_NAME = "\033[31mR\033[0mocks\033[31mT\033[0mo\033[31mF\033[0metch";

  @Parameter(
      index = 0,
      description = {"The path to the config"}
  )
  Path config();

  @Option(
      names = {"--hide-output", "-q"},
      description = "Hide script output"
  )
  boolean hideProgramOutput();

  @Option(
      names = {"--production-coverage"},
      description = "Do not instrument anything, only gather coverage data for production workload"
  )
  boolean productionCoverage();

  @Option(
      names = {"--print-covered-methods"},
      description = "Print all covered methods"
  )
  boolean printAllCoveredMethods();

  @Option(
      names = {"--statistics"},
      description = "Include serialization statistics in the output"
  )
  boolean statistics();

  @Option(
      names = {"--run-tests"},
      description = "Runs maven tests and reports"
  )
  Optional<Path> runTests();

}
