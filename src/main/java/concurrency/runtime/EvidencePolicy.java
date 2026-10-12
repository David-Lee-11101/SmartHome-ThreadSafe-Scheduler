package concurrency.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class EvidencePolicy {
    private final Set<String> supportedModelHashes;
    private final Set<String> supportedModelVersions;
    private final double minimumConfidence;
    private final Clock clock;

    public EvidencePolicy(Set<String> supportedModelHashes, Set<String> supportedModelVersions,
                          double minimumConfidence, Clock clock) {
        this.supportedModelHashes = Collections.unmodifiableSet(new HashSet<>(supportedModelHashes));
        this.supportedModelVersions = Collections.unmodifiableSet(new HashSet<>(supportedModelVersions));
        this.minimumConfidence = minimumConfidence;
        this.clock = clock;
    }

    public SchedulingDecision decide(SchedulePlan plan, ProfileEvidence evidence) {
        SchedulingDecision.Kind kind;
        String reason;
        List<String> order = Collections.emptyList();
        if (!supportedModelHashes.contains(evidence.getModelHash())
                || !supportedModelVersions.contains(evidence.getModelVersion())) {
            kind = SchedulingDecision.Kind.ABSTAIN;
            reason = "unsupported-profile-model";
        } else if (evidence.isAnomaly()) {
            kind = SchedulingDecision.Kind.DEFER;
            reason = "anomalous-traffic-profile";
        } else if (evidence.isAmbiguous() || "unknown-profile".equals(evidence.getLabel())
                || evidence.getConfidence() < minimumConfidence) {
            kind = SchedulingDecision.Kind.ABSTAIN;
            reason = "unknown-or-ambiguous-profile";
        } else {
            boolean conflict = false;
            for (ProfileEvidence.Pattern pattern : evidence.getPatterns()) {
                if ("conflict".equals(pattern.getKind()) && "violated".equals(pattern.getStatus())) conflict = true;
            }
            if (conflict && plan.getTasks().size() > 1) {
                order = topologicalOrder(plan, true);
                kind = SchedulingDecision.Kind.REORDER;
                reason = "conflict-profile-stable-reorder";
            } else {
                order = topologicalOrder(plan, false);
                kind = SchedulingDecision.Kind.PROCEED;
                reason = "trusted-profile";
            }
        }
        Instant now = clock.instant();
        String decisionId = "decision-" + Integer.toUnsignedString(
                (plan.getPlanId() + evidence.getProfileId() + kind.name()).hashCode(), 16);
        return new SchedulingDecision(decisionId, plan.getPlanId(), evidence.getProfileId(),
                kind, reason, order, now);
    }

    private static List<String> topologicalOrder(SchedulePlan plan, boolean reverseReadyTasks) {
        List<String> order = new ArrayList<>();
        Set<String> complete = new HashSet<>();
        while (order.size() < plan.getTasks().size()) {
            List<String> ready = new ArrayList<>();
            for (SchedulePlan.Task task : plan.getTasks()) {
                if (!complete.contains(task.getTaskId()) && complete.containsAll(task.getDependencies())) {
                    ready.add(task.getTaskId());
                }
            }
            Collections.sort(ready);
            if (reverseReadyTasks) Collections.reverse(ready);
            if (ready.isEmpty()) throw new IllegalArgumentException("task dependencies contain a cycle");
            order.addAll(ready);
            complete.addAll(ready);
        }
        return order;
    }
}
