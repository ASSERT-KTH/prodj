package se.kth.castor.rockstofetch.instrument;

import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import se.kth.castor.rockstofetch.serialization.Json;
import se.kth.castor.rockstofetch.serialization.RockySerializer;
import se.kth.castor.rockstofetch.serialization.Serializers;
import se.kth.castor.rockstofetch.util.Mocks;
import se.kth.castor.rockstofetch.util.SpoonAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import se.kth.castor.pankti.codemonkey.construction.actions.Action;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionMockObject;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionObjectReference;
import se.kth.castor.pankti.codemonkey.serialization.UnknownActionHandler;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

/**
 * Contains all the context needed for the current capturing session. All context is kept in static
 * fields as it needs to be accessible from all kinds of objects.
 */
public class CaptureContextHolder {

  // The current call stack, local to each thread
  private static final ThreadLocal<Deque<Deque<ClaimedMethodEntry>>> currentInvocationId;
  private static final ThreadLocal<Boolean> IS_IN_AGENT_CODE;
  private static final Path PERSISTENCE_DIR;
  private static final Json JSON;
  static final AtomicInteger UNIQUE_VARIABLE_SUFFIX;
  static final SpoonAccessor SPOON_ACCESSOR;
  private static final RockySerializer SERIALIZER;

  static {
    IS_IN_AGENT_CODE = ThreadLocal.withInitial(() -> false);
    try (var ignored = enterAgentCode()) {
      currentInvocationId = ThreadLocal
          .withInitial(() -> {
            Deque<Deque<ClaimedMethodEntry>> deque = new ArrayDeque<>();
            deque.addFirst(new ArrayDeque<>());
            return deque;
          });
      PERSISTENCE_DIR = AgentMain.dataPath;
      JSON = new Json();
      UNIQUE_VARIABLE_SUFFIX = new AtomicInteger();
      SPOON_ACCESSOR = new SpoonAccessor(AgentMain.projectPath);
      SERIALIZER = new RockySerializer(
          SPOON_ACCESSOR,
          AgentMain.mockConstructTypes,
          AgentMain.fixmeConstructTypes,
          AgentMain.mutationTraceTypes,
          new UnknownActionHandler.SeparatingActions()
              .addHandler(ActionMockObject.class, new LookupMockHandler())
              .addHandler(ActionObjectReference.class, new MarkerObjectRefHandler()),
          UNIQUE_VARIABLE_SUFFIX,
          AgentMain.statistics
      );
    }
  }

  public static boolean isInAgentCode() {
    return IS_IN_AGENT_CODE.get();
  }

  public static AgentCodeGuard enterAgentCode() {
    AgentCodeGuard guard = new AgentCodeGuard(IS_IN_AGENT_CODE.get());
    IS_IN_AGENT_CODE.set(true);
    return guard;
  }

  public static void exitAgentCode(boolean previousValue) {
    IS_IN_AGENT_CODE.set(previousValue);
  }

  /**
   * Called when a new MUT was invoked. Registers it in the current stack.
   *
   * @param entry the context of this invocation
   */
  public static void pushMutInvocation(ClaimedMethodEntry entry) {
    currentInvocationId.get().getFirst().addFirst(entry);
  }

  /**
   * Pops a MUT from the current stack.
   *
   * @param expected the expected entry, symmetric to
   *     {@link #pushMutInvocation(ClaimedMethodEntry)}
   */
  public static void popMutInvocation(ClaimedMethodEntry expected) {
    ClaimedMethodEntry popped = currentInvocationId.get().getFirst().removeFirst();
    if (!expected.equals(popped)) {
      throw new RuntimeException("Pop did not match expected: " + popped + " vs " + expected);
    }
  }

  /**
   * Returns the MUT currently at the top of the stack. Will throw an exception if no MUT was
   * {@link #pushMutInvocation(ClaimedMethodEntry) pushed} yet.
   *
   * @return the corresponding entry
   */
  public static ClaimedMethodEntry getCurrentMut() {
    return currentInvocationId.get().getFirst().getFirst();
  }

  /**
   * Returns the currently effective MUT entry. This is equal to {@link #getCurrentMut()} if the
   * current stack is not empty. If the current stack is empty and this is not the first invocation,
   * it will return the last MUT in the previous stack instead and expect it to be present.
   *
   * @return the corresponding entry
   */
  public static ClaimedMethodEntry getEffectiveMappingParent() {
    // Our stack is empty, look at the stack before
    if (currentInvocationId.get().getFirst().isEmpty() && currentInvocationId.get().size() > 1) {
      return getSecond(currentInvocationId.get()).getLast();
    }

    return currentInvocationId.get().getFirst().peekFirst();
  }

  private static <T> T getSecond(Deque<T> queue) {
    var iterator = queue.iterator();
    iterator.next();
    return iterator.next();
  }

  /**
   * {@return the depth of all MUT stacks summed up. Can be used for indenting log messages.}
   */
  public static int peekMutDepth() {
    int depth = 0;
    Deque<Deque<ClaimedMethodEntry>> stacks = currentInvocationId.get();
    for (Deque<ClaimedMethodEntry> stack : stacks) {
      depth += stack.size();
    }
    return depth + stacks.size() - 1;
  }

  /**
   * {@return true if there is no MUT being recorded and therefore no nested/mocked calls should be
   * captured}
   */
  public static boolean nestedCapturingPaused() {
    return currentInvocationId.get().getFirst().isEmpty();
  }

  /**
   * Registers that a mocked invocation was called. Terminates the current stack and pushes a new
   * one.
   */
  public static void pushMockedInvocation() {
    currentInvocationId.get().addFirst(new ArrayDeque<>());
  }

  /**
   * Pops the current MUT stack and verifies it is empty (as all MUTs should have been popped
   * already when the mocked method returns).
   */
  public static void popMockedInvocation() {
    if (!currentInvocationId.get().removeFirst().isEmpty()) {
      throw new IllegalStateException("Popped non-empty mut stack");
    }
  }

  /**
   * Serialized an arbitrary object to a {@link JavaSnippet}.
   *
   * @param o the object to serialize
   * @param why a human-readable reason for <em>why</em> this object is being serialized (used
   *     for log messages)
   * @return the serialized {@link JavaSnippet}
   */
  public static JavaSnippet toSnippet(Object o, Class<?> targetType, String why) {
    return Serializers.toSnippet(SERIALIZER, o, targetType, why);
  }

  /**
   * Serialized an arbitrary object to a {@link JavaSnippet}.
   *
   * @param o the object to serialize
   * @param why a human-readable reason for <em>why</em> this object is being serialized (used
   *     for log messages)
   * @return the serialized {@link JavaSnippet}
   */
  public static JavaSnippet toSnippet(Object o, CtTypeReference<?> assignedType, String why) {
    return Serializers.toSnippet(SERIALIZER, o, assignedType, why);
  }

  public synchronized static void persistInvocation(RecordedInvocation recordedInvocation) {
    try {
      String json = JSON.toJson(recordedInvocation);
      if (!Files.exists(PERSISTENCE_DIR)) {
        Files.createDirectories(PERSISTENCE_DIR);
      }
      Files.writeString(
          PERSISTENCE_DIR.resolve("invocations.json"), json + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
      );
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized static void persistInvocation(RecordedNestedInvocation nestedInvocation) {
    try {
      String json = JSON.toJson(nestedInvocation);
      if (!Files.exists(PERSISTENCE_DIR)) {
        Files.createDirectories(PERSISTENCE_DIR);
      }
      Files.writeString(
          PERSISTENCE_DIR.resolve("nested-invocations.json"), json + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
      );
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized static void persistInvocation(RecordedMockedInvocation mockedInvocation) {
    try {
      String json = JSON.toJson(mockedInvocation);
      if (!Files.exists(PERSISTENCE_DIR)) {
        Files.createDirectories(PERSISTENCE_DIR);
      }
      Files.writeString(
          PERSISTENCE_DIR.resolve("mocked-invocations.json"), json + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
      );
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static List<JavaSnippet> getParameterSnippets(
      Object receiver,
      Object[] parameters,
      RecordedMethod recordedMethod
  ) {
    CtMethod<?> method = SPOON_ACCESSOR.getMethod(receiver.getClass(), recordedMethod);
    List<JavaSnippet> parameterSnippets = new ArrayList<>();
    for (int i = 0; i < recordedMethod.parameterTypes().size(); i++) {
      Object value = parameters[i];
      CtParameter<?> ctParameter = method.getParameters().get(i);
      parameterSnippets.add(toSnippet(value, ctParameter.getType(), "param"));
    }
    return parameterSnippets;
  }

  public static JavaSnippet getReturnValueSnippet(
      Object receiver,
      Object returned,
      RecordedMethod recordedMethod
  ) {
    CtMethod<?> method = SPOON_ACCESSOR.getMethod(receiver.getClass(), recordedMethod);
    return toSnippet(returned, method.getType(), "returned");
  }

  /**
   * An entry in the MUT stack. Represents an invocation of a MUT.
   *
   * @param invocationId the id of this invocation
   * @param method the method that was invoked
   * @param objectToIdMap the map from object to id. Used to identify objects
   * @param idToNameMap the map from id to name. Used to convert object ids back
   * @param nextId the next free id for the {@link #objectToIdMap}
   */
  public record ClaimedMethodEntry(
      UUID invocationId,
      RecordedMethod method,
      Map<Object, Integer> objectToIdMap,
      Map<Integer, String> idToNameMap,
      int nextId
  ) {

    public Integer getId(Object receiver) {
      return objectToIdMap.get(receiver);
    }

    public void registerObject(String name, Object object, int id) {
      idToNameMap.put(id, name);
      objectToIdMap.put(object, id);
    }
  }

  public record AgentCodeGuard(boolean wasInAgentCode) implements AutoCloseable {

    @Override
    public void close() {
      CaptureContextHolder.exitAgentCode(wasInAgentCode);
    }

  }


  /**
   * An {@link UnknownActionHandler} that looks up the id of the object and appends it to the
   * variable name. This allows the generation phase to figure out which calls were made on the
   * mocked object in the MUT stack and recreate their return values.
   */
  private static class LookupMockHandler implements UnknownActionHandler {

    private static final AtomicInteger NEW_MOCK_COUNTER = new AtomicInteger(1_000_000);

    @Override
    public CtStatement handleAction(
        Action action, CtTypeReference<?> assigned, String suggestedName, Object value
    ) {
      Factory factory = assigned.getFactory();
      ClaimedMethodEntry mappingParent = CaptureContextHolder.getEffectiveMappingParent();
      Integer id = mappingParent.getId(value);
      if (id == null) {
        id = NEW_MOCK_COUNTER.getAndIncrement();
        mappingParent.registerObject("mock:" + suggestedName, value, id);
      }
      // FIXME: Delete
      if (value != null) {
        try {
          Files.writeString(
              AgentMain.dataPath.resolve("mocked.txt"),
              value.getClass().getName() + "\n",
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND
          );
        } catch (IOException ex) {
          throw new RuntimeException(ex);
        }
      }

      return factory.createLocalVariable(
          assigned,
          suggestedName + "_" + id,
          Mocks.mock(factory, assigned.getTypeErasure())
      );
    }

  }

  /**
   * An {@link UnknownActionHandler} writing a marker value for object references.
   */
  static class MarkerObjectRefHandler implements UnknownActionHandler {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public CtStatement handleAction(
        Action action, CtTypeReference<?> assigned, String suggestedName, Object value
    ) {
      ActionObjectReference objectRef = (ActionObjectReference) action;
      Factory factory = assigned.getFactory();

      return factory.createLocalVariable(
          (CtTypeReference) assigned,
          suggestedName,
          factory.createLiteral("ref:" + objectRef.id() + "@" + objectRef.timestamp())
      );
    }

  }

}
