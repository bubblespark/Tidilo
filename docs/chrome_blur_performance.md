# Screen chrome blur performance

Measured on an API 36 Android emulator at approximately 60 Hz. These are relative debug-build results, not physical-device FPS, battery, or release-performance claims.

## Method

Baseline product revision: `dab6ea9`. Candidate product and measurement harness revision: `d5cf996`. Baseline and candidate app/test APKs were preserved separately and the five shared harness file hashes were verified equal. The baseline source export contains the same test-only driver; no production profiling switches or dependencies were added.

The driver mounts the actual LibraryScreen and BookmarkDetailScreen, stationary canvas and private ambient provider. A two-route native NavHost uses the same enter/exit/pop spring slides as TidiloNavGraph; navigation measures overlapping outgoing and incoming destinations, not immediate screen replacement. Each run creates 400 local fixture bookmarks in a fixed, collision-checked synthetic domain. Visible titles, summaries, URL/domain text, fallback accent, filter, layout and image mocks match across profiles; UUID ownership IDs are not displayed. Cleanup stops ViewModel writers, restores the exact changed preferences, and deletes only owned records. A test-only atomic ownership journal permits safe recovery after process interruption; a stop marker is checked outside timed case boundaries. Browser ACTION_VIEW intents are blocked by an instrumentation monitor; real pointer press/release events measure app-side dispatch/ripple and fixture engagement only.

Three profiles (baseline with existing overlay blur, extended ON, extended OFF) were measured five times. Odd repetitions used baseline/ON/OFF order; even repetitions reversed it. Default light covers all eight cases; Modern dark supplements idle and Detail scrolling. Each case warms for 3 seconds and measures for 10 seconds. Scrolling uses the same timed semantics-action algorithm at nominal 100ms intervals; these are rendering-window comparisons, not a fixed-throughput input benchmark. A separate 2-second initial fixture-mount interval follows Activity launch; it is not an app cold-start measurement. No builds ran alongside the final matrix. Earlier exploratory/interrupted runs were excluded. The toggle case deliberately flips the preference ten times in both ON and OFF profiles, then waits idle; its result is not the cost of steady OFF rendering.

Platform Window.FrameMetrics supplies TOTAL_DURATION and supported DEADLINE values. Each activity/dialog window is recorded separately; frames from different windows are not pooled into an FPS or jank rate. Percentiles use nearest rank. A static interval with no frames has unavailable duration/deadline metrics, not zero-cost frames. Dropped callbacks are reported separately. Frame-duration deadline exceedances do not establish compositor presentation or GPU shader duration.

## Full matrix before the final geometry optimization

Values are milliseconds: median of the five per-run P50/P95/P99 values, followed by the largest observed maximum. They are not pooled percentiles. See the [per-run CSV](chrome_blur_frame_metrics.csv) for sample counts, missing values, callback drops, deadline exceedances, initial activation and individual dialog windows.

| Default light case | Baseline P50 / P95 / P99 / max | ON P50 / P95 / P99 / max | OFF P50 / P95 / P99 / max |
| --- | --- | --- | --- |
| idle | n/a / n/a / n/a / n/a | n/a / n/a / n/a / n/a | n/a / n/a / n/a / n/a |
| library | 217.2 / 319.6 / 336.0 / 403.8 | 217.8 / 333.9 / 365.2 / 369.2 | 217.6 / 317.4 / 334.6 / 335.7 |
| detail | 289.9 / 330.9 / 352.4 / 372.3 | 314.7 / 350.6 / 396.1 / 669.0 | 286.2 / 331.4 / 340.3 / 381.9 |
| jump | 251.2 / 351.1 / 405.7 / 495.3 | 234.9 / 352.0 / 400.1 / 423.2 | 236.7 / 352.9 / 419.4 / 434.3 |
| header | 281.5 / 326.6 / 336.5 / 383.8 | 311.7 / 336.3 / 386.5 / 773.9 | 284.4 / 332.9 / 348.1 / 367.8 |
| navigation | 262.3 / 333.4 / 368.0 / 437.7 | 284.8 / 360.3 / 385.9 / 449.0 | 263.5 / 330.5 / 351.7 / 419.8 |
| modal | 350.3 / 667.4 / 899.1 / 1094.3 | 346.3 / 714.7 / 749.4 / 1315.4 | 184.5 / 288.4 / 288.4 / 791.0 |
| toggle | 31.7 / 52.4 / 52.4 / 84.2 | 51.7 / 118.6 / 120.5 / 131.8 | 67.1 / 144.5 / 171.2 / 196.0 |

| Modern dark case | Baseline P50 / P95 / P99 / max | ON P50 / P95 / P99 / max | OFF P50 / P95 / P99 / max |
| --- | --- | --- | --- |
| idle | n/a / n/a / n/a / n/a | n/a / n/a / n/a / n/a | n/a / n/a / n/a / n/a |
| detail | 287.3 / 335.8 / 351.2 / 451.4 | 314.3 / 353.8 / 388.8 / 487.0 | 290.1 / 334.2 / 400.9 / 519.5 |

## Repeated spike investigation

For each paired repetition, flag a maximum exceeding both two display intervals and twice the corresponding baseline case P95. At least three flagged repetitions out of five trigger investigation; this is not an SLA or an automatic attribution to blur. A missing baseline P95 makes the comparison unavailable.

| Case | Baseline flagged / comparable runs | ON flagged / comparable runs | OFF flagged / comparable runs |
| --- | --- | --- | --- |
| default/idle | n/a | n/a | n/a |
| default/library | 0/5 | 0/5 | 0/5 |
| default/detail | 0/5 | 1/5 | 0/5 |
| default/jump | 0/5 | 0/5 | 0/5 |
| default/header | 0/5 | 1/5 | 0/5 |
| default/navigation | 0/5 | 0/5 | 0/5 |
| default/modal | 0/5 | 1/5 | 0/5 |
| default/toggle | 0/5 | 3/5 | 5/5 |
| modern/idle | n/a | n/a | n/a |
| modern/detail | 0/5 | 0/5 | 0/5 |

## Final geometry fix and affected-case rerun

The full matrix above measures revision `d5cf996` before the final geometry optimization. Activation investigation identified a layout-to-composition write: HeaderBackdrop stored onSizeChanged height in mutable state and built the first mask at height zero. An Android shader regression reproduced an opaque-body alpha of 0 instead of 255 before measured state arrived. Revision `c951897` replaces it with a remembered ShaderBrush that resolves and caches the actual draw bounds on first draw and resize. The existing smootherstep stops and completed-background mask remain unchanged. This removes a redundant composition update and fixes first-frame masking; it does not lower blur quality. [Android documents the extra-frame risk of layout-to-composition writes](https://developer.android.com/develop/ui/compose/performance/backwards-write).

The affected header visibility, overlapping navigation, and toggle cases were repeated with baseline/ON/OFF five times using the same benchmark harness and matching protocol. The baseline product remains dab6ea9. This adds 45 timed cases to the original 150; it is a separate follow-up comparison, not a pooled before/after sample. Unaffected steady-scroll and idle rows above retain their pre-optimization revision label. Initial fixture activation is also retained in the [follow-up CSV](chrome_blur_followup_metrics.csv). All 60 follow-up window/activation rows have zero dropped callbacks.

| Final Default light case | Baseline P50 / P95 / P99 / max | ON P50 / P95 / P99 / max | OFF P50 / P95 / P99 / max |
| --- | --- | --- | --- |
| header | 280.5 / 333.1 / 351.9 / 367.5 | 294.8 / 340.7 / 371.7 / 456.9 | 280.4 / 335.1 / 401.2 / 468.1 |
| navigation | 267.8 / 334.2 / 404.0 / 475.6 | 284.4 / 348.9 / 434.1 / 551.0 | 263.3 / 335.3 / 403.3 / 521.9 |
| toggle | 34.5 / 51.6 / 51.6 / 58.5 | 56.5 / 115.9 / 121.0 / 131.2 | 84.2 / 148.0 / 174.1 / 206.6 |

| Final case | Baseline spike runs | ON spike runs | OFF spike runs |
| --- | --- | --- | --- |
| header | 0/5 | 0/5 | 0/5 |
| navigation | 0/5 | 0/5 | 0/5 |
| toggle | 0/5 | 5/5 | 5/5 |

Header visibility and navigation did not meet the recurring-spike trigger in the final rerun. Toggle activation still did; removing the extra mask composition did not remove the additional activation frames. The before/after toggle P95 differences are small and are not evidence of a statistically significant speedup. Normal static intervals in the full matrix produced no frames in any of the 30 idle runs. The original ON Detail/header/modal outliers each occurred in one of five runs; their original causes are not established merely by later traces.

## Trace evidence and limitations

The complete matrix and affected-case rerun contain 195 timed cases and 300 separate window/activation summary rows, with zero dropped FrameMetrics callbacks. All 30 dedicated idle intervals produced zero activity frames; their duration percentiles remain unavailable rather than zero.

Supplementary Perfetto captures used scheduling, app gfx/view events and SurfaceFlinger frame timeline, analyzed locally with the [official trace processor](https://perfetto.dev/docs/getting-started/command-line-analysis). The final captures followed an emulator restart and are not merged with the quantitative matrix. A broad ON capture wrapped its circular buffer; it was excluded for toggle analysis and replaced by a complete, short toggle capture. The baseline and dedicated final toggle captures reported no positive data-loss statistics. Measurement windows were located from the lifetime of the test's FrameMetrics callback threads in scheduler events.

For ten preference flips, the pre-optimization ON capture contained 15 `Recomposer:recompose` slices. The final ON capture contained 10, matching the baseline's 10. This agrees with eliminating the five measured-height feedback recompositions when frost reappears. RenderThread DrawFrame counts remained 20 in final ON versus 10 in baseline: the geometry fix removes redundant composition, not all effect-switching frames. Counts are evidence of the removed update; cross-restart wall times are not used to claim a before/after speedup.

In the final approximately six seconds after settling, both complete toggle captures contained zero UI/RenderThread slices. Final ON's longest recorded recomposition was 33.1ms and RenderThread DrawFrame 34.3ms, versus 29.1ms and 5.7ms in the supplementary baseline capture. These are traced wall durations, not shader execution time or allocation counts. Work is concentrated around the explicit preference changes; there is no evidence here of a continuing idle render/recomposition loop. These captures did not enable ART/Dalvik tracing, so missing GC slices do not establish the absence of GC; GC attribution remains unavailable.

The remaining final toggle maxima (ON 131.2ms, initially-OFF 206.6ms) are reported rather than hidden. The trigger remained 5/5 in both profiles; both deliberately activate and remove effects during this case. This fixture changes the theme input programmatically on Library without opening the Settings dialog, so it isolates chrome switching rather than the full Settings interaction. Exact GPU work, graphics resource allocation and individual Haze source/layer churn are not separately established by these traces. Source ownership/demand lifecycle is additionally covered by the hardware tests. No further app-level redundant work was demonstrated; no blur-quality reduction or experimental sharing was used to mask the cost. The original one-off Detail/header/modal outlier causes remain unproven.

## Functional verification

The change passed the full JVM suite, debug/test APK builds and minified release build. Hardware instrumentation covers body-backed header blur versus ordinary translucency, sharp foreground and transparent fade tail, source loss, stationary canvas inclusion, overlapping destination exclusion, actual Haze demand removal, retained draft/focus/scroll, real Search and Detail floating surfaces in both themes/light modes, fixed policies, existing modal behavior, header gestures/geometry, Search ripple and Detail lighting. Raw traces, APKs and screenshots remain local and ignored.
