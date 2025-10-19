# Fallback modes

NovaPDF Reader exposes a handful of automatic fallbacks that downgrade high-risk
systems when startup or render stability is compromised. This document explains
what each fallback does, when it is activated, how engineers can toggle it
manually for debugging, and which user-visible signals confirm that the
fallback is in effect.

| Mode | Primary trigger(s) | Manual controls | User-facing signals |
| --- | --- | --- | --- |
| Legacy simple renderer | Render circuit breaker trips because repeated render faults or cache stress made the adaptive pipeline unstable. | Persist by writing `LEGACY_SIMPLE_RENDERER` to the `fallback_mode` preference via `UserPreferencesUseCase.setFallbackMode`. Clear it by storing `NONE` (or clearing the preference). | Snackbar message “Simple renderer fallback enabled for stability.” plus `fallback_mode:legacy_simple_renderer[:reason]` breadcrumbs. Compose UI is also replaced with the legacy layout. |
| Render cache fallback | Cache directories fail to initialise (permission issues, full storage, etc.). | Cannot be force-disabled while active; fix the underlying error and restart so caches initialise cleanly. Developers can only observe the locked toggle in the dev options panel. | Snackbar “Render caches failed to initialize. Performance may be degraded.”, cache toggle disabled with red hint text, and `render_cache_fallback:*` breadcrumbs/logcat entries. |
| Bitmap cache fallback | Runtime memory pressure (low-memory callbacks, TRIM events, or render `OutOfMemoryError`). | No direct control; engineers can simulate memory pressure (e.g. via `adb shell am send-trim-memory`) to reproduce it, and it clears itself when the memory monitor reports `NORMAL`. | Logcat warnings `Bitmap cache fallback enabled` (and matching `...disabled`) with the activation reason. |

## Legacy simple renderer fallback

`FallbackMode.LEGACY_SIMPLE_RENDERER` forces the viewer onto the conservative
legacy renderer so repeated render faults cannot keep tripping the adaptive
pipeline.【F:domain/model/src/main/kotlin/com/novapdf/reader/model/FallbackMode.kt†L1-L16】
The view model enables it whenever the render circuit breaker trips enough times
or an escalated cache failure occurs, recording a `render_circuit_open:*`
breadcrumb and then persisting the fallback if no other fallback is active.【F:presentation/viewer/src/main/kotlin/com/novapdf/reader/PdfViewerViewModel.kt†L1814-L1896】
While active the app posts a snackbar (“Simple renderer fallback enabled for
stability.”) and logs a breadcrumb of the form
`fallback_mode:legacy_simple_renderer[:reason]` so crash reports clearly state
why the downgrade happened.【F:presentation/viewer/src/main/kotlin/com/novapdf/reader/PdfViewerViewModel.kt†L1876-L1893】【F:presentation/viewer/src/main/res/values/strings.xml†L139-L143】

Because the fallback is persisted through the shared `fallback_mode` preference,
QA and automation can preseed or clear it by calling
`UserPreferencesUseCase.setFallbackMode(...)`, which ultimately writes the enum
name into DataStore.【F:data/repositories/src/main/kotlin/com/novapdf/reader/data/UserPreferencesRepository.kt†L75-L100】
When the app boots it reads that preference and immediately applies the stored
mode, which also forces `ReaderActivity` to swap from the Compose UI to the
legacy layout if the simple renderer is active.【F:presentation/viewer/src/main/kotlin/com/novapdf/reader/PdfViewerViewModel.kt†L600-L619】【F:presentation/viewer/src/main/kotlin/com/novapdf/reader/ReaderActivity.kt†L90-L146】
Clearing or overwriting the preference with `NONE` restores the adaptive
experience on the next launch.

## Render cache fallback

`PdfDocumentRepository` tracks whether the disk-backed render caches
successfully initialise. Any failure to create the directories or touch the
filesystem marks `_cacheFallbackActive` and emits a non-fatal error, breadcrumb,
and logcat entry noting that a safe fallback is now active.【F:data/repositories/src/main/kotlin/com/novapdf/reader/data/PdfDocumentRepository.kt†L2148-L2189】【F:data/repositories/src/main/kotlin/com/novapdf/reader/data/PdfDocumentRepository.kt†L320-L333】
When the UI observes this state the view model warns the user with
`error_render_cache_unavailable`, logs `render_cache_fallback_active_ui`, and
opens the render circuit breaker so prefetching is shut down until caches can be
rebuilt.【F:presentation/viewer/src/main/kotlin/com/novapdf/reader/PdfViewerViewModel.kt†L652-L705】

Developers cannot override this fallback in-app: the render cache toggle inside
Developer Options is disabled and replaced with explanatory text whenever the
fallback is active, signalling that only a clean restart after the underlying
error is resolved will re-enable caching.【F:presentation/viewer/src/main/kotlin/com/novapdf/reader/PdfViewerScreen.kt†L1920-L1976】【F:presentation/viewer/src/main/res/values/strings.xml†L186-L195】

## Bitmap cache fallback

The in-memory `LruBitmapCache` also has a fallback path that shrinks the memory
footprint and mirrors entries onto disk whenever the system reports severe
memory pressure or the renderer throws an `OutOfMemoryError`. Triggers include
`onLowMemory`, `onTrimMemory(TRIM_MEMORY_RUNNING_LOW+)`, explicit
`BitmapMemoryLevel` warnings from the memory monitor, and render cache errors
raised with an `OutOfMemoryError`.【F:data/repositories/src/main/kotlin/com/novapdf/reader/data/PdfDocumentRepository.kt†L3052-L3173】
When activated the cache logs “Bitmap cache fallback enabled” with the reason,
prepares the disk store, and resizes itself to the conservative fallback
capacity; once memory pressure eases it logs “Bitmap cache fallback disabled”
and restores the normal cache size.【F:data/repositories/src/main/kotlin/com/novapdf/reader/data/PdfDocumentRepository.kt†L3175-L3258】
This fallback is automatic—engineers can only provoke it by sending memory
pressure events—and it never surfaces UI beyond the log statements.
