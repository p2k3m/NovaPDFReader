# Baseline profile benchmark runs

This folder mirrors the pipeline used in CI to keep NovaPDF's shipped baseline
profile realistic. Each run directory under `runs/` contains the metrics and
profile generated from a full macrobenchmark execution:

1. `./gradlew :baselineprofile:connectedBenchmarkAndroidTest --stacktrace`
2. `./gradlew :app:generateReleaseBaselineProfile --stacktrace`

The instrumentation tests exercise cold startup, first-page render, stress
scrolling, and memory pressure scenarios before exporting aggregated metrics.
Immediately afterwards we copy the regenerated ART baseline profile into the run
folder for auditing.

The repository only keeps runs from the past 90 days. Invoke the retention helper
from the repository root whenever new results land:

```bash
python3 tools/scripts/retire_performance_results.py \
    --runs-dir docs/performance/baselineprofile/runs
```

Pass `--dry-run` to preview which directories would be removed and
`--retention-days` to override the default 90-day window when backfilling older
history.
