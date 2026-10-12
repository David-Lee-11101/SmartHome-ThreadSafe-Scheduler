# Historical synchronization research

This directory preserves critical-section granularity, mutex, condition-variable, Redis Streams, SQL log-validation, and monitor experiments transferred from the companion detector repository. `PROVENANCE.json` binds the exact source commit, path, and Git tree hash.

These are isolated research assets. They are not imported by the supported scheduler and must be invoked explicitly with their optional dependencies and external configuration. No routine build connects to Redis, MySQL, a device, or a cloud service.

The `LLSC` name in the historical files describes optimistic SQL log validation. It is not hardware load-linked/store-conditional and is not a proof of lock-free progress. The monitor DAG is a research heuristic and does not establish unconditional deadlock freedom. Current-tree private machine paths were replaced by `research-data`; Git history was not rewritten.
