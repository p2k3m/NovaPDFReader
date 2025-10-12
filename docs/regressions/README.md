# Regression documentation playbook

This folder tracks regressions that have affected NovaPDF Reader in the wild or in
continuous integration. When a regression is detected, add a new markdown file using
the template below so the reproduction steps and mitigation stay searchable.

## When to file a regression note

Create a regression document when one of the following is true:

- A connected test, macrobenchmark, or screenshot harness failure reproduces locally.
- A production crash or ANR is confirmed via Play Console, Crashlytics, or logcat
  attachments.
- A developer discovers a bug that requires a hotfix or dependency upgrade to
  prevent recurring instability.

## Information to capture

Each regression note should contain the following sections:

| Section | Required details |
| --- | --- |
| Summary | Date discovered, impacted area, observed symptom, and a single
  command-line snippet that reproduces the issue when possible. |
| Mitigation | Concrete code, configuration, or infrastructure changes that resolved
  the issue. Call out temporary mitigations (for example, feature flags) separately
  from permanent fixes. |
| Verification | Commands or tests that confirm the regression is no longer present.
  Include the exact Gradle/ADB invocations so teammates can rerun them. |
| Regression signals (optional) | Metrics, logcat excerpts, crash signatures, or
  screenshots collected while reproducing the failure. |

The [September 2024 Pdfium crash note](./2024-09-pdfium-crash.md) demonstrates the
expected level of detail.

## Authoring workflow

1. **Reproduce and log.** Capture logcat output while rerunning the failing test or
   harness. Store any interesting excerpts in the regression note. For connected
   tests, the helper script `tools/check_logcat_for_crashes.py` mirrors the CI
   watchdog and quickly highlights fatal signals or ANRs.
2. **Document the fix.** Summarize the code changes and link to relevant commits or
   pull requests once they merge. If the mitigation requires a follow-up task,
   reference the tracking issue number.
3. **Codify the verification.** Whenever possible, land a dedicated unit test,
   instrumentation test, or script so the regression stays detectable. Link to the
   new coverage in the note and mention how to run it. When regression coverage is
   unavailable (for example, device-specific hardware bugs), describe how to spot
   the failure in logs and how often to rerun the manual scenario.
4. **Keep artifacts lightweight.** Prefer textual descriptions, code snippets, and
   reproduction commands over large binary attachments. If screenshots are required,
   compress them and store them under `docs/regressions/assets/` with descriptive
   names.

## Suggested file naming

Use ISO-8601 dates and concise identifiers for new notes, for example:

```
2024-10-adaptive-flow-jank.md
2025-01-s3-certificate-rotation.md
```

Sorting by name keeps the folder chronological.

## Template

Copy the scaffold below into a new file to get started:

```markdown
# <Concise title>

## Summary

* **Date discovered:** <YYYY-MM-DD>
* **Area:** <Subsystem affected>
* **Symptom:** <User-visible breakage or CI failure>
* **Reproduction:**
  ```bash
  <command that consistently reproduces the issue>
  ```

## Mitigation

* <Bullet list of changes>

## Verification

* <Commands or tests that prove the regression is fixed>
```

Feel free to expand the template with additional sections (for example, "Related
metrics" or "Follow-up tasks") when relevant.
