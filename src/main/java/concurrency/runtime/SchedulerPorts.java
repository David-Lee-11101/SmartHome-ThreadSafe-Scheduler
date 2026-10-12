package concurrency.runtime;

public final class SchedulerPorts {
    private SchedulerPorts() { }

    public interface ConditionEvaluator {
        boolean evaluate(SchedulePlan plan, SchedulePlan.Task task) throws Exception;
    }

    public interface ActionHandler {
        void invoke(SchedulePlan.Action action, CancellationToken cancellation) throws Exception;
    }

    public interface DecisionStore {
        void persist(SchedulingDecision decision) throws Exception;
    }

    public interface EventSink {
        void emit(ExecutionEvent event);
    }

    public interface CancellationToken {
        boolean isCancelled();
    }
}
