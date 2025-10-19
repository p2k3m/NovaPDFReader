# Performance baselines

The `docs/performance` tree keeps the most recent macrobenchmark metrics and
baseline profile snapshots that ship with NovaPDF. Records in this directory are
backed by source-controlled artefacts so regressions can be reproduced years
later without depending on ephemeral CI storage. Each benchmark capture stores
three artefacts:

- `metadata.json` — provenance for the run (device, build variant, commit
  SHA, and notes about the scenario under test).
- `benchmarks.json` — aggregated cold start, steady-state scroll, render, and
  memory metrics gathered from the macrobenchmark suite.
- `baseline-prof.txt` — the ART baseline profile generated immediately after the
  benchmarks complete.

A helper script (`tools/scripts/retire_performance_results.py`) enforces a
90-day retention policy. When invoked it deletes run directories whose
`recorded_at` timestamp in `metadata.json` falls outside the retention window.
Developers should execute the script after landing fresh benchmark data to keep
this directory realistic and avoid stale historical profiles from lingering
indefinitely.

For additional context on how benchmark runs are produced, see
`docs/performance/baselineprofile/README.md`.
