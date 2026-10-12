# Integration contract baseline

This component consumes catalog `1.0.1` from the private integration Hub:

- stable repository ID: `1415109155`
- exact commit: `ff569fdb97a7ad1f2a33665bd3eaceb1bcd28075`
- `contracts/INTEGRATION_API.md` SHA-256: `a6b41f12ea80d2ea4872a34fd7c8b638ad6d4be19e14695ad0bd9dcb99a4365e`

The adapter accepts `SchedulePlan` and `ProfileEvidence` major version 1 and emits `SchedulingDecision` and `ExecutionEvent` version `1.0.1`. Shape validation against the canonical Hub schemas remains part of the Hub cold-source runner. A valid shape is not trusted evidence: the scheduler separately allowlists both model version and model hash.

Self-contained tests vendor two canonical fixtures from that commit:

- `schedule-plan.json`: `2a0ffc471dd17e173d400828e65e01deeab630da5ad3373c0d4b4459905ae34a`
- `profile-evidence.json`: `b3c783bcd998fdd0e5e1c9ac26083ba407b837317a8fe039f22397b2b37caf2e`
