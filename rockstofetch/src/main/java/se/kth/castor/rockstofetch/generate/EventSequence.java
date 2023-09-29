package se.kth.castor.rockstofetch.generate;

import se.kth.castor.rockstofetch.generate.EventSequence.ObjectCreateEvent.ByConstructor;
import se.kth.castor.rockstofetch.generate.EventSequence.ObjectCreateEvent.ByStaticFactory;
import se.kth.castor.rockstofetch.generate.GenerationException.Type;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.CallMethodEndEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.CallMethodStartEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.CallMutatorEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.ConstructEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Event.SetFieldEvent;
import se.kth.castor.rockstofetch.instrument.MutationTracingContextHolder.Value;
import se.kth.castor.rockstofetch.util.Spoons;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.factory.Factory;

public class EventSequence {

  private final Map<Integer, ObjectContext> objects;
  private final Statistics statistics;

  private EventSequence(Map<Integer, ObjectContext> objects, Statistics statistics) {
    this.objects = Collections.unmodifiableMap(objects);
    this.statistics = statistics;

    if (statistics != null) {
      statistics.getTraceBased().setTotalConstructableObjects(objects.size());
    }
  }

  public ObjectContext getContext(int object, long timestamp) {
    ObjectContext context = objects.get(object);
    if (context == null) {
      if (statistics != null) {
        statistics.getTraceBased().addObjectNotFound();
      }
      throw new GenerationException(Type.REFERENCED_OBJECT_NOT_FOUND, "ID: " + object);
    }

    if (statistics != null) {
      statistics.getTraceBased().addObjectRequested(object);
    }
    return context.atTimestamp(timestamp);
  }

  public static EventSequence fromSequence(Stream<Event> events, Statistics statistics) {
    Map<Integer, ObjectParseContext> objects = new HashMap<>();
    InterestedMap interestedMap = new InterestedMap();

    try (events) {
      for (Event event : (Iterable<Event>) events::iterator) {
        if (event instanceof ConstructEvent constructEvent) {
          ObjectParseContext context = objects.computeIfAbsent(
              constructEvent.newObject(), ObjectParseContext::new
          );
          context.onConstructEvent(constructEvent, interestedMap);
        }
        if (event instanceof CallMethodStartEvent callStartEvent) {
          ObjectParseContext context = objects.get(callStartEvent.receiver());
          if (context != null) {
            context.onCallStart(callStartEvent);
          }

          // handle static factory calls
          if (callStartEvent.receiver() == -1) {
            interestedMap.pushStaticFactoryCall(callStartEvent);
          }
        }
        if (event instanceof SetFieldEvent fieldEvent) {
          for (var context : interestedMap.getInterested(fieldEvent.receiver())) {
            context.onSetFieldEvent(fieldEvent, interestedMap);
          }
        }
        if (event instanceof CallMutatorEvent callMutatorEvent) {
          for (var context : interestedMap.getInterested(callMutatorEvent.receiver())) {
            context.onMutatorCalled();
          }
        }
        if (event instanceof CallMethodEndEvent callEndEvent) {
          ObjectParseContext context = objects.get(callEndEvent.receiver());
          if (context != null) {
            context.onMethodEnd(callEndEvent);
          }

          // Handle static factory calls
          if (callEndEvent.receiver() == -1 && callEndEvent.returnedId() != null) {
            CallMethodStartEvent start = interestedMap.popStaticFactoryCall(
                callEndEvent.methodInvocationId()
            );
            ObjectParseContext returned = objects
                .computeIfAbsent(callEndEvent.returnedId(), ObjectParseContext::new);
            returned.onStaticFactoryMethod(start, callEndEvent.returnedClass());
          }
        }
      }
    }

    Map<Integer, ObjectContext> objectContexts = objects.values()
        .stream()
        .collect(Collectors.toMap(
            it -> it.id,
            ObjectParseContext::toFinalContext
        ));

    return new EventSequence(objectContexts, statistics);
  }

  public record ObjectContext(
      int id,
      ObjectCreateEvent createEvent,
      List<CallMethodStartEvent> mutatorCalls
  ) {

    public ObjectContext {
      mutatorCalls = List.copyOf(mutatorCalls);
    }

    public ObjectContext atTimestamp(long timestamp) {
      return new ObjectContext(
          id,
          createEvent,
          mutatorCalls.stream().filter(it -> it.timestamp() < timestamp).toList()
      );
    }
  }

  public sealed interface ObjectCreateEvent {

    long timestamp();

    String clazz();

    <T> CtAbstractInvocation<T> createCall(Factory factory);

    int newObject();

    List<Value> parameters();

    record ByConstructor(ConstructEvent event) implements ObjectCreateEvent {

      @Override
      public long timestamp() {
        return event.timestamp();
      }

      @Override
      public String clazz() {
        return event.clazz();
      }

      @Override
      public int newObject() {
        return event.newObject();
      }

      @Override
      public List<Value> parameters() {
        return event.parameters();
      }

      @Override
      public <T> CtAbstractInvocation<T> createCall(Factory factory) {
        return factory.createConstructorCall(factory.createReference(clazz()));
      }
    }

    record ByStaticFactory(
        CallMethodStartEvent event, int newObject, String newObjectClass
    ) implements ObjectCreateEvent {

      @Override
      public long timestamp() {
        return event.timestamp();
      }

      @Override
      public String clazz() {
        return newObjectClass;
      }

      @Override
      public List<Value> parameters() {
        return event.parameters();
      }

      @Override
      @SuppressWarnings("unchecked")
      public <T> CtAbstractInvocation<T> createCall(Factory factory) {
        CtMethod<T> called = (CtMethod<T>) Spoons.getCtMethod(factory, event.method());
        return factory.createInvocation(
            factory.createTypeAccess(factory.createReference(clazz())),
            called.getReference()
        );
      }
    }
  }

  private static class ObjectParseContext {

    private final int id;
    private final List<CallMethodStartEvent> mutatorCalls;
    private ObjectCreateEvent createEvent;
    private CallMethodStartEvent currentStartEvent;
    private boolean mutatorCalled;

    public ObjectParseContext(int id) {
      this.id = id;
      this.mutatorCalls = new ArrayList<>();
    }

    public ObjectContext toFinalContext() {
      return new ObjectContext(id, createEvent, mutatorCalls);
    }

    public void onConstructEvent(ConstructEvent event, InterestedMap interestedMap) {
      createEvent = new ByConstructor(event);
      for (int id : event.fieldIds()) {
        interestedMap.setInterested(this, id);
      }
      // You also care about setting fields on yourself!
      interestedMap.setInterested(this, id);
    }

    public void onCallStart(CallMethodStartEvent event) {
      if (currentStartEvent == null) {
        currentStartEvent = event;
      }
    }

    public void onSetFieldEvent(SetFieldEvent event, InterestedMap interestedMap) {
      if (currentStartEvent != null) {
        mutatorCalled = true;
      }
      if (event.oldValueId() != null) {
        interestedMap.setNotInterested(this, event.oldValueId());
      }
      if (event.newValueId() != null) {
        interestedMap.setInterested(this, event.newValueId());
      }
    }

    public void onMutatorCalled() {
      if (currentStartEvent == null) {
        return;
      }
      mutatorCalled = true;
    }

    public void onMethodEnd(CallMethodEndEvent event) {
      if (currentStartEvent == null) {
        return;
      }
      if (currentStartEvent.methodInvocationId() != event.methodInvocationId()) {
        return;
      }
      if (mutatorCalled) {
        mutatorCalls.add(currentStartEvent);
      }
      mutatorCalled = false;
      currentStartEvent = null;
    }

    public void onStaticFactoryMethod(CallMethodStartEvent event, String newObjectClass) {
      // We might not capture constructors of all types (e.g. because they are local)
      if (this.createEvent != null && this.createEvent.timestamp() <= event.timestamp()) {
        // We were constructed before, this method did not create us. It just returned us
        return;
      }
      this.createEvent = new ByStaticFactory(event, id, newObjectClass);
      // They will be replayed by the static factory method call
      this.mutatorCalls.clear();
    }
  }

  private record InterestedMap(
      Map<Integer, Collection<ObjectParseContext>> interestedMap,
      Map<Integer, CallMethodStartEvent> pendingStaticFactoryCalls
  ) {

    public InterestedMap() {
      this(new HashMap<>(), new HashMap<>());
    }

    public Collection<ObjectParseContext> getInterested(int id) {
      return interestedMap.getOrDefault(id, List.of());
    }

    public void setInterested(ObjectParseContext context, int id) {
      interestedMap.computeIfAbsent(id, ignored -> new ArrayList<>())
          .add(context);
    }

    public void setNotInterested(ObjectParseContext context, int id) {
      Collection<ObjectParseContext> contexts = interestedMap.get(id);
      if (contexts == null) {
        return;
      }
      contexts.remove(context);
    }

    public void pushStaticFactoryCall(CallMethodStartEvent event) {
      pendingStaticFactoryCalls.put(event.methodInvocationId(), event);
    }

    public CallMethodStartEvent popStaticFactoryCall(int methodInvocationId) {
      return pendingStaticFactoryCalls.remove(methodInvocationId);
    }

  }

}
