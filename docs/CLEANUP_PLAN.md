# Thread-safe Scheduler cleanup plan

## Behavior locked before refactoring

- Existing `RuleTreeTest`, `RuleSpecTest`, and `RealUserPriorityExecutorTest` preserve the current simulation behavior.
- The current eleven tests must pass before and after the new runtime core is introduced.

## Changes

1. Add an independent runtime core instead of mutating the historical simulation in place.
2. Validate acyclic plans and bounded identifiers before starting worker threads.
3. Acquire read/write resources in one stable total order and release in reverse order.
4. Evaluate conditions and revalidate them after lock acquisition.
5. Add cooperative cancellation, deadlines, bounded queues/waits, structured events, and cleanup on every terminal path.
6. Consume exact `ProfileEvidence`, persist a decision before actions, and abstain or defer safely.
7. Preserve transferred synchronization research under `experiments/historical-synchronization` with source provenance and no import-time external connections.
8. Rewrite public documentation around `SmartHome-ThreadSafe-Scheduler` with precise guarantees.

## Non-goals

- Rollback of an already committed physical action, starvation freedom, or universal deadlock freedom.
- Claiming the historical synchronization prototypes already implemented the new runtime guarantees.
