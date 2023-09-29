package se.kth.castor.rockstofetch.instrument;

import se.kth.castor.rockstofetch.extract.ClassSerializationType;
import se.kth.castor.rockstofetch.extract.NestedInvocation;
import se.kth.castor.rockstofetch.extract.RecordingConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record InstrumentationConfiguration(
    List<RecordedMethod> methods,
    List<RecordedMethod> nestedMethods,
    Map<String, ClassSerializationType> classTypes,
    Set<String> mutationTraceTypes,
    Set<String> packagesToInstrument,
    Path projectPath,
    Path dataPath,
    boolean collectStatistics
) {

  public InstrumentationConfiguration {
    methods = List.copyOf(methods);
    nestedMethods = List.copyOf(nestedMethods);
    classTypes = Map.copyOf(classTypes);
    mutationTraceTypes = Set.copyOf(mutationTraceTypes);
  }

  public boolean isMocked(RecordedMethod method) {
    return classTypes().get(method.declaringClassName()) == ClassSerializationType.MOCK;
  }

  public ClassSerializationType getType(RecordedMethod method) {
    return classTypes().get(method.declaringClassName());
  }

  public static InstrumentationConfiguration fromRecordingConfiguration(
      RecordingConfiguration configuration,
      Set<String> packagesToInstrument,
      Path projectPath,
      Path dataPath,
      boolean collectStatistics
  ) {
    List<RecordedMethod> methods =
        configuration.getCandidates().stream()
            .map(method -> new RecordedMethod(
                method.declaringClassName(),
                method.methodName(),
                method.parameterTypes()
            ))
            .toList();

    List<RecordedMethod> nestedMethods = configuration.getCandidates().stream()
        .flatMap(it -> it.nestedInvocations().stream())
        .map(NestedInvocation::toRecordedMethod)
        .distinct()
        .toList();

    return new InstrumentationConfiguration(
        methods,
        nestedMethods,
        configuration.getSerializationTypes(),
        configuration.getMutationTraceTypes(),
        packagesToInstrument,
        projectPath,
        dataPath,
        collectStatistics
    );
  }

}
