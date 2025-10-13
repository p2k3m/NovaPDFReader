# Fallback Mode Shortcuts

NovaPDF Reader exposes three emergency fallback modes that can be toggled while the
viewer is running. Each shortcut disables higher-risk subsystems so engineers can
triage crashes, rendering bugs, or accessibility regressions without restarting the
entire app. Fallback modes are intentionally conservative and should only be used
when the default adaptive flow pipeline becomes unstable.

## Classic "safe" mode

* **What it does:** Shuts down adaptive rendering, custom gesture recognisers, and
  hardware compositing so the UI collapses to the text, inventory, and alert
  surfaces only. This mirrors the pre-adaptive flow experience and keeps all
  navigation reachable via the accessibility tree.
* **When to enable:** Use when a regression in adaptive flow, page tiling, or
  annotation synchronisation causes the main canvas to crash or stop responding.
  Classic mode guarantees that every critical action is still reachable through the
  fallback panels and TalkBack interactions.

## Simple legacy graphics mode

* **What it does:** Forces the viewer to render using the legacy bitmap pipeline
  without post-processing, animations, or sensor-driven parallax. Prefetch queues
  remain active, but tiles are decoded with conservative memory budgets and the UI
  uses static layout hints.
* **When to enable:** Useful when modern GPU effects or high-contrast compositing
  trigger device-specific bugs. Legacy mode keeps rendering deterministic so issues
  can be reproduced and logged without extra motion or adaptive effects.

## Full tutorial / mid-run recovery mode

* **What it does:** Immediately restarts the built-in tutorial and overlays the
  guided walkthrough on top of the current document. All background adaptive logic
  is suspended until the walkthrough completes, at which point normal operation
  resumes.
* **When to enable:** Trigger this mode when custom onboarding or contextual tips
  fail to initialise. The recovery tutorial ensures users still receive critical
  guidance and that telemetry captures a consistent fallback flow.

## Usage notes

* Fallback modes persist only for the active session. Relaunching the app restores
  the previous user preferences.
* Only one fallback can be active at a time. Triggering a different shortcut
  automatically clears the previously enabled mode.
* Logcat surfaces a `FallbackMode` tag whenever a shortcut is toggled. Include
  these entries when filing regression notes so the team can correlate symptoms
  with the active fallback state.
