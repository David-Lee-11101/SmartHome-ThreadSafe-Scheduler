package concurrency.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SchedulingDecision {
    public enum Kind { PROCEED, REORDER, DEFER, ABSTAIN }

    private final String decisionId;
    private final String planId;
    private final String profileEvidenceId;
    private final Kind decision;
    private final String reason;
    private final List<String> resolvedOrder;
    private final Instant persistedAt;

    public SchedulingDecision(String decisionId, String planId, String profileEvidenceId,
                              Kind decision, String reason, List<String> resolvedOrder,
                              Instant persistedAt) {
        this.decisionId = SchedulePlan.identifier(decisionId, "decisionId");
        this.planId = SchedulePlan.identifier(planId, "planId");
        this.profileEvidenceId = SchedulePlan.identifier(profileEvidenceId, "profileEvidenceId");
        this.decision = decision;
        if (reason == null || reason.isEmpty() || reason.length() > 256) {
            throw new IllegalArgumentException("reason must be bounded and non-empty");
        }
        this.reason = reason;
        this.resolvedOrder = Collections.unmodifiableList(new ArrayList<>(resolvedOrder));
        this.persistedAt = persistedAt;
        if (decision == null || persistedAt == null) throw new IllegalArgumentException("decision and persistedAt are required");
        boolean allowed = decision == Kind.PROCEED || decision == Kind.REORDER;
        if (allowed == this.resolvedOrder.isEmpty()) {
            throw new IllegalArgumentException("allowed decisions require order; blocked decisions require empty order");
        }
    }

    public String getDecisionId() { return decisionId; }
    public String getPlanId() { return planId; }
    public String getProfileEvidenceId() { return profileEvidenceId; }
    public Kind getDecision() { return decision; }
    public String getReason() { return reason; }
    public List<String> getResolvedOrder() { return resolvedOrder; }
    public Instant getPersistedAt() { return persistedAt; }
    public boolean allowsAction() { return decision == Kind.PROCEED || decision == Kind.REORDER; }
}
