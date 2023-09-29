package se.kth.castor.rockstofetch.generate;

import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event;
import se.kth.castor.rockstofetch.instrument.RecordedInvocation;
import se.kth.castor.rockstofetch.instrument.RecordedMockedInvocation;
import se.kth.castor.rockstofetch.instrument.RecordedNestedInvocation;
import se.kth.castor.rockstofetch.serialization.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class DataReader {

  public List<LoadedInvocation> loadInvocations(Path dir) throws IOException {
    Path invocationsPath = dir.resolve("invocations.json");
    Path mockedInvocationsPath = dir.resolve("mocked-invocations.json");
    Path nestedInvocationsPath = dir.resolve("nested-invocations.json");

    Json json = new Json();

    Map<UUID, LoadedInvocation> invocationMap = new HashMap<>();

    try (var lines = Files.lines(invocationsPath)) {
      for (String line : (Iterable<String>) lines::iterator) {
        RecordedInvocation invocation = Objects.requireNonNull(
            json.fromJson(line, RecordedInvocation.class)
        );
        invocationMap.put(invocation.invocationId(), new LoadedInvocation(invocation));
      }
    }

    if (Files.exists(nestedInvocationsPath)) {
      try (var lines = Files.lines(nestedInvocationsPath)) {
        for (String line : (Iterable<String>) lines::iterator) {
          RecordedNestedInvocation nestedInvocation = Objects.requireNonNull(
              json.fromJson(line, RecordedNestedInvocation.class)
          );
          invocationMap.get(nestedInvocation.parentInvocationId()).addNested(nestedInvocation);
        }
      }
    }

    if (Files.exists(mockedInvocationsPath)) {
      try (var lines = Files.lines(mockedInvocationsPath)) {
        for (String line : (Iterable<String>) lines::iterator) {
          RecordedMockedInvocation mockedInvocation = Objects.requireNonNull(
              json.fromJson(line, RecordedMockedInvocation.class)
          );

          invocationMap.get(mockedInvocation.parentInvocationId()).addMocked(mockedInvocation);
        }
      }
    }

    // Propagate nested mocked calls
    invocationMap.values().forEach(it -> propagateMockedToParents(invocationMap, it));

    return List.copyOf(invocationMap.values());
  }

  private void propagateMockedToParents(
      Map<UUID, LoadedInvocation> invocations,
      LoadedInvocation loadedInvocation
  ) {
    Optional<UUID> parentId = loadedInvocation.invocation().parentId();
    if (parentId.isEmpty()) {
      return;
    }
    UUID parent = parentId.get();

    LoadedInvocation parentInvocation = invocations.get(parent);
    if (parentInvocation == null) {
      return;
    }
    loadedInvocation.mockedInvocations()
        .stream()
        .filter(parentInvocation::hasMockedTarget)
        .forEach(parentInvocation::addMocked);

    propagateMockedToParents(invocations, parentInvocation);
  }

  /**
   * Reads events in a lazy stream. The caller needs to close the stream.
   *
   * @param dir the directory to read them from
   * @return a stream with all events
   * @throws IOException if the events file can not be opened
   */
  public Stream<Event> readEvents(Path dir) throws IOException {
    Json json = new Json();

    //noinspection resource
    return Files.lines(dir.resolve("events.json"))
        .map(line -> {
          try {
            return json.fromJson(line, Event.class);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  public record LoadedInvocation(
      RecordedInvocation invocation,
      List<RecordedNestedInvocation> nestedInvocations,
      List<RecordedMockedInvocation> mockedInvocations
  ) {

    public LoadedInvocation(RecordedInvocation invocation) {
      this(invocation, new ArrayList<>(), new ArrayList<>());
    }

    private void addNested(RecordedNestedInvocation nestedInvocation) {
      nestedInvocations().add(nestedInvocation);
    }

    private void addMocked(RecordedMockedInvocation mockedInvocation) {
      mockedInvocations().add(mockedInvocation);
    }

    private boolean hasMockedTarget(RecordedMockedInvocation mockedInvocation) {
      return invocation().hasTarget(mockedInvocation.targetId());
    }
  }

}
