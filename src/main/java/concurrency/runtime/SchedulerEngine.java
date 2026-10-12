package concurrency.runtime;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Executes only ready tasks; worker threads never wait for queued dependencies. */
public final class SchedulerEngine {
    private final EvidencePolicy policy;
    private final ResourceLockManager locks;
    private final Clock clock;

    public SchedulerEngine(EvidencePolicy policy, ResourceLockManager locks, Clock clock) {
        this.policy = policy;
        this.locks = locks;
        this.clock = clock;
    }

    public Result execute(SchedulePlan plan, ProfileEvidence evidence,
                          SchedulerPorts.ConditionEvaluator conditions,
                          SchedulerPorts.ActionHandler actions,
                          SchedulerPorts.DecisionStore decisions,
                          SchedulerPorts.EventSink events,
                          SchedulerPorts.CancellationToken cancellation) throws Exception {
        SchedulingDecision decision = policy.decide(plan, evidence);
        decisions.persist(decision); // Failure here is terminal: no action has started.
        if (!decision.allowsAction()) return new Result(decision, 0);
        if (actions == null) throw new IllegalStateException("ACTION_HANDLER_REQUIRED");

        Map<String, SchedulePlan.Task> byId = new HashMap<>();
        for (SchedulePlan.Task task : plan.getTasks()) byId.put(task.getTaskId(), task);
        AtomicLong sequence = new AtomicLong();
        String executionId = "execution-" + decision.getDecisionId().substring("decision-".length());
        int completed = 0;
        Set<String> completedTasks = new HashSet<>();
        for (String taskId : decision.getResolvedOrder()) {
            SchedulePlan.Task task = byId.get(taskId);
            emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.ACCEPTED, "");
            if (!completedTasks.containsAll(task.getDependencies())) {
                emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.CANCELLED,
                        "DEPENDENCY_NOT_COMPLETED");
                continue;
            }
            if (cancellation.isCancelled()) {
                emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.CANCELLED, "CANCELLED");
                break;
            }
            long remaining = plan.getDeadlineEpochMs() - clock.millis();
            if (remaining <= 0) {
                emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.TIMED_OUT, "DEADLINE_EXCEEDED");
                break;
            }
            if (!conditions.evaluate(plan, task)) {
                emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.CANCELLED, "CONDITION_FALSE");
                continue;
            }
            try (ResourceLockManager.Lease ignored = locks.acquire(task.getReadResources(), task.getWriteResources(),
                    remaining, cancellation)) {
                if (cancellation.isCancelled()) {
                    emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.CANCELLED, "CANCELLED");
                    break;
                }
                if (clock.millis() >= plan.getDeadlineEpochMs()) {
                    emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.TIMED_OUT,
                            "DEADLINE_EXCEEDED");
                    break;
                }
                if (!conditions.evaluate(plan, task)) {
                    emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.CANCELLED,
                            "STALE_CONDITION");
                    continue;
                }
                emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.STARTED, "");
                try {
                    actions.invoke(task.getAction(), cancellation);
                    completed++;
                    completedTasks.add(taskId);
                    emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.COMPLETED, "");
                } catch (Exception actionFailure) {
                    emit(events, executionId, decision, taskId, sequence, ExecutionEvent.State.FAILED,
                            "ACTION_CALLBACK_FAILED");
                    throw actionFailure;
                }
            } catch (InterruptedException interrupted) {
                if (!(interrupted instanceof ResourceLockManager.WaitAbortedException)) {
                    Thread.currentThread().interrupt();
                }
                emit(events, executionId, decision, taskId, sequence,
                        cancellation.isCancelled() ? ExecutionEvent.State.CANCELLED : ExecutionEvent.State.TIMED_OUT,
                        cancellation.isCancelled() ? "CANCELLED" : "RESOURCE_WAIT_TIMEOUT");
                break;
            }
        }
        return new Result(decision, completed);
    }

    private void emit(SchedulerPorts.EventSink sink, String executionId,
                      SchedulingDecision decision, String taskId, AtomicLong sequence,
                      ExecutionEvent.State state, String code) {
        sink.emit(new ExecutionEvent(executionId, decision.getDecisionId(), taskId,
                sequence.getAndIncrement(), state, code, clock.instant()));
    }

    public static final class Result {
        private final SchedulingDecision decision;
        private final int completedActions;
        Result(SchedulingDecision decision, int completedActions) {
            this.decision = decision;
            this.completedActions = completedActions;
        }
        public SchedulingDecision getDecision() { return decision; }
        public int getCompletedActions() { return completedActions; }
    }
}
