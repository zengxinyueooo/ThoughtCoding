# Public evaluation protocol

This is a reproducible prospective design, not an experimental results report.
Use cli.py and USAGE.md. The runtime is v3; transfer scoring is v4.

- Five WriteTool requirements, two trials per arm: 20 trials.
- Three historical regression tasks, one trial per arm: 6 trials.
- Baseline versus availability of the same public normal-path compatibility checks.
- Same model, starting code, final private acceptance, regression, tool restrictions,
  time/iteration budget and historical budget estimate in both arms.
- Random interleaving with seed 20261029, two workers, 26 planned trials.
- Freeze inputs/runtime before model calls; retain every result. Do not rerun for a better outcome.
- Calibrate base failure, reference success and applicable known-bad rejection using real Java tests.
- Final grading requires command success, complete expected test suites and private acceptance.
  Timeouts, skipped/missing tests and budget exhaustion are not successful delivery.
- Report the new requirements and historical regression cohorts separately.
- Retain incomplete telemetry as incomplete; missing usage is not zero.
- Local tools are restricted but this is not an operating-system sandbox.
- This is an internal, seen-module suite. No cross-module or statistically significant improvement claim.

Model, budget details, generated evidence and optional Langfuse upload steps are in USAGE.md.
The older protocol filenames are retained solely for source-freeze compatibility;
use cli.py rather than the historical batch entrypoints.
