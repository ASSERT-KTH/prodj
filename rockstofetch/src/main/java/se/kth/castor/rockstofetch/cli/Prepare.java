package se.kth.castor.rockstofetch.cli;

import static java.util.function.Predicate.not;

import com.google.common.collect.Sets;
import se.kth.castor.rockstofetch.extract.CandidateMethodExtractor;
import se.kth.castor.rockstofetch.extract.ExtractCandidateMethod;
import se.kth.castor.rockstofetch.extract.MutationChainExtractor;
import se.kth.castor.rockstofetch.extract.RecordingConfiguration;
import se.kth.castor.rockstofetch.instrument.InstrumentationConfiguration;
import se.kth.castor.rockstofetch.instrument.RecordedMethod;
import se.kth.castor.rockstofetch.serialization.Json;
import se.kth.castor.rockstofetch.serialization.RockySerializer;
import se.kth.castor.rockstofetch.util.SpoonAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.serialization.UnknownActionHandler;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtModule;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypedElement;
import spoon.reflect.reference.CtTypeReference;

public class Prepare {

  public static int prepare(
      Path projectPath,
      Path methodsJson,
      Path dataPath,
      List<RecordedMethod> coveredMethods,
      Set<String> additionalInstrumentedPackages,
      Statistics statistics
  ) throws IOException {
    SpoonAccessor spoonAccessor = new SpoonAccessor(projectPath);

    CandidateMethodExtractor candidateMethodExtractor = new CandidateMethodExtractor();
    var serializer = new RockySerializer(
        spoonAccessor, Set.of(), Set.of(), Set.of(), UnknownActionHandler.fail(), statistics
    );

    for (CtModule module : spoonAccessor.getFactory().getModel().getAllModules()) {
      module.getRootPackage().accept(candidateMethodExtractor);
    }
    Set<String> coveredFqnsWithSignature = coveredMethods.stream()
        .map(RecordedMethod::fqnWithSignature)
        .collect(Collectors.toSet());
    List<ExtractCandidateMethod> candidates = candidateMethodExtractor.getCandidates().stream()
        .filter(it -> !coveredFqnsWithSignature.contains(
            it.recordingCandidate().toRecordedMethod().fqnWithSignature()
        ))
        .filter(it -> receiverStructureBasedIfVoid(serializer, it))
        .toList();

    System.out.printf(
        "Found %d candidates and %d total methods. Keeping %d after pruning.%n",
        candidateMethodExtractor.getCandidates().size(),
        candidateMethodExtractor.getTotalMethodCount(),
        candidates.size()
    );

    if (statistics != null) {
      statistics.getGeneral().setMuts(candidates.size());
    }

    MutationChainExtractor extractor = new MutationChainExtractor();
    Set<CtTypeReference<?>> potentialTraceRoots = new HashSet<>();
    candidates.stream()
        .map(it -> it.spoonMethod().getDeclaringType())
        .filter(it -> it instanceof CtClass<?>)
        .map(CtType::getReference)
        .forEach(potentialTraceRoots::add);
    candidates
        .stream()
        .map(ExtractCandidateMethod::spoonMethod)
        .flatMap(it -> it.getParameters().stream())
        .map(CtTypedElement::getType)
        .filter(Objects::nonNull)
        .forEach(potentialTraceRoots::add);

    potentialTraceRoots.stream()
        .filter(not(serializer::canSerializeStructurally))
        .map(CtTypeReference::getTypeDeclaration)
        .filter(Objects::nonNull)
        .forEach(extractor::trace);

    extractor.addInterfaceImplementations(spoonAccessor.getFactory());

    if (statistics != null) {
      updateStatistics(statistics, serializer, extractor, potentialTraceRoots);
    }

    RecordingConfiguration configuration = RecordingConfiguration.fromMethods(
        serializer, candidates, extractor
    );

    System.out.println();
    System.out.println("Forbidden as receiver:");
    configuration.getSerializationTypes().entrySet()
        .stream()
        .filter(it -> !it.getValue().isAllowedAsReceiver())
        .sorted(Entry.comparingByKey())
        .map(it -> "  " + it.getKey() + " (" + it.getValue() + ")")
        .forEach(System.out::println);

    Set<String> packagesToInstrument = spoonAccessor.getFactory()
        .getModel()
        .getAllPackages()
        .stream()
        .filter(not(CtPackage::isUnnamedPackage))
        .filter(CtPackage::hasTypes)
        .map(CtPackage::getQualifiedName)
        .collect(Collectors.toCollection(HashSet::new));
    if (additionalInstrumentedPackages != null) {
      packagesToInstrument.addAll(additionalInstrumentedPackages);
    }

    Files.writeString(
        methodsJson,
        new Json().prettyPrint(
            InstrumentationConfiguration.fromRecordingConfiguration(
                configuration, packagesToInstrument, projectPath, dataPath, statistics != null
            )
        )
    );

    return extractor.getToTrace().size();
  }

  private static void updateStatistics(
      Statistics statistics, RockySerializer serializer, MutationChainExtractor extractor,
      Set<CtTypeReference<?>> potentialTraceRoots
  ) {
    Set<CtTypeReference<?>> allTypes = Sets.union(extractor.getToTrace(), potentialTraceRoots);
    int structureBasedTypes = (int) allTypes.stream()
        .filter(serializer::canSerializeStructurally)
        .count();
    int traceBasedTypes = allTypes.size() - structureBasedTypes;

    statistics.getGeneral().setAssociatedTypeCount(allTypes.size());
    statistics.getGeneral().setTraceBasedTypes(traceBasedTypes);
    statistics.getGeneral().setStructureBasedTypes(structureBasedTypes);
  }

  private static boolean receiverStructureBasedIfVoid(
      RockySerializer serializer, ExtractCandidateMethod method
  ) {
    if (!method.spoonMethod().getType().getSimpleName().equals("void")) {
      return true;
    }
    if (!(method.spoonMethod().getDeclaringType() instanceof CtClass<?> ctClass)) {
      return true;
    }
    return ctClass.isAbstract() || serializer.canSerializeStructurally(ctClass.getReference());
  }
}
