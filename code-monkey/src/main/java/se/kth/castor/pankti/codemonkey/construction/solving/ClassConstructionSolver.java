package se.kth.castor.pankti.codemonkey.construction.solving;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;
import com.microsoft.z3.BoolSort;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Model;
import com.microsoft.z3.Optimize;
import com.microsoft.z3.Status;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import se.kth.castor.pankti.codemonkey.construction.actions.Action;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionObjectReference;
import se.kth.castor.pankti.codemonkey.construction.actions.ActionUseStaticFieldInstance;
import se.kth.castor.pankti.codemonkey.construction.solving.MutationStrategy.Result;
import se.kth.castor.pankti.codemonkey.util.Statistics;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtNamedElement;

public class ClassConstructionSolver {

  private final List<MutationStrategy> dynamicMutations;
  private final List<MutationStrategy> staticMutations;
  private final SolvingState initial;
  private final Supplier<Statistics> statisticsSupplier;

  public ClassConstructionSolver(
      List<MutationStrategy> mutations, SolvingState initial, Supplier<Statistics> statistics
  ) {
    this.dynamicMutations = mutations.stream().filter(it -> !it.isStatic()).toList();
    this.staticMutations = mutations.stream()
        .filter(MutationStrategy::isStatic)
        .toList();
    this.initial = initial;
    this.statisticsSupplier = statistics == null ? () -> null : statistics;
  }

  public SolveResult solveDynamic(Object instance) {
    Instant start = Instant.now();
    List<Action> potentialActions = new ArrayList<>();
    for (MutationStrategy mutation : dynamicMutations) {
      Result result = mutation.register(initial, instance);
      if (result.newState().isEmpty()) {
        continue;
      }
      SolvingState newState = result.newState().get();
      for (Action action : newState.actions()) {
        if (action.handledFields().size() != initial.fields().size()) {
          throw new IllegalArgumentException(
              "Dynamic mutation " + mutation + " did not handle all fields"
          );
        }
      }
      potentialActions.addAll(newState.actions());
    }

    Optional<Action> minimalAction = potentialActions.stream()
        .min(Comparator.comparingInt(Action::cost));

    Statistics statistics = this.statisticsSupplier.get();
    if (statistics != null && minimalAction.isPresent()) {
      Action action = minimalAction.get();
      if (action instanceof ActionUseStaticFieldInstance useStaticField) {
        if (useStaticField.field().getDeclaringType().getSimpleName().equals("StandardCharsets")) {
          statistics.getMixed().addStandardCharsetSerializedInProd(instance.getClass());
        } else {
          statistics.getMixed().addStaticFieldSerializedInProd(instance.getClass());
        }
      } else if (action instanceof ActionObjectReference) {
        statistics.getMixed().addTraceSerializedInProd(instance.getClass());
      } else {
        statistics.getMixed().addOther();
      }
    }
    if (statistics != null) {
      statistics.getStructureBased()
          .addTimeSpentDynamic(ChronoUnit.MILLIS.between(start, Instant.now()));
    }

    return new SolveResult(minimalAction.map(initial::withAction));
  }

  @SuppressWarnings({"unchecked", "UnstableApiUsage"})
  public SolveResult solveStatic() {
    Instant startTime = Instant.now();
    SolvingState current = initial;
    for (MutationStrategy mutation : staticMutations) {
      Result result = mutation.register(current, null);
      if (result.newState().isPresent()) {
        current = result.newState().get();
      }
      if (result.consideredDynamicMutation()) {
        throw new IllegalStateException("Mutation " + mutation + " produced dynamic result");
      }
    }

    if (current.actions().isEmpty()) {
      if (statisticsSupplier.get() != null) {
        statisticsSupplier.get().getStructureBased().addPlanBuiltFailed();
        statisticsSupplier.get()
            .getStructureBased()
            .addTimeSpentBuildingPlan(ChronoUnit.MILLIS.between(startTime, Instant.now()));
      }
      return new SolveResult(Optional.empty());
    }

    Map<Action, Expr<BoolSort>> actionToVars = new IdentityHashMap<>();

    try (Context context = new Context()) {
      Optimize optimize = context.mkOptimize();

      // Setup variables
      int counter = 0;
      for (Action action : current.actions()) {
        String name = action.getClass().getSimpleName() + counter++;
        actionToVars.put(action, context.mkBoolConst(name));
      }

      // Field creations:
      //   e.g. first => (constructor | assignFirst  | setFirst )
      for (CtField<?> field : current.fields()) {
        List<Expr<BoolSort>> settingActions = current.actions()
            .stream()
            .filter(it -> it.handledFields().contains(field))
            .map(actionToVars::get)
            .toList();

        if (!settingActions.isEmpty()) {
          optimize.Add(context.mkOr(settingActions.toArray(Expr[]::new)));
        } else {
          if (statisticsSupplier.get() != null) {
            statisticsSupplier.get().getStructureBased().addPlanBuiltFailed();
            statisticsSupplier.get()
                .getStructureBased()
                .addTimeSpentBuildingPlan(ChronoUnit.MILLIS.between(startTime, Instant.now()));
          }
          // Can't construct this field :/
          return new SolveResult(Optional.empty());
        }
      }

      // ensure an instance is created
      //   e.g. (constructor | defaultConstructor)
      Set<Action> constructingActions = current.actions()
          .stream()
          .filter(Action::constructsInstance)
          .collect(Collectors.toSet());
      optimize.Add(context.mkOr(
          constructingActions.stream()
              .map(actionToVars::get)
              .toArray(Expr[]::new)
      ));

      if (constructingActions.size() > 1) {
        // Only allow a single constructing action
        //  every possible pair has at most one set to true
        Sets
            .combinations(constructingActions, 2)
            .stream()
            .map(List::copyOf)
            .map(it -> context.mkNot(context.mkAnd(
                actionToVars.get(it.get(0)), actionToVars.get(it.get(1)))
            ))
            .forEach(optimize::Add);
      }

      // Cost of used elements
      var cost = context.mkAdd(
          current.actions()
              .stream()
              .map(action -> context.mkITE(
                  actionToVars.get(action),
                  context.mkInt(action.cost()),
                  context.mkInt(0)
              ))
              .toArray(Expr[]::new)
      );
      optimize.MkMinimize(cost);

      Status checkStatus = optimize.Check();
      if (checkStatus == Status.UNSATISFIABLE) {
        if (statisticsSupplier.get() != null) {
          statisticsSupplier.get().getStructureBased().addPlanBuiltFailed();
          statisticsSupplier.get()
              .getStructureBased()
              .addTimeSpentBuildingPlan(ChronoUnit.MILLIS.between(startTime, Instant.now()));
        }
        return new SolveResult(Optional.empty());
      } else if (checkStatus == Status.UNKNOWN) {
        throw new RuntimeException("Unknown element found");
      }

      Model model = optimize.getModel();
      List<Action> usedActions = actionToVars.entrySet().stream()
          .filter(entry -> model.getConstInterp(entry.getValue()).isTrue())
          .map(Entry::getKey)
          // Move the ones needing an instance to the front
          .sorted(
              Comparator.comparing(Action::needsInstance)
                  .thenComparing(action -> action.handledFields()
                      .stream()
                      .map(CtNamedElement::getSimpleName)
                      .min(String::compareTo)
                      .orElse("N/A")
                  )
          )
          .toList();

      if (statisticsSupplier.get() != null) {
        statisticsSupplier.get().getStructureBased().addPlanBuiltSuccessful();
        statisticsSupplier.get()
            .getStructureBased()
            .addTimeSpentBuildingPlan(ChronoUnit.MILLIS.between(startTime, Instant.now()));
      }

      return new SolveResult(
          Optional.of(new SolvingState(current.fields(), usedActions, current.type()))
      );
    }
  }

  public static BiFunction<CtClass<?>, Object, Optional<SolvingState>> cached(
      BiFunction<CtClass<?>, Object, ClassConstructionSolver> solverFactory
  ) {
    return new BiFunction<>() {
      private final Cache<String, Optional<SolvingState>> staticCache = CacheBuilder.newBuilder()
          .maximumSize(6000)
          .build();

      @Override
      public Optional<SolvingState> apply(CtClass<?> ctClass, Object instance) {
        Optional<SolvingState> cachedStatic;
        try {
          cachedStatic = staticCache.get(
              ctClass.getQualifiedName(),
              () -> solverFactory.apply(ctClass, instance).solveStatic().state()
          );
        } catch (ExecutionException e) {
          throw new RuntimeException(e);
        }
        if (cachedStatic.isPresent()) {
          return cachedStatic;
        }

        return solverFactory.apply(ctClass, instance).solveDynamic(instance).state();
      }
    };
  }


  public record SolveResult(
      Optional<SolvingState> state
  ) {

  }

}
