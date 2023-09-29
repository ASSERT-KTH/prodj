package se.kth.castor.pankti.codemonkey.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.util.Statistics.StatisticsDeserializer;
import se.kth.castor.pankti.codemonkey.util.Statistics.StatisticsSerializer;

@JsonSerialize(using = StatisticsSerializer.class)
@JsonDeserialize(using = StatisticsDeserializer.class)
public class Statistics {

  private final TraceBased traceBased;
  private final StructureBased structureBased;
  private final Mixed mixed;
  private final General general;
  private final Processing processing;

  private Statistics(
      TraceBased traceBased, StructureBased structureBased, Mixed mixed, General general,
      Processing processing
  ) {
    this.traceBased = traceBased;
    this.structureBased = structureBased;
    this.mixed = mixed;
    this.general = general;
    this.processing = processing;
  }

  public Statistics() {
    this.structureBased = new StructureBased();
    this.traceBased = new TraceBased();
    this.mixed = new Mixed();
    this.general = new General();
    this.processing = new Processing();
  }

  public StructureBased getStructureBased() {
    return structureBased;
  }

  public TraceBased getTraceBased() {
    return traceBased;
  }

  public Mixed getMixed() {
    return mixed;
  }

  public General getGeneral() {
    return general;
  }

  public Processing getProcessing() {
    return processing;
  }

  public void assimilateOther(Statistics other) {
    other.structureBased.merge(structureBased);
    other.mixed.merge(mixed);

    // Commented out sometimes for whatever reason?
    // Some bug collection statistics for both in parallel IIRC?
    other.traceBased.merge(traceBased);
    other.general.merge(general);
  }

  public static void addStatDuration(Statistics statistics, String name, Instant start) {
    if (statistics != null) {
      statistics.getGeneral().addDuration(name, Duration.between(start, Instant.now()));
    }
  }

  @Override
  public String toString() {
    return "Statistics{" +
           "traceBased=" + traceBased +
           ", structureBased=" + structureBased +
           ", mixed=" + mixed +
           '}';
  }

  public static class TraceBased {

    private final Map<Integer, Integer> requestedObjects;
    private int objectsNotFound;
    private int totalConstructableObjects;

    private TraceBased(
        Map<Integer, Integer> requestedObjects,
        int objectsNotFound,
        int totalConstructableObjects
    ) {
      this.requestedObjects = requestedObjects;
      this.objectsNotFound = objectsNotFound;
      this.totalConstructableObjects = totalConstructableObjects;
    }

    public TraceBased() {
      this.requestedObjects = new HashMap<>();
    }

    public void addObjectRequested(int id) {
      requestedObjects.merge(id, 1, Math::addExact);
    }

    public void setTotalConstructableObjects(int totalConstructableObjects) {
      this.totalConstructableObjects = totalConstructableObjects;
    }

    public void addObjectNotFound() {
      objectsNotFound++;
    }

    public void merge(TraceBased other) {
      other.requestedObjects.putAll(requestedObjects);
      other.objectsNotFound += objectsNotFound;
      other.totalConstructableObjects += totalConstructableObjects;
    }

    @Override
    public String toString() {
      return "TraceBased{" +
             "requestedObjects=" + requestedObjects +
             ", objectsNotFound=" + objectsNotFound +
             ", totalConstructableObjects=" + totalConstructableObjects +
             '}';
    }
  }

  public static class Mixed {

    private final Map<String, Integer> failedClasses;
    private final Map<String, Integer> successfulClasses;
    private final Map<Class<?>, SerializationType> knownClasses;
    private final AtomicInteger typesSerializedStructureInProd;
    private final AtomicInteger typesSerializedTraceInProd;
    private final AtomicInteger typesSerializedStaticField;
    private final AtomicInteger typesSerializedStandardCharset;

    private final AtomicInteger blacklisted;
    private final AtomicInteger structure;
    private final AtomicInteger failed;
    private final AtomicInteger staticField;
    private final AtomicInteger standardCharset;
    private final AtomicInteger other;
    private final AtomicInteger traceBased;
    private final LongAdder timeSpentSerializing;
    private final LongAdder objectsTimeWasSpentOn;

    public void merge(Mixed other) {
      failedClasses.forEach((key, value) -> other.failedClasses.merge(key, value, Math::addExact));
      successfulClasses.forEach(
          (key, value) -> other.successfulClasses.merge(key, value, Math::addExact)
      );
      other.knownClasses.putAll(knownClasses);
      other.blacklisted.addAndGet(blacklisted.get());
      other.structure.addAndGet(structure.get());
      other.failed.addAndGet(failed.get());
      other.staticField.addAndGet(staticField.get());
      other.standardCharset.addAndGet(standardCharset.get());
      other.other.addAndGet(this.other.get());
      other.traceBased.addAndGet(traceBased.get());
      other.timeSpentSerializing.add(timeSpentSerializing.sum());
      other.objectsTimeWasSpentOn.add(objectsTimeWasSpentOn.sum());
    }

    public Mixed() {
      this.staticField = new AtomicInteger();
      this.standardCharset = new AtomicInteger();
      this.traceBased = new AtomicInteger();
      this.other = new AtomicInteger();
      this.structure = new AtomicInteger();
      this.failed = new AtomicInteger();
      this.blacklisted = new AtomicInteger();
      this.typesSerializedStructureInProd = new AtomicInteger();
      this.typesSerializedTraceInProd = new AtomicInteger();
      this.typesSerializedStaticField = new AtomicInteger();
      this.typesSerializedStandardCharset = new AtomicInteger();
      this.knownClasses = new ConcurrentHashMap<>();
      this.failedClasses = new ConcurrentHashMap<>();
      this.successfulClasses = new ConcurrentHashMap<>();
      this.timeSpentSerializing = new LongAdder();
      this.objectsTimeWasSpentOn = new LongAdder();
    }

    private Mixed(
        int staticField, int standardCharset, int traceBased, int structure, int other,
        int failed, Map<String, Integer> failedClasses, Map<String, Integer> successfulClasses,
        int blacklisted, int typesSerializedStructureInProd, int typesSerializedTraceInProd,
        int typesSerializedStaticField, int typesSerializedStandardCharset,
        long timeSpentSerializing, long objectsTimeWasSpentOn
    ) {
      this();
      this.staticField.set(staticField);
      this.standardCharset.set(standardCharset);
      this.traceBased.set(traceBased);
      this.structure.set(structure);
      this.other.set(other);
      this.failed.set(failed);
      this.failedClasses.putAll(failedClasses);
      this.successfulClasses.putAll(successfulClasses);
      this.blacklisted.set(blacklisted);
      this.typesSerializedStructureInProd.set(typesSerializedStructureInProd);
      this.typesSerializedTraceInProd.set(typesSerializedTraceInProd);
      this.typesSerializedStaticField.set(typesSerializedStaticField);
      this.typesSerializedStandardCharset.set(typesSerializedStandardCharset);
      this.timeSpentSerializing.add(timeSpentSerializing);
      this.objectsTimeWasSpentOn.add(objectsTimeWasSpentOn);
    }

    public int getAllObjects() {
      return staticField.get()
             + standardCharset.get()
             + structure.get()
             + other.get()
             + traceBased.get();
    }

    public void addBlacklisted() {
      blacklisted.getAndIncrement();
    }

    public void addFailed(Class<?> clazz) {
      failedClasses.merge(clazz.getName(), 1, Math::addExact);
      failed.getAndIncrement();
    }

    public void addOther() {
      other.getAndIncrement();
    }

    public void addStructureSerializedInProd(Class<?> clazz) {
      knownClasses.putIfAbsent(clazz, SerializationType.STRUCTURE);
      structure.getAndIncrement();
      successfulClasses.merge(clazz.getName(), 1, Math::addExact);
    }

    public void addTraceSerializedInProd(Class<?> clazz) {
      knownClasses.putIfAbsent(clazz, SerializationType.TRACE);
      traceBased.getAndIncrement();
      successfulClasses.merge(clazz.getName(), 1, Math::addExact);
    }

    public void addStandardCharsetSerializedInProd(Class<?> clazz) {
      knownClasses.putIfAbsent(clazz, SerializationType.STANDARD_CHARSET);
      standardCharset.getAndIncrement();
      successfulClasses.merge(clazz.getName(), 1, Math::addExact);
    }

    public void addStaticFieldSerializedInProd(Class<?> clazz) {
      knownClasses.putIfAbsent(clazz, SerializationType.STATIC_FIELD);
      staticField.getAndIncrement();
      successfulClasses.merge(clazz.getName(), 1, Math::addExact);
    }

    private void setTypeCountersToKnownClasses() {
      if (knownClasses.isEmpty()) {
        return;
      }

      typesSerializedStructureInProd.set(0);
      typesSerializedTraceInProd.set(0);
      typesSerializedStaticField.set(0);
      typesSerializedStandardCharset.set(0);

      for (SerializationType value : knownClasses.values()) {
        switch (value) {
          case STRUCTURE -> typesSerializedStructureInProd.getAndIncrement();
          case TRACE -> typesSerializedTraceInProd.getAndIncrement();
          case STATIC_FIELD -> typesSerializedStaticField.getAndIncrement();
          case STANDARD_CHARSET -> typesSerializedStandardCharset.getAndIncrement();
        }
      }
    }

    public void addInternallySerializedInProd(Class<?> clazz) {
      successfulClasses.merge(clazz.getName(), 1, Math::addExact);
      if (knownClasses.containsKey(clazz)) {
        return;
      }
      SerializationType serializationType;
      if (clazz.isArray()) {
        Class<?> current = clazz;
        while (current.isArray()) {
          current = current.getComponentType();
        }

        serializationType = knownClasses.get(current);
      } else {
        serializationType = SerializationType.STRUCTURE;
      }

      if (serializationType == SerializationType.STANDARD_CHARSET) {
        addStandardCharsetSerializedInProd(clazz);
      } else if (serializationType == SerializationType.STATIC_FIELD) {
        addStaticFieldSerializedInProd(clazz);
      } else if (serializationType == SerializationType.STRUCTURE) {
        addStructureSerializedInProd(clazz);
      } else {
        addTraceSerializedInProd(clazz);
      }
    }

    public void addTimeSpentSerializing(long time) {
      this.timeSpentSerializing.add(time);
      this.objectsTimeWasSpentOn.increment();
    }

    enum SerializationType {
      STRUCTURE,
      TRACE,
      STATIC_FIELD,
      STANDARD_CHARSET
    }

  }

  public static class StructureBased {

    private final AtomicInteger basicallyPrimitiveCount;
    private final AtomicInteger plansBuiltSuccessful;
    private final AtomicInteger plansBuiltFailed;
    private final LongAdder timeSpentBuildingPlans;
    private final LongAdder objectsTimeBuildingPlansWasSpentOn;
    private final LongAdder timeSpentDynamic;
    private final LongAdder objectsTimeSpentDynamicWasSpentOn;

    public void merge(StructureBased other) {
      other.basicallyPrimitiveCount.addAndGet(basicallyPrimitiveCount.get());
      other.plansBuiltSuccessful.addAndGet(plansBuiltSuccessful.get());
      other.plansBuiltFailed.addAndGet(plansBuiltFailed.get());
      other.timeSpentBuildingPlans.add(timeSpentBuildingPlans.sum());
      other.objectsTimeBuildingPlansWasSpentOn.add(objectsTimeBuildingPlansWasSpentOn.sum());
      other.timeSpentDynamic.add(timeSpentDynamic.sum());
      other.objectsTimeSpentDynamicWasSpentOn.add(objectsTimeSpentDynamicWasSpentOn.sum());
    }

    public StructureBased() {
      this.plansBuiltFailed = new AtomicInteger();
      this.plansBuiltSuccessful = new AtomicInteger();
      this.basicallyPrimitiveCount = new AtomicInteger();
      this.timeSpentBuildingPlans = new LongAdder();
      this.objectsTimeBuildingPlansWasSpentOn = new LongAdder();
      this.timeSpentDynamic = new LongAdder();
      this.objectsTimeSpentDynamicWasSpentOn = new LongAdder();
    }

    private StructureBased(
        int basicallyPrimitiveCount,
        int plansBuiltSuccessful,
        int plansBuiltFailed,
        long timeSpentBuildingPlans,
        long objectsTimeBuildingPlansWasSpentOn,
        long timeSpentDynamic,
        long objectsTimeSpentDynamicWasSpentOn
    ) {
      this();
      this.basicallyPrimitiveCount.set(basicallyPrimitiveCount);
      this.plansBuiltSuccessful.set(plansBuiltSuccessful);
      this.plansBuiltFailed.set(plansBuiltFailed);
      this.timeSpentBuildingPlans.add(timeSpentBuildingPlans);
      this.objectsTimeBuildingPlansWasSpentOn.add(objectsTimeBuildingPlansWasSpentOn);
      this.timeSpentDynamic.add(timeSpentDynamic);
      this.objectsTimeSpentDynamicWasSpentOn.add(objectsTimeSpentDynamicWasSpentOn);
    }

    public void addBasicallyPrimitive() {
      basicallyPrimitiveCount.getAndIncrement();
    }

    public void addPlanBuiltSuccessful() {
      plansBuiltSuccessful.getAndIncrement();
    }

    public void addPlanBuiltFailed() {
      plansBuiltFailed.getAndIncrement();
    }

    public void addTimeSpentBuildingPlan(long ms) {
      timeSpentBuildingPlans.add(ms);
      objectsTimeBuildingPlansWasSpentOn.increment();
    }

    public void addTimeSpentDynamic(long ms) {
      timeSpentDynamic.add(ms);
      objectsTimeSpentDynamicWasSpentOn.increment();
    }

    public int getBasicallyPrimitive() {
      return basicallyPrimitiveCount.get();
    }
  }

  public static class General {

    private int associatedTypeCount;
    private int structureBasedTypes;
    private int traceBasedTypes;
    private int muts;
    private final AtomicInteger invokedMuts;
    private Map<String, Duration> durations;
    private Map<String, Long> durationCounts;

    public void merge(General other) {
      other.associatedTypeCount += associatedTypeCount;
      other.structureBasedTypes += structureBasedTypes;
      other.traceBasedTypes += traceBasedTypes;
      other.muts += muts;
      other.invokedMuts.addAndGet(invokedMuts.get());
      // TODO: Durations?
    }

    public General() {
      this.invokedMuts = new AtomicInteger();
      this.durations = new HashMap<>();
      this.durationCounts = new HashMap<>();
    }

    public General(
        int associatedTypeCount, int structureBasedTypes, int traceBasedTypes, int muts,
        int invokedMuts, Map<String, Duration> durations, Map<String, Long> durationCounts
    ) {
      this();
      this.associatedTypeCount = associatedTypeCount;
      this.structureBasedTypes = structureBasedTypes;
      this.traceBasedTypes = traceBasedTypes;
      this.muts = muts;
      this.invokedMuts.set(invokedMuts);
      this.durations = durations;
      this.durationCounts = durationCounts;
    }

    public void setAssociatedTypeCount(int associatedTypeCount) {
      this.associatedTypeCount = associatedTypeCount;
    }

    public void setStructureBasedTypes(int structureBasedTypes) {
      this.structureBasedTypes = structureBasedTypes;
    }

    public void setTraceBasedTypes(int traceBasedTypes) {
      this.traceBasedTypes = traceBasedTypes;
    }

    public void setMuts(int muts) {
      this.muts = muts;
    }

    public void addInvokedMut() {
      invokedMuts.getAndIncrement();
    }

    public void addDuration(String name, Duration duration) {
      this.durations.put(name, duration);
    }

    public void addToAveragedDuration(String name, Duration duration) {
      this.durations.merge(name, duration, Duration::plus);
      this.durationCounts.merge(name, 1L, Math::addExact);
    }

  }

  public static class Processing {

    private final AtomicInteger removedExceptions;
    private final AtomicInteger inlinedVariables;
    private final AtomicInteger inlineMethodEvents;
    private final AtomicInteger deduplicatedMethods;
    private final AtomicInteger rewrittenAssertions;

    public Processing() {
      this.removedExceptions = new AtomicInteger();
      this.inlinedVariables = new AtomicInteger();
      this.inlineMethodEvents = new AtomicInteger();
      this.deduplicatedMethods = new AtomicInteger();
      this.rewrittenAssertions = new AtomicInteger();
    }

    public Processing(
        int removedExceptions, int inlinedVariables, int inlineMethodEvents,
        int deduplicatedMethods,
        int rewrittenAssertions
    ) {
      this();
      this.removedExceptions.set(removedExceptions);
      this.inlinedVariables.set(inlinedVariables);
      this.inlineMethodEvents.set(inlineMethodEvents);
      this.deduplicatedMethods.set(deduplicatedMethods);
      this.rewrittenAssertions.set(rewrittenAssertions);
    }

    public void addRemovedException() {
      this.removedExceptions.getAndIncrement();
    }

    public void addInlinedVariable() {
      this.inlinedVariables.getAndIncrement();
    }

    public void addInlineMethodEvent() {
      this.inlineMethodEvents.getAndIncrement();
    }

    public void addDeduplicatedMethod() {
      this.deduplicatedMethods.getAndIncrement();
    }

    public void addAssertionRewritten() {
      this.rewrittenAssertions.getAndIncrement();
    }

  }

  static class StatisticsSerializer extends JsonSerializer<Statistics> {

    @Override
    public void serialize(Statistics value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      System.out.println("CALLED");
      gen.writeStartObject();

      gen.writeObjectFieldStart("structure");
      writeStructure(value, gen);
      gen.writeEndObject();

      gen.writeObjectFieldStart("trace");
      writeTrace(value, gen);
      gen.writeEndObject();

      gen.writeObjectFieldStart("mixed");
      writeMixed(value, gen);
      gen.writeEndObject();

      gen.writeObjectFieldStart("aggregated");
      writeAggregated(value, gen);
      gen.writeEndObject();

      gen.writeObjectFieldStart("general");
      writeGeneral(value, gen);
      gen.writeEndObject();

      gen.writeObjectFieldStart("processing");
      writeProcessing(value, gen);
      gen.writeEndObject();

      gen.writeEndObject();
    }

    private static void writeStructure(Statistics value, JsonGenerator gen) throws IOException {
      gen.writeNumberField(
          "basicallyPrimitiveCount", value.getStructureBased().basicallyPrimitiveCount.get()
      );
      gen.writeNumberField(
          "plansBuiltSuccessful", value.getStructureBased().plansBuiltSuccessful.get()
      );
      gen.writeNumberField("plansBuiltFailed", value.getStructureBased().plansBuiltFailed.get());
      gen.writeNumberField(
          "timeSpentBuildingPlans",
          value.getStructureBased().timeSpentBuildingPlans.sum()
      );
      gen.writeNumberField(
          "objectsTimeBuildingPlansWasSpentOn",
          value.getStructureBased().objectsTimeBuildingPlansWasSpentOn.sum()
      );
      gen.writeNumberField(
          "timeSpentDynamic",
          value.getStructureBased().timeSpentDynamic.sum()
      );
      gen.writeNumberField(
          "objectsTimeSpentDynamicWasSpentOn",
          value.getStructureBased().objectsTimeSpentDynamicWasSpentOn.sum()
      );
    }

    private static void writeTrace(Statistics value, JsonGenerator gen) throws IOException {
      gen.writeObjectField("requestedObjects", value.getTraceBased().requestedObjects);
      gen.writeNumberField("objectsNotFound", value.getTraceBased().objectsNotFound);
      gen.writeNumberField(
          "totalConstructableObjects", value.getTraceBased().totalConstructableObjects
      );
    }

    private static void writeMixed(Statistics value, JsonGenerator gen) throws IOException {
      value.getMixed().setTypeCountersToKnownClasses();

      gen.writeNumberField("staticField", value.getMixed().staticField.get());
      gen.writeNumberField("standardCharset", value.getMixed().standardCharset.get());
      gen.writeNumberField("traceBased", value.getMixed().traceBased.get());
      gen.writeNumberField("structure", value.getMixed().structure.get());
      gen.writeNumberField("other", value.getMixed().other.get());
      gen.writeNumberField("failed", value.getMixed().failed.get());
      gen.writePOJOField("failedClasses", value.getMixed().failedClasses);
      gen.writePOJOField("successfulClasses", value.getMixed().successfulClasses);
      gen.writeNumberField("blacklisted", value.getMixed().blacklisted.get());
      gen.writeNumberField(
          "typesSerializedStructureInProd", value.getMixed().typesSerializedStructureInProd.get()
      );
      gen.writeNumberField(
          "typesSerializedTraceInProd", value.getMixed().typesSerializedTraceInProd.get()
      );
      gen.writeNumberField(
          "typesSerializedStaticField", value.getMixed().typesSerializedStaticField.get()
      );
      gen.writeNumberField(
          "typesSerializedStandardCharset", value.getMixed().typesSerializedStandardCharset.get()
      );
      gen.writeNumberField("timeSpentSerializing", value.getMixed().timeSpentSerializing.sum());
      gen.writeNumberField("objectsTimeWasSpentOn", value.getMixed().objectsTimeWasSpentOn.sum());
    }

    private static void writeAggregated(Statistics value, JsonGenerator gen) throws IOException {
      TraceBased trace = value.getTraceBased();
      Mixed mixed = value.getMixed();
      int successfulSerialized = mixed.structure.get()
                                 + mixed.staticField.get()
                                 + mixed.standardCharset.get()
                                 + trace.requestedObjects.values()
                                     .stream()
                                     .mapToInt(Integer::intValue)
                                     .sum();
      gen.writeNumberField(
          "successfulSerialized",
          successfulSerialized
      );
      gen.writeNumberField(
          "successfulTraceBased",
          trace.requestedObjects.values().stream().mapToInt(Integer::intValue).sum()
      );
      gen.writeNumberField(
          "totalObjects",
          successfulSerialized + mixed.failed.get()
      );
    }

    private static void writeGeneral(Statistics value, JsonGenerator gen) throws IOException {
      General general = value.getGeneral();
      gen.writeNumberField("structureBasedTypes", general.structureBasedTypes);
      gen.writeNumberField("traceBasedTypes", general.traceBasedTypes);
      gen.writeNumberField("associatedTypeCount", general.associatedTypeCount);
      gen.writeNumberField("muts", general.muts);
      gen.writeNumberField("invokedMuts", general.invokedMuts.get());
      gen.writeObjectField(
          "durations",
          general.durations.entrySet()
              .stream()
              .map(it -> Map.entry(it.getKey(), it.getValue().toMillis()))
              .collect(Collectors.toMap(Entry::getKey, Entry::getValue))
      );
      gen.writeObjectField(
          "durationCounts",
          general.durationCounts
      );
    }

    private static void writeProcessing(Statistics value, JsonGenerator gen) throws IOException {
      gen.writeNumberField("removedExceptions", value.getProcessing().removedExceptions.get());
      gen.writeNumberField("inlinedVariables", value.getProcessing().inlinedVariables.get());
      gen.writeNumberField("inlineMethodEvents", value.getProcessing().inlineMethodEvents.get());
      gen.writeNumberField("deduplicatedMethods", value.getProcessing().deduplicatedMethods.get());
      gen.writeNumberField("rewrittenAssertions", value.getProcessing().rewrittenAssertions.get());
    }
  }

  static class StatisticsDeserializer extends JsonDeserializer<Statistics> {


    @Override
    public Statistics deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonNode root = ctxt.readTree(p);
      StructureBased structure = readStructure(root.get("structure"));
      TraceBased trace = readTrace(root.get("trace"), p);
      Mixed mixed = readMixed(ctxt, root.get("mixed"));
      General general = readGeneral(root.get("general"));
      Processing processing = readProcessing(root.get("processing"));

      return new Statistics(trace, structure, mixed, general, processing);
    }

    private static StructureBased readStructure(JsonNode node) {
      return new StructureBased(
          node.get("basicallyPrimitiveCount").asInt(),
          node.get("plansBuiltSuccessful").asInt(),
          node.get("plansBuiltFailed").asInt(),
          node.get("timeSpentBuildingPlans").asLong(),
          node.get("objectsTimeBuildingPlansWasSpentOn").asLong(),
          node.get("timeSpentDynamic").asLong(),
          node.get("objectsTimeSpentDynamicWasSpentOn").asLong()
      );
    }

    private static TraceBased readTrace(JsonNode node, JsonParser p) throws IOException {
      int totalConstructableObjects = node.get("totalConstructableObjects").asInt();
      int objectsNotFound = node.get("objectsNotFound").asInt();
      @SuppressWarnings("unchecked")
      Map<Integer, Integer> requestedObjects = (Map<Integer, Integer>) p.getCodec()
          .treeToValue(node.get("requestedObjects"), Map.class);

      return new TraceBased(requestedObjects, objectsNotFound, totalConstructableObjects);
    }

    @SuppressWarnings("unchecked")
    private static Mixed readMixed(DeserializationContext ctxt, JsonNode node) throws IOException {
      return new Mixed(
          node.get("staticField").asInt(),
          node.get("standardCharset").asInt(),
          node.get("traceBased").asInt(),
          node.get("structure").asInt(),
          node.get("other").asInt(),
          node.get("failed").asInt(),
          (Map<String, Integer>) ctxt.readTreeAsValue(node.get("failedClasses"), Map.class),
          (Map<String, Integer>) ctxt.readTreeAsValue(node.get("successfulClasses"), Map.class),
          node.get("blacklisted").asInt(),
          node.get("typesSerializedStructureInProd").asInt(),
          node.get("typesSerializedTraceInProd").asInt(),
          node.get("typesSerializedStaticField").asInt(),
          node.get("typesSerializedStandardCharset").asInt(),
          node.get("timeSpentSerializing").asLong(),
          node.get("objectsTimeWasSpentOn").asLong()
      );
    }

    private static General readGeneral(JsonNode node) {
      Map<String, Duration> convertedDurations = new HashMap<>();
      Iterator<Entry<String, JsonNode>> durations = node.get("durations").fields();
      while (durations.hasNext()) {
        Entry<String, JsonNode> next = durations.next();
        convertedDurations.put(next.getKey(), Duration.ofMillis(next.getValue().asLong()));
      }
      Map<String, Long> convertedDurationCounts = new HashMap<>();
      Iterator<Entry<String, JsonNode>> durationCounts = node.get("durationCounts").fields();
      while (durationCounts.hasNext()) {
        Entry<String, JsonNode> next = durations.next();
        convertedDurationCounts.put(next.getKey(), next.getValue().asLong());
      }

      return new General(
          node.get("associatedTypeCount").asInt(),
          node.get("structureBasedTypes").asInt(),
          node.get("traceBasedTypes").asInt(),
          node.get("muts").asInt(),
          node.get("invokedMuts").asInt(),
          convertedDurations,
          convertedDurationCounts
      );
    }

    private static Processing readProcessing(JsonNode node) {
      return new Processing(
          node.get("removedExceptions").asInt(),
          node.get("inlinedVariables").asInt(),
          node.get("inlineMethodEvents").asInt(),
          node.get("deduplicatedMethods").asInt(),
          node.get("rewrittenAssertions").asInt()
      );
    }

  }
}
