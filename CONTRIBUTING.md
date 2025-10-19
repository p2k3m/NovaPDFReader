# Contributing to NovaPDF Reader

We rely on deterministic performance guardrails to keep the NovaPDF Reader
experience fast. Please read this guide before opening a pull request so that
baseline profile changes and macrobenchmark coverage stay consistent across the
team.

## Baseline profile maintenance

The release baseline profile (`app/src/main/baseline-prof.txt`) must be
regenerated whenever you make meaningful source or dependency changes that could
impact startup or scroll performance. Pull requests that touch Kotlin/Java
source, resources, Gradle scripts, or dependency versions are considered
performance-sensitive by default.

1. Boot a single emulator or hardware device that matches the configuration
   defined in `.github/workflows/ci-cd.yml` (`pixel-7-pro`, API level 32,
   8 GB+ RAM, SwiftShader GPU). Using different hardware introduces profile
   mismatches that the CI pipeline will flag.
2. Run the macrobenchmark suite to warm the device and exercise the end-to-end
   reader flows:
   ```sh
   ./gradlew :baselineprofile:connectedBenchmarkAndroidTest --stacktrace --no-build-cache
   ```
3. Regenerate the release baseline profile:
   ```sh
   ./gradlew :app:generateReleaseBaselineProfile --stacktrace --no-build-cache
   ```
4. Review and commit the updated `app/src/main/baseline-prof.txt`. The
   repository pre-commit hook and CI workflow both verify that performance
   changes are accompanied by a refreshed profile and will surface a diff if the
   file is stale.

CI reruns these steps on the dedicated emulator hardware and fails fast with the
full `git diff` when the generated profile diverges. If the failure occurs,
regenerate the profile locally, ensure all macrobenchmark scenarios cover your
new code paths, and push the updated baseline snapshot.

## Required macrobenchmark flows

The instrumentation in `baselineprofile/src/main/java` exercises realistic user
journeys such as opening stress-test documents, scrolling, invoking search, and
navigating chrome actions. The CI workflow calls
`tools/verify_macrobenchmark_coverage.py` to parse the instrumentation reports
and confirm that every benchmark test runs to completion. When adding new
features, update the existing scenarios so that the automation continues to
mirror the interactions real users perform.

## Git hooks

To enable the shared Git hooks, run the following command once after cloning the
repository:

```sh
git config core.hooksPath tools/git-hooks
```

The `pre-commit` hook wraps `tools/scripts/baseline_profile_guard.py` and blocks
commits that modify relevant application code without staging a refreshed
baseline profile. You can invoke the guard manually with
`python3 tools/scripts/baseline_profile_guard.py --staged --fail-when-outdated`
if you need to double-check the staged changes before committing.

## Abstraction reviews for caching, bitmap, PDF, and storage layers

Architectural changes to data flow are high risk for regressions. Whenever you
introduce a new caching, bitmap, PDF, or storage abstraction—or significantly
rewrite an existing one—open a design review and document the decision before
landing the code. Follow the checklist in
`docs/architecture/abstraction-review.md` and link the resulting document in
your pull request description so reviewers can verify that:

- The motivation and evaluated alternatives are captured.
- Failure modes and monitoring hooks are enumerated.
- The error surface exposed to callers is described along with mitigation
  strategies.
- Test coverage for happy paths and failure scenarios is listed.

Pull requests that add these abstractions without an accompanying review
document should be marked "changes requested" until the documentation is in
place.
