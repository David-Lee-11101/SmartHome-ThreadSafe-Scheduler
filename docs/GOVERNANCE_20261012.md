# Bounded governance plan

Preserve pre-action decision persistence, stable lock ordering, reverse cleanup, evidence policy, callback ports, and historical experiments.

1. Lock cooperative cancellation behavior with a token-only wait regression that reacquires partially acquired resources from another thread.
2. Replace the full-deadline lock wait with bounded cancellation polling. Preserve external interrupt propagation and timeout cleanup; report timeout distinctly from actual interruption.
3. Lock ASCII identifier grammar with regression coverage and align runtime validation with the Hub contract.
4. Rerun Maven tests, package checks, and focused diff/static checks.

Fallback inventory: unsupported evidence abstention and anomaly deferral are grounded fail-safe paths covered by tests. No swallowed failures or compatibility bypass is added. No UI change or dependency addition.
