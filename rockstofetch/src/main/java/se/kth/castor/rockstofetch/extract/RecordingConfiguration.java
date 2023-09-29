package se.kth.castor.rockstofetch.extract;

import static se.kth.castor.rockstofetch.extract.ClassSerializationType.CONSTRUCT;
import static se.kth.castor.rockstofetch.extract.ClassSerializationType.MOCK;

import se.kth.castor.rockstofetch.serialization.RockySerializer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import spoon.reflect.declaration.CtTypeInformation;
import spoon.reflect.reference.CtTypeReference;

public class RecordingConfiguration {

  private final List<RecordingCandidateMethod> candidates;
  private final Map<String, ClassSerializationType> serializationTypes;
  private final Set<String> mutationTraceTypes;

  private RecordingConfiguration(
      List<RecordingCandidateMethod> candidates,
      Map<String, ClassSerializationType> serializationTypes,
      Set<String> mutationTraceTypes
  ) {
    this.candidates = candidates;
    this.serializationTypes = serializationTypes;
    this.mutationTraceTypes = mutationTraceTypes;
  }

  public List<RecordingCandidateMethod> getCandidates() {
    return Collections.unmodifiableList(candidates);
  }

  public Map<String, ClassSerializationType> getSerializationTypes() {
    return Collections.unmodifiableMap(serializationTypes);
  }

  public Set<String> getMutationTraceTypes() {
    return mutationTraceTypes;
  }

  public static RecordingConfiguration fromMethods(
      RockySerializer serializer,
      Collection<ExtractCandidateMethod> methods,
      MutationChainExtractor mutationChain
  ) {
    List<RecordingCandidateMethod> candidates = methods.stream()
        .map(ExtractCandidateMethod::recordingCandidate)
        .collect(Collectors.toCollection(ArrayList::new));

    Map<String, ClassSerializationType> serializationTypes = mutationChain.getToTrace()
        .stream()
        .collect(Collectors.toMap(
            CtTypeInformation::getQualifiedName,
            it -> classify(serializer, it)
        ));

    // We need to trace all, as they are needed by some trace based method
    Set<String> mutationTraceTypes = mutationChain.getToTrace()
        .stream()
        .map(CtTypeInformation::getQualifiedName)
        .collect(Collectors.toSet());

    return new RecordingConfiguration(candidates, serializationTypes, mutationTraceTypes);
  }

  private static ClassSerializationType classify(
      RockySerializer serializer,
      CtTypeReference<?> type
  ) {
    return serializer.canSerializeStructurally(type) ? CONSTRUCT : MOCK;
  }
}
