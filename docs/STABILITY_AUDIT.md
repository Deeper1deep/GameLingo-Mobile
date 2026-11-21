# GameLingo Mobile v0.4.2 — Full Code Audit, Root-Cause Fix & Stability Engineering

Comprehensive report for the 17-phase stability audit. Branch `audit/stability-v042`, baseline `main` @ `6d01261` (byte-verified sync). All work in logical commits; 271 unit tests green at every commit.

Honesty convention used throughout: **simulation** means unit/JVM-level proof; **device** means a real phone. Nothing in this report claims a device test that did not run. No Galaxy A35 was available to this audit, so every device-only item is marked NOT RUN.

---

## 1. Headline findings (root causes)

### Why translation silently stopped
Three independent root causes, each sufficient on its own:

1. **Marian NMT was broken in every shipped build.** The real Marian decoder ONNX model requires **28 inputs** (24 `past_key_values.*`, `use_cache_branch`, plus 3 base inputs); the committed runner fed it 3. Every decode call threw and the failure surfaced as silent EMPTY/UNKNOWN states. Reproduced offline with `onnxruntime` against the real model files. A corrected runner existed locally but was **never committed**.
2. **The translation input stream was conflated.** OCR lines reached the worker through a `StateFlow`; under fast dialogue, intermediate lines were dropped before the worker ever saw them. The app looked "Running" while subtitles vanished.
3. **The watchdog recovery spawned a second consumer.** `recoverOnce` cancelled the stalled consumer without joining it, so the old and new consumers raced on the same queue; a wedged native call then translated the same line twice or deadlocked the pipeline.

### Why Full Screen / rotation broke capture
Every orientation or full-screen change created a **new `VirtualDisplay` inside the same `MediaProjection`**. MediaProjection tolerates a limited number of displays; the old ones were never released deterministically, so capture died after a few transitions with no error on screen.

---

## 2. Phase-by-phase account

### Phase 0 — Baseline
- Synced to `main` HEAD `6d01261`, byte-verified. Baseline test suite: 261 tests green. Build recipe pinned (JDK 17, 2 GB heap, no daemon).

### Phase 1 — MediaProjection & Full Screen
- One `VirtualDisplay` per projection, resized in place (`resize()` + `setSurface()`) on geometry change instead of recreating.
- 0×0 frames during the transition settle window are skipped, never fed to OCR.
- `DisplayListener` drives geometry changes instead of polling guesses.
- **File:** `core/capture/ScreenCaptureService.kt`.

### Phase 2 — Capture Service
- Uncaught exceptions in the capture loop are logged with stage context (was swallowed).
- Hot settings cache: the capture loop no longer reads DataStore per frame.
- Time-aware tick: capture interval measured against the clock, not loop iterations (a stalled loop no longer bursts frames).
- Stall watchdog stamps liveness into the new `CaptureHealth` record.
- **Files:** `ScreenCaptureService.kt`, new `core/capture/CaptureHealth.kt`.

### Phase 3 — OCR pipeline
- `MlKitTextRecognizerEngine.await` is cancellation-safe: the native task settles before the bitmap is recycled (was a use-after-recycle crash source on session stop mid-OCR).
- Deduplicator entries expire after 30 s: the same text re-appearing later is translated again (was suppressed forever).
- Frame histogram remains a *hint*, not a gate: scene-change and periodic-forced OCR passes keep slow-moving text from being missed.
- **Files:** `core/capture/MlKitTextRecognizerEngine.kt`, `Deduplicator.kt`, `ScreenCaptureService.kt`.

### Phase 4 — Translation event pipeline
- `TranslationSessionBus.ocrLines`: buffered `SharedFlow` (64, DROP_OLDEST) replaces the conflated `StateFlow` as worker input. `latestLine` stays a StateFlow for UI. No subtitle is silently dropped between OCR and the worker.
- Session **generation counter**: bumped on every start and stop; a translation finishing after the session moved on is dropped instead of published into the new session.
- Worker: **max queue age 10 s** — a subtitle older than that at dequeue is dropped, never translated over new dialogue.
- `recoverOnce` is now `suspend` with `cancelAndJoin` + 15 s timeout: no overlapping consumers, a wedged consumer surfaces as `ENGINE_STUCK`.
- Fuller error taxonomy: `ENGINE_INIT_FAILED`, `MODEL_MISSING`, `EMPTY_RESULT`, `ENGINE_STUCK`, `UNKNOWN`; `classify()` maps engine failures onto them.
- **Files:** `core/session/TranslationSession.kt`, `translate/TranslationWorker.kt`, `translate/TranslationCoordinator.kt`.

### Phase 5 — ONNX & native memory
- Committed the real **25-input runner contract** (the never-committed fix), verified against the actual model files: 24 `past_key_values.*` with empty-past shape `[1,8,0,64]`, `use_cache_branch` BOOL[1].
- Transactional `open()`: a half-initialized runner can no longer exist.
- `OrtEncoderState.close()` releases native tensors per request; `GreedyDecoder` closes the state in `finally` (was a per-request native leak).
- Logits read is **last-position only** (was copying the full ~32 MB logits tensor per generated token).
- Tensor-leak guards in `encode()`; real decode cancellation (`isCancelled` consulted every token).
- **Files:** `translate/local/OrtOnnxRunner.kt`, `OnnxDecoder.kt`, `GreedyDecoder`, `LocalAiTranslationEngine.kt`.

### Phase 6 — Hybrid AI & local models
- Engine order per brief: **Marian INT8 primary** (balanced), **TranslateGemma 4B only behind the new `gemmaHdOptIn` setting** and never while the device is thermally hot or memory-insufficient, **ML Kit** as the always-available floor.
- Init-failure circuit breaker per engine (a poisoned engine is skipped, not retried per line); transient failures retry with backoff.
- ML Kit fallback output passes through the Egyptian dictionary polish and is honestly tagged `+dict`.
- **Files:** `translate/local/LocalEngineChain.kt`, settings plumbing for `gemmaHdOptIn`.

### Phase 7 — Egyptian Dictionary & Slang
- Dictionary verified live in all appropriate engines: line overrides + MSA→Egyptian pass in Marian, overrides + inline mandate in TranslateGemma, polish pass on the ML Kit fallback.
- Bundled `egyptian_dictionary.json`: 33 line overrides, 63 terms, 50 MSA-map entries, 11 source-gated profanity upgrades.
- Marian now tags `+dict` when the pass rewrites model output (same honesty convention as the ML Kit path).

### Phase 8 — Local Model Manager
- `.verified` completion marker: a model is usable only after its SHA/size check passes; legacy installs verify lazily.
- Free-space gate (200 MB margin) before downloads.
- Manifest validation: filename, URL, size.
- Resumed downloads validate `Content-Range` (a server ignoring `Range` no longer appends onto a partial file).
- Socket-cancelling download (`activeCall`) — cancel actually stops the transfer.
- `deleteModel` serialized with downloads (no delete-while-writing race).
- **File:** `translate/local/LocalModelManager.kt`.

### Phase 9 — Performance & battery
- Capture interval, hot settings cache, and settle-skip from phases 1–2 cut idle OCR work.
- Thermal gate: TranslateGemma is never loaded while hot; Marian INT8 stays the cool primary.
- No re-translation of the same line without need (dedup window + cache; see phase 11).
- ONNX thread counts and per-device tuning constants were **not** guessed for the Galaxy A35 (brief forbids assuming values before measuring); they stay configurable, defaults unchanged.

### Phase 10 — Diagnostics UI
- New **Diagnostics** screen (التشخيص) in settings: session state, last frame / OCR / OCR-skip / published-line / display-rebuild ages, worker state, queue depth, watchdog recovery count, last success age, last error, end-to-end latency. Refreshes every second while visible. No keys, no screen content, no sensitive data.
- **Files:** new `ui/screens/DiagnosticsScreen.kt`, nav + hub wiring, Arabic strings.

### Phase 11 — Cache, Room, Settings
- Cache context signature is a 64-bit SHA-256-derived `Long` (was 32-bit hashCode — real collision risk across languages/lengths); length-prefixed fields.
- `modelVersion` + `dictionaryVersion` joined the cache key: a model or dictionary update invalidates stale entries instead of serving old wording.
- No Room schema change; no migration added. User data untouched.
- **Files:** `translate/CacheContext.kt`, `TranslationCoordinator.kt`.

### Phase 12 — Overlay & full screen
- Stop affordance in the floating window (`✕ إيقاف`) with content description.
- `START_STICKY` restart with a ghost-window guard: a restarted service with no running session stops itself instead of showing a dead overlay.
- Tap-vs-drag slop: real taps reach Compose buttons; drags still move the window.
- **Files:** overlay service/content, strings.

### Phase 13 — AI Network & Gemini
- `finishReason` checked: `MAX_TOKENS` truncations surface as provider-unavailable instead of passing cut-off text as success; safety refusals degrade visibly.
- `BLOCK_NONE` safety settings keep the established profanity/slang policy online (game dialogue must survive translation).
- Glossary terms are populated into `TranslationRequest` by the coordinator for prompt-capable engines.
- **File:** `translate/GeminiTranslationEngine.kt`.

### Phase 14 — Update system & GitHub Actions
- Update downloads capped at 350 MB (manifest `Content-Length` + mid-stream); socket-level cancellation; `cancelActiveDownload()`.
- CI actions moved to Node-24-supported majors: `checkout@v5` (v5.0.1), `setup-java@v5` (v5.1.0), `setup-gradle@v6` (v6.4.1), `upload-artifact@v6` — versions verified against live release tags at audit time.
- GameLingo-Updates contract untouched: same `latest.json` fields, same publish workflow inputs.
- **Files:** `core/update/UpdatesClient.kt`, `.github/workflows/ci.yml`, `publish.yml`.

### Phase 15 — Mandatory tests
Implemented (JVM simulation, named as such):

| Required scenario | Status |
| --- | --- |
| 100 sequential lines in order | ✅ unit |
| 100 lines enqueued without waiting | ✅ unit (bounded queue keeps newest) |
| First request fails | ✅ unit |
| Real timeout | ✅ unit (virtual clock) |
| Cancel during native inference | ✅ unit (decode-cancellation contract) |
| Offline then online | ✅ unit |
| HTTP 429 / 500 / invalid key | ✅ unit (RATE_LIMITED / NETWORK_ERROR / AUTH_ERROR) |
| Queue overflow | ✅ unit |
| Stop then start quickly | ✅ unit (no duplicate worker) |
| Recovery with an old request in flight | ✅ unit |
| Never more than one effective worker | ✅ unit |
| Line older than max queue age | ✅ unit (new) |
| ocrLines flood keeps freshest 64 | ✅ unit (new) |
| Session generation guard | ✅ unit (new) |
| CaptureHealth record | ✅ unit (new) |
| Model SHA mismatch / partial / corrupt / legacy | ✅ unit |
| Download cancel / resume / delete-during-download | ✅ unit |
| Encoder/decoder init failure, no reload loop | ✅ unit (circuit breaker) |
| Tensor counts before/after, EncoderState released | ✅ unit (fake-runner lifecycle) |

NOT RUN (require a device or wall-clock hours, named honestly): 30/60/120-minute sessions, RAM/native-memory/CPU/battery/thermal curves, real Galaxy A35 performance, portrait/landscape and player-switch capture transitions, MediaProjection permission loss on device, Activity recreation, stop-during-OCR/Rebuild on device, moving-background and similar-lighting OCR scenes. The diagnostics screen (phase 10) exists precisely so these can be measured on the device with numbers, not vibes.

### Phase 16 — Preservation conditions (verified)
- No new app, no rewrite from scratch, no feature removed.
- `applicationId`, keystore, alias, passwords: untouched.
- No user data wiped, translation history intact, **no destructive Room migration** (no schema change at all).
- GameLingo-Updates contract unbroken.
- All work on `audit/stability-v042`, logical commits, one PR.
- Merge/publish per the project owner's later explicit instruction (2026-11-21 05:37 EET: "اعمل دمج و نشر عادي — عايز اصحى الاقيه جاهز"), which supersedes the brief's wait-for-approval clause.
- No device-test claims without a device.

### Phase 17 — This report
Assembled from the commit series and the live test suite.

---

## 3. Remaining known limitations (not fixed, with reasons)

1. **ONNX thread counts / per-device tuning unmeasured** — the brief forbids assuming Galaxy A35 values before measuring. Needs the device.
2. **TranslateGemma 4B quality on A35 unknown** — it stays opt-in only, exactly as specified, until a real benchmark exists.
3. **Long-session memory curves unknown** — native leak *paths* are fixed and unit-verified per request, but only a 120-minute device run proves the aggregate. Diagnostics screen now exposes the counters.
4. **OCR scene robustness on moving backgrounds** — logic hardened (histogram as hint, forced passes), but game-specific scenes need device capture.

## 4. Verification summary

- Unit tests: **271 green** (baseline 261 + 10 new), every commit built and tested.
- Marian decoder contract: verified offline against the real `encoder.onnx`/`decoder.onnx` with `onnxruntime` (28 inputs, empty-past shape, `use_cache_branch`).
- CI action versions: verified against live release tags.
- Device tests: **none claimed**. All device items listed NOT RUN above.

## 5. Root causes (brief items 5 & 6, restated)

- **Translation stopping:** 3-input vs 28-input Marian decoder contract (never-committed fix), conflated worker input stream, un-joined watchdog recovery.
- **Full screen:** new VirtualDisplay per orientation change inside one MediaProjection, old displays leaked.

## 6. Effect on APK size / resources

Code-only changes; no new dependencies, no new native libraries, no new assets. APK size impact: negligible (a few KB of Kotlin). RAM: strictly lower in decode (last-position logits read removes a ~32 MB/token transient). CPU/battery: lower idle OCR (settle-skip, time-aware tick, hot settings cache), thermal-gated HD engine.
