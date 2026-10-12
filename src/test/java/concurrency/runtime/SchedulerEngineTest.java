package concurrency.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerEngineTest {
    private static final String MODEL_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void persistsExactDecisionBeforeFirstAction() throws Exception {
        List<String> causalOrder = new ArrayList<>();
        SchedulerEngine.Result result = engine().execute(plan(), evidence(false, false, 0.9,
                        Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> true, (action, cancellation) -> causalOrder.add("action"),
                decision -> causalOrder.add("decision:" + decision.getProfileEvidenceId()),
                event -> { }, () -> false);
        assertEquals(Arrays.asList("decision:profile-known", "action", "action"), causalOrder);
        assertEquals(2, result.getCompletedActions());
    }

    @Test
    void persistenceFailurePreventsEveryAction() {
        AtomicInteger actions = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> engine().execute(plan(),
                evidence(false, false, 0.9, Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> true, (action, cancellation) -> actions.incrementAndGet(),
                decision -> { throw new IllegalStateException("disk unavailable"); },
                event -> { }, () -> false));
        assertEquals(0, actions.get());
    }

    @Test
    void falseConditionAndCancellationPreventActions() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        SchedulerEngine.Result falseCondition = engine().execute(singleTaskPlan(),
                evidence(false, false, 0.9, Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> false, (action, cancellation) -> actions.incrementAndGet(), decision -> { },
                event -> { }, () -> false);
        assertEquals(0, falseCondition.getCompletedActions());
        SchedulerEngine.Result cancelled = engine().execute(singleTaskPlan(),
                evidence(false, false, 0.9, Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> true, (action, cancellation) -> actions.incrementAndGet(), decision -> { },
                event -> { }, () -> true);
        assertEquals(0, cancelled.getCompletedActions());
        assertEquals(0, actions.get());
    }

    @Test
    void expiredDeadlineProducesOneTimedOutTerminalEvent() throws Exception {
        SchedulePlan expired = new SchedulePlan("plan-expired", clock.millis() - 1,
                Collections.singletonList(new SchedulePlan.Task("task-expired",
                        Collections.<String>emptyList(), Collections.<String>emptyList(),
                        Collections.singletonList("device-a"), action())));
        List<ExecutionEvent> events = new ArrayList<>();
        SchedulerEngine.Result result = engine().execute(expired,
                evidence(false, false, 0.9, Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> true, (action, cancellation) -> fail("expired task ran"),
                decision -> { }, events::add, () -> false);
        assertEquals(0, result.getCompletedActions());
        assertEquals(1, events.stream()
                .filter(event -> event.getState() == ExecutionEvent.State.TIMED_OUT).count());
    }

    @Test
    void incompleteDependencyPreventsDependentAction() throws Exception {
        SchedulePlan dependent = new SchedulePlan("plan-condition-chain", clock.millis() + 10_000,
                Arrays.asList(
                        new SchedulePlan.Task("task-first", Collections.<String>emptyList(),
                                Collections.<String>emptyList(), Collections.singletonList("device-a"), action()),
                        new SchedulePlan.Task("task-second", Collections.singletonList("task-first"),
                                Collections.<String>emptyList(), Collections.singletonList("device-b"), action())));
        AtomicInteger actions = new AtomicInteger();
        List<ExecutionEvent> events = new ArrayList<>();
        engine().execute(dependent, evidence(false, false, 0.9,
                        Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> !"task-first".equals(task.getTaskId()),
                (action, cancellation) -> actions.incrementAndGet(), decision -> { }, events::add, () -> false);
        assertEquals(0, actions.get());
        assertTrue(events.stream().anyMatch(event -> "DEPENDENCY_NOT_COMPLETED".equals(event.getErrorCode())));
    }

    @Test
    void unknownAmbiguousAndUnsupportedEvidenceAbstainWithoutAction() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        SchedulerEngine.Result ambiguous = engine().execute(plan(), evidence(false, true, 0.9,
                        Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> true, (action, cancellation) -> actions.incrementAndGet(), decision -> { }, event -> { }, () -> false);
        assertEquals(SchedulingDecision.Kind.ABSTAIN, ambiguous.getDecision().getDecision());
        ProfileEvidence unsupported = new ProfileEvidence("profile-unsupported", "model-v1",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "sensor-read", 0.9, false, false, Collections.<ProfileEvidence.Pattern>emptyList());
        SchedulerEngine.Result unknown = engine().execute(plan(), unsupported,
                (plan, task) -> true, (action, cancellation) -> actions.incrementAndGet(), decision -> { }, event -> { }, () -> false);
        assertEquals(SchedulingDecision.Kind.ABSTAIN, unknown.getDecision().getDecision());
        assertEquals(0, actions.get());
    }

    @Test
    void anomalyDefersAndValidConflictChangesOrder() throws Exception {
        SchedulerEngine.Result anomaly = engine().execute(plan(), evidence(true, false, 0.9,
                        Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> true, (action, cancellation) -> { }, decision -> { }, event -> { }, () -> false);
        assertEquals(SchedulingDecision.Kind.DEFER, anomaly.getDecision().getDecision());
        ProfileEvidence conflict = evidence(false, false, 0.9,
                Collections.singletonList(new ProfileEvidence.Pattern("conflict", "violated")));
        SchedulerEngine.Result reordered = engine().execute(plan(), conflict,
                (plan, task) -> true, (action, cancellation) -> { }, decision -> { }, event -> { }, () -> false);
        assertEquals(SchedulingDecision.Kind.REORDER, reordered.getDecision().getDecision());
        assertEquals(Arrays.asList("task-second", "task-first"), reordered.getDecision().getResolvedOrder());
    }

    @Test
    void staleConditionPreventsActionAfterLockAcquisition() throws Exception {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();
        List<ExecutionEvent> events = new ArrayList<>();
        engine().execute(singleTaskPlan(), evidence(false, false, 0.9,
                        Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> evaluations.incrementAndGet() == 1,
                (action, cancellation) -> actions.incrementAndGet(), decision -> { }, events::add, () -> false);
        assertEquals(0, actions.get());
        assertTrue(events.stream().anyMatch(event -> "STALE_CONDITION".equals(event.getErrorCode())));
    }

    @Test
    void callbackFailureEmitsOneFailedTerminalEventAndReleasesResource() {
        List<ExecutionEvent> events = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> engine().execute(singleTaskPlan(),
                evidence(false, false, 0.9, Collections.<ProfileEvidence.Pattern>emptyList()),
                (plan, task) -> true, (action, cancellation) -> { throw new IllegalStateException("private detail"); },
                decision -> { }, events::add, () -> false));
        assertEquals(1, events.stream().filter(event -> event.getState() == ExecutionEvent.State.FAILED).count());
        assertTrue(events.stream().noneMatch(event -> event.getErrorCode().contains("private detail")));
    }

    @Test
    void cancellationWhileWaitingIsBoundedAndReleasesPartialLocks() throws Exception {
        ResourceLockManager manager = new ResourceLockManager();
        ResourceLockManager.Lease held = manager.acquire(Collections.<String>emptyList(),
                Collections.singletonList("resource-b"), 100, () -> false);
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                manager.acquire(Collections.<String>emptyList(), Arrays.asList("resource-a", "resource-b"),
                        500, cancelled::get).close();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(50);
        cancelled.set(true);
        waiter.interrupt();
        waiter.join(1000);
        assertFalse(waiter.isAlive());
        held.close();
        manager.acquire(Collections.<String>emptyList(), Collections.singletonList("resource-a"),
                100, () -> false).close();
    }

    @Test
    void tokenOnlyCancellationReleasesPartialLocksPromptly() throws Exception {
        ResourceLockManager manager = new ResourceLockManager();
        ResourceLockManager.Lease held = manager.acquire(Collections.<String>emptyList(),
                Collections.singletonList("resource-b"), 100, () -> false);
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                manager.acquire(Collections.<String>emptyList(), Arrays.asList("resource-a", "resource-b"),
                        30_000, () -> { waiting.countDown(); return cancelled.get(); }).close();
            } catch (InterruptedException expected) {
                // Token cancellation should work without interrupting this worker.
            }
        });
        try {
            waiter.start();
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            cancelled.set(true);
            waiter.join(1000);
            assertFalse(waiter.isAlive(), "token cancellation must not wait for the full deadline");
            manager.acquire(Collections.<String>emptyList(), Collections.singletonList("resource-a"),
                    100, () -> false).close();
        } finally {
            waiter.interrupt();
            waiter.join(1000);
            held.close();
        }
    }

    @Test
    void resourceTimeoutDoesNotPoisonExecutingThread() throws Exception {
        ResourceLockManager manager = new ResourceLockManager();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (ResourceLockManager.Lease ignored = manager.acquire(Collections.<String>emptyList(),
                    Collections.singletonList("device-a"), 100, () -> false)) {
                held.countDown();
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        holder.start();
        try {
            assertTrue(held.await(1, TimeUnit.SECONDS));
            SchedulePlan timed = new SchedulePlan("plan-timeout", clock.millis() + 50,
                    singleTaskPlan().getTasks());
            SchedulerEngine executor = new SchedulerEngine(new EvidencePolicy(
                    new HashSet<>(Collections.singletonList(MODEL_HASH)),
                    new HashSet<>(Collections.singletonList("model-v1")), 0.8, clock), manager, clock);
            List<ExecutionEvent> events = new ArrayList<>();
            executor.execute(timed, evidence(false, false, 0.9,
                    Collections.<ProfileEvidence.Pattern>emptyList()), (plan, task) -> true,
                    (action, token) -> fail("blocked resource must not execute"), decision -> { },
                    events::add, () -> false);
            assertFalse(Thread.currentThread().isInterrupted());
            assertTrue(events.stream().anyMatch(event -> event.getState() == ExecutionEvent.State.TIMED_OUT));
        } finally {
            release.countDown();
            holder.join(1000);
        }
    }

    @Test
    void identifiersFollowAsciiContractGrammar() {
        assertThrows(IllegalArgumentException.class, () -> new SchedulePlan.Action(
                "服务-service", "set-state", Collections.<Object>emptyList()));
    }

    @Test
    void oppositeDeclarationOrderCannotDeadlockAndReadsCanOverlap() throws Exception {
        ResourceLockManager manager = new ResourceLockManager();
        CountDownLatch acquired = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable reader = () -> {
            try (ResourceLockManager.Lease ignored = manager.acquire(
                    Arrays.asList("resource-b", "resource-a"), Collections.<String>emptyList(), 500, () -> false)) {
                acquired.countDown();
                release.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = new Thread(reader);
        Thread second = new Thread(reader);
        first.start(); second.start();
        assertTrue(acquired.await(500, TimeUnit.MILLISECONDS));
        release.countDown();
        first.join(1000); second.join(1000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
    }

    @Test
    void cycleAndOverlappingResourcesFailBeforeExecution() {
        SchedulePlan.Action action = action();
        assertThrows(IllegalArgumentException.class, () -> new SchedulePlan("plan-cycle",
                clock.millis() + 10_000, Arrays.asList(
                new SchedulePlan.Task("task-a", Collections.singletonList("task-b"),
                        Collections.<String>emptyList(), Collections.singletonList("resource-a"), action),
                new SchedulePlan.Task("task-b", Collections.singletonList("task-a"),
                        Collections.<String>emptyList(), Collections.singletonList("resource-b"), action))));
        assertThrows(IllegalArgumentException.class, () -> new SchedulePlan.Task("task-overlap",
                Collections.<String>emptyList(), Collections.singletonList("resource-a"),
                Collections.singletonList("resource-a"), action));
    }

    @Test
    void conflictReorderPreservesDeclaredDependencies() throws Exception {
        SchedulePlan dependent = new SchedulePlan("plan-dependent", clock.millis() + 10_000, Arrays.asList(
                new SchedulePlan.Task("task-first", Collections.<String>emptyList(),
                        Collections.<String>emptyList(), Collections.singletonList("device-a"), action()),
                new SchedulePlan.Task("task-second", Collections.singletonList("task-first"),
                        Collections.<String>emptyList(), Collections.singletonList("device-b"), action())));
        ProfileEvidence conflict = evidence(false, false, 0.9,
                Collections.singletonList(new ProfileEvidence.Pattern("conflict", "violated")));
        SchedulerEngine.Result result = engine().execute(dependent, conflict, (plan, task) -> true,
                (action, cancellation) -> { }, decision -> { }, event -> { }, () -> false);
        assertEquals(Arrays.asList("task-first", "task-second"), result.getDecision().getResolvedOrder());
    }

    private SchedulerEngine engine() {
        EvidencePolicy policy = new EvidencePolicy(new HashSet<>(Collections.singletonList(MODEL_HASH)),
                new HashSet<>(Collections.singletonList("model-v1")), 0.8, clock);
        return new SchedulerEngine(policy, new ResourceLockManager(), clock);
    }

    private SchedulePlan plan() {
        return new SchedulePlan("plan-demo", clock.millis() + 10_000, Arrays.asList(
                new SchedulePlan.Task("task-first", Collections.<String>emptyList(),
                        Collections.singletonList("sensor-value"), Collections.singletonList("device-a"), action()),
                new SchedulePlan.Task("task-second", Collections.<String>emptyList(),
                        Collections.singletonList("sensor-value"), Collections.singletonList("device-b"), action())));
    }

    private SchedulePlan singleTaskPlan() {
        return new SchedulePlan("plan-single", clock.millis() + 10_000, Collections.singletonList(
                new SchedulePlan.Task("task-single", Collections.<String>emptyList(),
                        Collections.singletonList("sensor-value"), Collections.singletonList("device-a"), action())));
    }

    private SchedulePlan.Action action() {
        return new SchedulePlan.Action("device-service", "set-state", Collections.<Object>singletonList(true));
    }

    private ProfileEvidence evidence(boolean anomaly, boolean ambiguous, double confidence,
                                     List<ProfileEvidence.Pattern> patterns) {
        return new ProfileEvidence("profile-known", "model-v1", MODEL_HASH, "sensor-read",
                confidence, anomaly, ambiguous, patterns);
    }
}
