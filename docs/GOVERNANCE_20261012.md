# Bounded governance plan

Preserve pre-action decision persistence, stable lock ordering, reverse cleanup, evidence policy, callback ports, and historical experiments.

1. Lock cooperative cancellation behavior with a token-only wait regression that reacquires partially acquired resources from another thread.
2. Replace the full-deadline lock wait with bounded cancellation polling. Preserve external interrupt propagation and timeout cleanup; report timeout distinctly from actual interruption.
3. Lock ASCII identifier grammar with regression coverage and align runtime validation with the Hub contract.
4. Rerun Maven tests, package checks, and focused diff/static checks.

Fallback inventory: unsupported evidence abstention and anomaly deferral are grounded fail-safe paths covered by tests. No swallowed failures or compatibility bypass is added. No UI change or dependency addition.

## Independent review follow-up

Before further edits, add regressions for malformed typed action/condition values, unchecked evidence fields and enums, malformed versions/deadline coercion, and condition evaluation that cancels or consumes the action deadline. Reject duplicate JSON keys. Preserve validated typed wrappers for the transport bridge. Recheck cancellation/deadline immediately after the final condition callback.

## Atomic persistence review follow-up

The ordinary-move downgrade hides a lost persistence guarantee and is masking fallback slop. Add a narrow package-private atomic-move injection regression, assert the exception propagates, existing decision bytes remain intact, temporary file is removed, and scheduler actions stay at zero. Remove the downgrade; retain the required atomic move and fail closed when the filesystem cannot provide it.
