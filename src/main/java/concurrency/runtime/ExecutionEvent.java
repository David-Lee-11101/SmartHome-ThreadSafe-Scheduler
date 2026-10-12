package concurrency.runtime;

import java.time.Instant;

public final class ExecutionEvent {
    public enum State { ACCEPTED, STARTED, COMPLETED, FAILED, CANCELLED, TIMED_OUT }

    private final String executionId;
    private final String decisionId;
    private final String taskId;
    private final long sequence;
    private final State state;
    private final String errorCode;
    private final Instant occurredAt;

    public ExecutionEvent(String executionId, String decisionId, String taskId, long sequence,
                          State state, String errorCode, Instant occurredAt) {
        this.executionId = executionId;
        this.decisionId = decisionId;
        this.taskId = taskId;
        this.sequence = sequence;
        this.state = state;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.occurredAt = occurredAt;
    }

    public String getExecutionId() { return executionId; }
    public String getDecisionId() { return decisionId; }
    public String getTaskId() { return taskId; }
    public long getSequence() { return sequence; }
    public State getState() { return state; }
    public String getErrorCode() { return errorCode; }
    public Instant getOccurredAt() { return occurredAt; }
}
