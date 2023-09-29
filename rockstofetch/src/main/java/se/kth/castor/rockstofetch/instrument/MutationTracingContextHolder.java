package se.kth.castor.rockstofetch.instrument;

import static se.kth.castor.rockstofetch.util.Classes.className;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.google.common.collect.MapMaker;
import se.kth.castor.rockstofetch.instrument.CaptureContextHolder.MarkerObjectRefHandler;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.CallMethodEndEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.CallMethodStartEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.CallMutatorEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.ConstructEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.SetFieldEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.BooleanValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.ByteValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.CharacterValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.DoubleValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.FailedValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.FloatValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.IntegerValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.LongValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.NullValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.ReferenceValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.SerializedValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.ShortValue;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value.StringValue;
import se.kth.castor.rockstofetch.serialization.JavaSnippet;
import se.kth.castor.rockstofetch.serialization.Json;
import se.kth.castor.rockstofetch.serialization.RockySerializer;
import se.kth.castor.rockstofetch.serialization.Serializers;
import se.kth.castor.rockstofetch.util.ClosableLock;
import se.kth.castor.rockstofetch.util.SpoonAccessor;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionObjectReference;
import se.kth.castor.pankti.codemonkey.serialization.UnknownActionHandler;
import se.kth.castor.pankti.codemonkey.util.ClassUtil;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import se.kth.castor.pankti.codemonkey.util.Statistics.Mixed;
import se.kth.castor.rockstofetch.util.Classes;
import spoon.reflect.reference.CtTypeReference;

public class MutationTracingContextHolder {

  private static final RockySerializer CLEAN_SERIALIZER;
  private static final RockySerializer UNCLEANER_SERIALIZER;
  private static final AtomicInteger OBJECT_ID_COUNTER;
  private static final AtomicInteger INVOCATION_ID_COUNTER;
  private static final ConcurrentMap<Object, Integer> OBJECT_IDS;
  private static final ClosableLock EVENT_LOCK;
  private static final SpoonAccessor SPOON_ACCESSOR;
  private static long currentTime;
  private static final Json JSON;
  private static final AtomicReference<BlockingDeque<Event>> EVENT_QUEUE;

  static {
    try (var ignored = CaptureContextHolder.enterAgentCode()) {
      EVENT_LOCK = new ClosableLock(new ReentrantLock());
      OBJECT_ID_COUNTER = new AtomicInteger();
      INVOCATION_ID_COUNTER = new AtomicInteger();
      // Weak-keys cause identity key comparisons
      OBJECT_IDS = new MapMaker().weakKeys().makeMap();
      JSON = new Json();
      SPOON_ACCESSOR = CaptureContextHolder.SPOON_ACCESSOR;
      EVENT_QUEUE = new AtomicReference<>(new LinkedBlockingDeque<>(256));
      CLEAN_SERIALIZER = new RockySerializer(
          SPOON_ACCESSOR,
          Set.of(), Set.of(), Set.of(), UnknownActionHandler.fail(),
          CaptureContextHolder.UNIQUE_VARIABLE_SUFFIX,
          new FailStatisticSwallowStats(AgentMain.statistics)
      );
      UNCLEANER_SERIALIZER = new RockySerializer(
          SPOON_ACCESSOR,
          Set.of(),
          Set.of(),
          AgentMain.mutationTraceTypes,
          new UnknownActionHandler.SeparatingActions()
              .addHandler(ActionObjectReference.class, new MarkerObjectRefHandler()),
          CaptureContextHolder.UNIQUE_VARIABLE_SUFFIX,
          AgentMain.statistics
      );
      spawnWriter();
    }
  }

  public static void registerObjectCreation(
      long timestamp,
      Object newObject,
      List<Value> parameters,
      List<Object> fieldValues
  ) {
    if (!OBJECT_IDS.containsKey(newObject)) {
      OBJECT_IDS.put(newObject, OBJECT_ID_COUNTER.getAndIncrement());
    }
    List<Integer> fieldIds = fieldValues.stream()
        .filter(Objects::nonNull)
        .filter(
            it -> !(it instanceof Number) && !(it instanceof Character) && !(it instanceof Boolean)
                  && !(it instanceof String)
        )
        .map(it -> OBJECT_IDS.computeIfAbsent(it, ignored -> OBJECT_ID_COUNTER.getAndIncrement()))
        .toList();
    int newId = OBJECT_IDS.get(newObject);
    ConstructEvent event = new ConstructEvent(
        timestamp,
        getNextTimestamp(),
        newId,
        Classes.className(newObject.getClass()),
        parameters,
        fieldIds
    );
    persistEvent(event);
  }

  public static void registerSetField(Object receiver, Object newValue, Object oldValue) {
    long timestamp = getNextTimestamp();
    Integer receiverId = OBJECT_IDS.get(receiver);
    if (receiverId == null) {
      System.out.println("Field receiver was unknown " + receiver.getClass() + " @ " + timestamp);
      return;
    }
    Integer newValueId = OBJECT_IDS.get(newValue);

    // record the new value if it is a real object as we might call mutators on it
    if (newValueId == null && newValue != null
        && !ClassUtil.isBoxed(newValue.getClass()) && newValue.getClass() != String.class) {
      newValueId = OBJECT_ID_COUNTER.getAndIncrement();
      OBJECT_IDS.put(newValue, newValueId);
    }
    SetFieldEvent event = new SetFieldEvent(
        timestamp,
        receiverId,
        newValueId,
        OBJECT_IDS.get(oldValue)
    );
    persistEvent(event);
  }

  public static void registerCallMutator(Object receiver) {
    long timestamp = getNextTimestamp();
    Integer receiverId = OBJECT_IDS.get(receiver);
    if (receiverId == null) {
      System.out.println("Mutator receiver was unknown: " + receiver.getClass());
      return;
    }
    CallMutatorEvent event = new CallMutatorEvent(timestamp, receiverId);
    persistEvent(event);
  }

  public static int startMethodCall(Object receiver, Method method, List<Value> parameters) {
    long timestamp = getNextTimestamp();
    Integer receiverId = receiver == null ? Integer.valueOf(-1) : OBJECT_IDS.get(receiver);
    if (receiverId == null) {
      System.out.println("Method receiver was unknown " + receiver.getClass() + " @ " + timestamp);
      return -1;
    }
    CallMethodStartEvent event = new CallMethodStartEvent(
        timestamp,
        INVOCATION_ID_COUNTER.getAndIncrement(),
        receiverId,
        RecordedMethod.fromReflectMethod(method),
        parameters
    );
    persistEvent(event);
    return event.methodInvocationId();
  }

  public static void endMethodCall(int receiver, int invocationId, Object returned) {
    long timestamp = getNextTimestamp();
    // We only set this for static methods returning an instance, so it can never be a boxed value
    // or string.
    Integer returnId = returned == null ? null : getObjectId(returned);
    String returnedClass = returnId == null ? null : Classes.className(returned.getClass());
    CallMethodEndEvent event = new CallMethodEndEvent(
        receiver,
        timestamp,
        invocationId,
        returnId,
        returnedClass
    );
    persistEvent(event);
  }

  // Ordering between threads is not relevant, therefore we do not need to synchronize this access
  private static void persistEvent(Event event) {
    try {
      BlockingDeque<Event> queue = EVENT_QUEUE.getAcquire();
      if (queue != null) {
        queue.put(event);
      } else {
        synchronized (EVENT_QUEUE) {
          Files.writeString(
              AgentMain.dataPath.resolve("events.json"),
              JSON.toJson(event),
              StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE
          );
        }
      }
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static List<Value> getConstructorParams(Object[] parameters, Constructor<?> constructor) {
    if (constructor.getDeclaringClass().isAnonymousClass()
        || constructor.getDeclaringClass().isEnum()) {
      return List.of();
    }
    List<CtTypeReference<?>> types = SPOON_ACCESSOR.getParameterTypes(constructor);

    // First parameters might be implicit (captures, non-static inner classes)
    // Skip as many as needed to equal the count
    int realParamsOffset = parameters.length - types.size();
    List<Value> result = new ArrayList<>(types.size());
    for (int i = 0; i < types.size(); i++) {
      result.add(Value.fromObject(parameters[i + realParamsOffset], types.get(i)));
    }

    return result;
  }

  public static List<Value> getMethodParams(Object[] parameters, Method method) {
    List<CtTypeReference<?>> types = SPOON_ACCESSOR.getParameterTypes(method);

    List<Value> result = new ArrayList<>(types.size());
    for (int i = 0; i < types.size(); i++) {
      result.add(Value.fromObject(parameters[i], types.get(i)));
    }

    return result;
  }

  public static long getNextTimestamp() {
    try (var ignored = EVENT_LOCK.lock()) {
      return currentTime++;
    }
  }

  public static long getCurrentTime() {
    try (var ignored = EVENT_LOCK.lock()) {
      return currentTime;
    }
  }

  public static Integer getObjectId(Object o) {
    // Register it. The constructor might be called *after* `getObjectId`, as we can only get the
    // this instance at the end of the constructor invocation. We can then later on try to find the
    // corresponding construct event anyways...
    return OBJECT_IDS.computeIfAbsent(o, ignored -> OBJECT_ID_COUNTER.getAndIncrement());
  }

  @JsonTypeInfo(use = Id.NAME)
  @JsonSubTypes({
      @JsonSubTypes.Type(value = ConstructEvent.class, name = "cons"),
      @JsonSubTypes.Type(value = CallMethodStartEvent.class, name = "start"),
      @JsonSubTypes.Type(value = CallMethodEndEvent.class, name = "end"),
      @JsonSubTypes.Type(value = SetFieldEvent.class, name = "field"),
      @JsonSubTypes.Type(value = CallMutatorEvent.class, name = "mut"),
  })
  public sealed interface Event {

    long timestamp();

    record ConstructEvent(
        long timestamp,
        long endTimestamp,
        int newObject,
        String clazz,
        List<Value> parameters,
        List<Integer> fieldIds
    ) implements Event {

      @Override
      public String toString() {
        return "\033[34mConstruct[\n"
               + "  @\033[1;35m" + timestamp + " -> " + endTimestamp + "\033[34m,\n"
               + "  +\033[1;35m" + newObject + "\033[34m,\n"
               + "  t\033[1;35m" + clazz + "\033[34m,\n"
               + "  p\033[1;35m" + parameters + "\033[34m,\n"
               + "]\033[0m";
      }
    }

    record CallMethodStartEvent(
        long timestamp,
        int methodInvocationId,
        int receiver,
        RecordedMethod method,
        List<Value> parameters
    ) implements Event {

      @Override
      public String toString() {
        return "\033[34mCallMethodStart[\n"
               + "  @\033[1;35m" + timestamp + "\033[34m,\n"
               + "  i\033[1;35m" + methodInvocationId + "\033[34m,\n"
               + "  >\033[1;35m" + receiver + "\033[34m,\n"
               + "  c\033[1;35m" + method.methodName() + "\033[34m,\n"
               + "  p\033[1;35m" + parameters + "\033[34m,\n"
               + "]\033[0m";
      }
    }

    record CallMethodEndEvent(
        int receiver,
        long timestamp,
        int methodInvocationId,
        Integer returnedId,
        String returnedClass
    ) implements Event {

      @Override
      public String toString() {
        return "\033[34mCallMethodEnd[\n"
               + "  @\033[1;35m" + timestamp + "\033[34m,\n"
               + "  i\033[1;35m" + methodInvocationId + "\033[34m,\n"
               + "  >\033[1;35m" + receiver + "\033[34m,\n"
               + "  <\033[1;35m" + returnedId + "\033[34m,\n"
               + "]\033[0m";
      }

    }

    record SetFieldEvent(
        long timestamp,
        int receiver,
        Integer newValueId,
        Integer oldValueId
    ) implements Event {

      @Override
      public String toString() {
        return "\033[34mSetField[\n"
               + "  @\033[1;35m" + timestamp + "\033[34m,\n"
               + "  >\033[1;35m" + receiver + "\033[34m,\n"
               + "  n\033[1;35m" + newValueId + "\033[34m,\n"
               + "  o\033[1;35m" + oldValueId + "\033[34m,\n"
               + "]\033[0m";
      }
    }

    record CallMutatorEvent(
        long timestamp,
        int receiver
    ) implements Event {

      @Override
      public String toString() {
        return "\033[34mCallMutator[\n"
               + "  @\033[1;35m" + timestamp + "\033[34m,\n"
               + "  >\033[1;35m" + receiver + "\033[34m,\n"
               + "]\033[0m";
      }
    }
  }

  public interface PrimitiveValue {

    Object asValue();
  }

  public interface IdentityValue {

    Integer id();
  }

  @JsonTypeInfo(use = Id.NAME)
  @JsonSubTypes({
      @JsonSubTypes.Type(value = NullValue.class, name = "null"),
      @JsonSubTypes.Type(value = BooleanValue.class, name = "bool"),
      @JsonSubTypes.Type(value = CharacterValue.class, name = "char"),
      @JsonSubTypes.Type(value = ByteValue.class, name = "byte"),
      @JsonSubTypes.Type(value = ShortValue.class, name = "short"),
      @JsonSubTypes.Type(value = IntegerValue.class, name = "int"),
      @JsonSubTypes.Type(value = LongValue.class, name = "long"),
      @JsonSubTypes.Type(value = FloatValue.class, name = "float"),
      @JsonSubTypes.Type(value = DoubleValue.class, name = "double"),
      @JsonSubTypes.Type(value = StringValue.class, name = "string"),
      @JsonSubTypes.Type(value = FailedValue.class, name = "failed"),
      @JsonSubTypes.Type(value = SerializedValue.class, name = "ser"),
      @JsonSubTypes.Type(value = ReferenceValue.class, name = "ref"),
  })
  public sealed interface Value {

    static Value fromObject(Object o, CtTypeReference<?> type) {
      if (o == null) {
        return new NullValue(type.getQualifiedName());
      }
      if (o instanceof Boolean b) {
        return new BooleanValue(b);
      }
      if (o instanceof Character b) {
        return new CharacterValue(b);
      }
      if (o instanceof Byte b) {
        return new ByteValue(b);
      }
      if (o instanceof Short s) {
        return new ShortValue(s);
      }
      if (o instanceof Integer i) {
        return new IntegerValue(i);
      }
      if (o instanceof Long l) {
        return new LongValue(l);
      }
      if (o instanceof Float f) {
        return new FloatValue(f);
      }
      if (o instanceof Double d) {
        return new DoubleValue(d);
      }
      if (o instanceof String s) {
        return new StringValue(s);
      }

      if (o.getClass().isArray()) {
        if (Array.getLength(o) > 15) {
          return new FailedValue(o.getClass().getName() + "@" + Array.getLength(o));
        }
      }

      Integer id = OBJECT_IDS.get(o);
      JavaSnippet javaSnippet = getSnippet(o, type, CLEAN_SERIALIZER);
      if (!javaSnippet.statements().isEmpty()) {
        try {
          return new SerializedValue(
              Classes.className(o.getClass()),
              id,
              javaSnippet
          );
        } catch (Exception e) {
          System.out.println("FAIL FOR " + o.getClass() + " DUE TO ");
          e.printStackTrace();
          return new FailedValue(o.getClass().getName());
        }
      }
      if (id == null) {
        JavaSnippet snippet = getSnippet(o, type, UNCLEANER_SERIALIZER);
        if (!snippet.statements().isEmpty()) {
          return new SerializedValue(Classes.className(o.getClass()), null, snippet);
        }
        System.out.println("FAIL FOR " + o.getClass() + " AS NO ID");
        return new FailedValue(o.getClass().getName());
      }

      return new ReferenceValue(Classes.className(o.getClass()), id);
    }

    private static JavaSnippet getSnippet(
        Object o, CtTypeReference<?> type, RockySerializer serializer
    ) {
      JavaSnippet javaSnippet;
      if (type == null) {
        javaSnippet = Serializers.toSnippet(
            serializer, o, o.getClass(), "mutation-trace-probe"
        );
      } else {
        javaSnippet = Serializers.toSnippet(
            serializer, o, type, "mutation-trace-probe"
        );
      }
      return javaSnippet;
    }

    record NullValue(String clazz) implements Value, PrimitiveValue {

      @Override
      public Object asValue() {
        return null;
      }
    }

    record BooleanValue(boolean value) implements Value, PrimitiveValue {

      @Override
      public Object asValue() {
        return value;
      }
    }

    record CharacterValue(char value) implements Value, PrimitiveValue {

      @Override
      public Object asValue() {
        return value;
      }
    }

    record ByteValue(byte value) implements Value, PrimitiveValue {

      @Override
      public Object asValue() {
        return value;
      }
    }

    record ShortValue(short value) implements Value, PrimitiveValue {

      @Override
      public Object asValue() {
        return value;
      }
    }

    record IntegerValue(int value) implements Value, PrimitiveValue {

      @Override
      public Object asValue() {
        return value;
      }
    }

    record LongValue(long value) implements Value, PrimitiveValue {

      @Override
      public Object asValue() {
        return value;
      }
    }

    record FloatValue(float value) implements Value, PrimitiveValue {

      @Override
      public Object asValue() {
        return value;
      }
    }

    record DoubleValue(double value) implements Value, PrimitiveValue {

      @Override
      public Object asValue() {
        return value;
      }
    }

    record StringValue(String value) implements Value, PrimitiveValue {

      public StringValue {
        Objects.requireNonNull(value);
      }

      @Override
      public Object asValue() {
        return value;
      }
    }

    record ReferenceValue(String clazz, int referenceId) implements Value, IdentityValue {

      public ReferenceValue {
        Objects.requireNonNull(clazz);
      }

      @Override
      public Integer id() {
        return referenceId;
      }
    }

    record SerializedValue(String clazz, Integer id, JavaSnippet snippet)
        implements Value, IdentityValue {

      public SerializedValue {
        Objects.requireNonNull(clazz);
        Objects.requireNonNull(snippet);
      }
    }

    record FailedValue(String clazz) implements Value {

      public FailedValue {
        Objects.requireNonNull(clazz);
      }
    }

  }

  private static void spawnWriter() {
    AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    Thread thread = new Thread(drainEvents(shutdownRequested));
    thread.setName("event-writer");
    thread.setDaemon(true);
    thread.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        System.out.println("Shutting down " + thread.getName());
        EVENT_QUEUE.setRelease(null);
        shutdownRequested.set(true);
        thread.interrupt();
        thread.join(5000);
        System.out.println("Shutdown completed for " + thread.getName());
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }));
  }

  private static Runnable drainEvents(AtomicBoolean shutDown) {
    return () -> {
      try (var ignored = CaptureContextHolder.enterAgentCode()) {
        BlockingDeque<Event> queue = EVENT_QUEUE.get();

        synchronized (EVENT_QUEUE) {
          try (BufferedWriter writer = Files.newBufferedWriter(
              AgentMain.dataPath.resolve("events.json"),
              StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE
          )) {
            while (!shutDown.get() || !queue.isEmpty()) {
              try {
                Event event = queue.take();
                writer.write(JSON.toJson(event));
                writer.newLine();
              } catch (InterruptedException ignored1) {
                // just check again
                System.out.println(Thread.currentThread().getName() + " was interrupted");
              }
            }
          } catch (Throwable e) {
            e.printStackTrace();
            // Not much we can do...
            System.err.flush();
            Runtime.getRuntime().halt(1);
          }
        }

        System.out.println(
            Thread.currentThread().getName() + " is done. Queue size: " + queue.size()
        );
      }
    };
  }

  private static class FailStatisticSwallowStats extends Statistics {

    private final Statistics original;
    private final SuccessOnlyMixed successOnlyMixed;

    private FailStatisticSwallowStats(Statistics original) {
      this.original = original;
      this.successOnlyMixed = new SuccessOnlyMixed(original.getMixed());
    }

    @Override
    public Mixed getMixed() {
      return successOnlyMixed;
    }

    @Override
    public StructureBased getStructureBased() {
      return original.getStructureBased();
    }

    @Override
    public TraceBased getTraceBased() {
      return original.getTraceBased();
    }

    @Override
    public General getGeneral() {
      return original.getGeneral();
    }

    @Override
    public Processing getProcessing() {
      return original.getProcessing();
    }
  }

  private static class SuccessOnlyMixed extends Mixed {

    private final Mixed original;

    private SuccessOnlyMixed(Mixed original) {
      this.original = original;
    }

    @Override
    public void addBlacklisted() {
    }

    @Override
    public void addFailed(Class<?> clazz) {
    }

    @Override
    public void addOther() {
      original.addOther();
    }

    @Override
    public void addStructureSerializedInProd(Class<?> clazz) {
      original.addStructureSerializedInProd(clazz);
    }

    @Override
    public void addTraceSerializedInProd(Class<?> clazz) {
      original.addTraceSerializedInProd(clazz);
    }

    @Override
    public void addStandardCharsetSerializedInProd(Class<?> clazz) {
      original.addStandardCharsetSerializedInProd(clazz);
    }

    @Override
    public void addStaticFieldSerializedInProd(Class<?> clazz) {
      original.addStaticFieldSerializedInProd(clazz);
    }

    @Override
    public void addInternallySerializedInProd(Class<?> clazz) {
      original.addInternallySerializedInProd(clazz);
    }
  }

}
