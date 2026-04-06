# Implementation Plan

## Iteration 1 — Scaffold ✅

**Goal:** Establish module structure, interfaces, and build infrastructure.

- [x] Multi-module Gradle project (10 modules)
- [x] `AudioFrame`, `AudioCapture`, `MicrophoneCapture` (IO-bound flow)
- [x] `VadResult`, `VoiceActivityDetector`, `EnergyBasedVad`
- [x] `SpeechSegment`, `SpeechSegmenter`, `VadSpeechSegmenter`
- [x] `PaceMetrics`, `PaceEstimator`, `RollingWindowPaceEstimator`
- [x] `FeedbackEvent`, `FeedbackDecision`, `ThresholdFeedbackDecision`
- [x] `SessionState`, `SessionManager`, `DefaultSessionManager`
- [x] `SessionRecord`, `SessionRepository` (interface only)
- [x] `UserPreferences`, `AppSettings` (interface only)
- [x] `MainScreen`, `MainViewModel`, `SpeechPilotTheme` (placeholder UI)
- [x] Unit tests for VAD, pace, feedback, session
- [x] README, phase1_architecture.md, plan.md

---

## Iteration 2 — Live Pipeline ✅

**Goal:** Connect modules into a working real-time pipeline.

- [x] Create `SessionConfig`, `SessionStats`, `LiveSessionState` models
- [x] Implement `SpeechCoachSessionManager` wiring `MicrophoneCapture → EnergyBasedVad → VadSpeechSegmenter → RollingWindowPaceEstimator → ThresholdFeedbackDecision`
- [x] Extend `SessionManager` interface with `liveState: StateFlow<LiveSessionState>`
- [x] Implement `RECORD_AUDIO` permission request in `MainActivity`
- [x] Update `MainViewModel` to call `SpeechCoachSessionManager.start()` / `stop()`
- [x] Expose live WPM and feedback in `MainUiState`
- [x] Display live WPM, feedback event, and listening indicator in `MainScreen`
- [x] Unit tests for `SpeechCoachSessionManager` state transitions

---

## Iteration 3 — Persistence ✅

**Goal:** Record and display session history.

- [x] Implement Room `@Entity` for `SessionRecord` and `@Dao` (`SessionDao`)
- [x] Implement `SpeechPilotDatabase` (Room database singleton)
- [x] Implement `RoomSessionRepository`
- [x] Wire `SpeechCoachSessionManager` to save a record on session stop via `SessionRepository`
- [x] Add session history screen (`HistoryScreen`, `HistoryViewModel`)
- [x] Unit tests for `SessionRecord` model and defaults

---

## Iteration 4 — Settings ✅

**Goal:** Let users configure pace targets.

- [x] Implement `DataStoreAppSettings` (DataStore-backed)
- [x] Add settings screen with target WPM, tolerance, and cooldown sliders (`SettingsScreen`, `SettingsViewModel`)
- [x] Feed `UserPreferences` into `ThresholdFeedbackDecision` at session creation
- [x] Persist preferences across app restarts
- [x] Add lightweight screen-switcher navigation (`AppNavigation`)
- [x] Unit tests for `UserPreferences` defaults and copy behavior

---

## Iteration 5 — Quick-Start, Background Hardening, Passive Mode, UX Cleanup ✅

**Goal:** Improve runtime robustness, reduce session-start friction, add passive-mode foundation, and clean up live session UX.

- [x] Add `SessionMode` enum (`Active` / `Passive`) to `:session`
- [x] Add `mode: SessionMode` field to `LiveSessionState`
- [x] Update `SessionManager.start()` to accept `mode: SessionMode = SessionMode.Active`
- [x] Guard `SpeechCoachSessionManager.start()` — no-op if already Starting/Active/Stopping
- [x] Guard `SpeechCoachSessionManager.stop()` — no-op if already Idle/Stopping
- [x] Suppress feedback dispatch in `SessionMode.Passive`
- [x] Create `SpeechCoachingService` foreground service to keep process alive during backgrounding
- [x] Add `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` permissions to manifest
- [x] Wire service start/stop from `MainActivity` observing live session state
- [x] Create notification channel in `SpeechPilotApp.onCreate()`
- [x] Add `errorMessage: String?` and `sessionMode: SessionMode` to `MainUiState`
- [x] Surface error state and session mode from `LiveSessionState` in `MainViewModel`
- [x] Add `dismissError()` to `MainViewModel`
- [x] Improve `MainScreen` UX: speech-active indicator, passive-mode badge, error display, layout cleanup
- [x] Add `SessionModeTest` — enum equality, default in `LiveSessionState`, copy behaviour
- [x] Add session idempotency tests — double-stop, stop without start, mode storage
- [x] Update `phase1_architecture.md` with background service, passive mode, invariants

---

## Iteration 9C — Stabilization, Bug-Fix, and Integration Coherence ✅

**Goal:** Fix issues introduced across earlier iterations and improve coherence, correctness, and maintainability. No new features.

- [x] **Fix `:ui:compileReleaseKotlin` build failure** — `MainViewModel` directly instantiated `SpeechCoachSessionManager` with named constructor arguments, but `:ui` does not depend on `:audio`, `:vad`, or `:pace`. Kotlin's compiler requires visibility of all constructor parameter types when resolving default-argument calls. Fixed by adding a `SpeechCoachSessionManager.create()` companion factory that accepts only types already on `:ui`'s classpath (`FeedbackDispatcher?`, `SessionRepository?`, `FeedbackDecision`), and updating `MainViewModel` to use it.
- [x] **Fix nullable warning in `RollingWindowPaceEstimator.evictOldSegments`** — `java.util.ArrayDeque.peekFirst()` returns a nullable type in Kotlin. The original call was unsafe (`segmentQueue.peekFirst().startMs`). Fixed by extracting into a null-safe local variable with an early break.
- [x] Review and confirm docs accuracy (README, `phase1_architecture.md`)

---

## Iteration 6 — Improved Pace Estimation

**Goal:** Replace placeholder WPM estimation with a real word-boundary approach.

- [ ] Research lightweight on-device word-count heuristics (syllable-based or energy-envelope)
- [ ] Implement improved `PaceEstimator` in `:pace`
- [ ] Benchmark estimation accuracy against ground-truth recordings
- [ ] Update unit tests with realistic test vectors

---


## Iteration 10 — Local Transcript Debug + Transcript WPM ✅

**Goal:** Add an optional local transcription debug path and text-derived rolling WPM for pace calibration truth-checking.

- [x] Add `:transcription` module with `LocalTranscriber` contract
- [x] Implement `AndroidSpeechRecognizerTranscriber` (local, offline-preferred)
- [x] Add `RollingTranscriptWpmCalculator` and deterministic unit tests
- [x] Integrate optional transcription into `SpeechCoachSessionManager` without breaking existing heuristic pipeline
- [x] Expose transcript text + transcript-derived rolling WPM in `LiveSessionState`
- [x] Extend debug panel to compare heuristic pace vs transcript WPM
- [x] Add settings toggle for transcript debug mode (off by default)
- [x] Document limits, enablement, and persistence scope

---

## Iteration 13 — Transcript Runtime Visibility + Diagnosis ✅

**Goal:** Make transcript debug behavior explicit and diagnosable during live sessions.

- [x] Add typed transcript runtime model (`TranscriptDebugState`, `TranscriptDebugStatus`)
- [x] Keep transcript diagnostics visible in UI whenever transcript debug mode is enabled (even with blank transcript text)
- [x] Distinguish waiting / partial-only / final-available / unavailable / error states
- [x] Add explicit transcript WPM pending context when no finalized words have been recognized yet
- [x] Expose lightweight transcript diagnostics: partial-present flag, finalized/rolling word counts, last update timestamp
- [x] Add deterministic tests for transcript status mapping and partial-only behavior
- [x] Update README + architecture docs for transcript debug status semantics and final-word WPM dependency

---

## Iteration 14 — Transcript-First Live UX + Pace Hierarchy ✅

**Goal:** Make live sessions practically usable by centering transcript visibility and transcript-derived pace while keeping local-first constraints intact.

- [x] Add dedicated transcript card to main live screen when transcript mode is enabled
- [x] Keep transcript surface visible even when text is blank; render explicit states (listening, partial, final, no-final-yet, unavailable/error)
- [x] Promote transcript-derived WPM to primary pace display when finalized words exist
- [x] Show explicit pending/unavailable states instead of silent `0.0` transcript pace
- [x] Demote heuristic pace to secondary/debug context in transcript mode
- [x] Keep coaching wording honest: transcript pace may be displayed while decision engine remains heuristic
- [x] Simplify debug panel to compact calibration fields (speech-active, transcript status/engine, text WPM, heuristic pace, target, last decision, cooldown, mic level)
- [x] Add deterministic UI presentation tests for transcript state and primary pace selection logic
- [x] Extend transcript status mapping tests for unavailable/error states
- [x] Update README + architecture docs to reflect transcript-first UX and primary/secondary pace hierarchy

---

## Iteration 15 — Decision Signal Alignment (Transcript-Driven Feedback) ✅

**Goal:** Align coaching decisions with the visible pace signal by making transcript WPM the primary decision input when ready, with explicit fallback behavior.

- [x] Add explicit pace-signal selection model (`PaceSignalSource`, `PaceSignalSelection`)
- [x] Add deterministic transcript readiness rules for decision use (finalized transcript + minimum rolling word threshold + healthy status)
- [x] Update `SpeechCoachSessionManager` to evaluate feedback using selected signal (transcript when ready, heuristic fallback otherwise)
- [x] Expose active decision source/reason/fallback state in live debug state
- [x] Align live pace card and coaching labels with active decision signal
- [x] Expand compact debug panel with pace-source diagnostics (active source, reason, fallback, readiness, decision pace, alternative pace)
- [x] Add deterministic tests for pace-source selection and fallback behavior
- [x] Update docs for decision-source alignment and fallback semantics

---

## Iteration 11 — Live Audio Activity + UI Responsiveness ✅

**Goal:** Make the app feel observably alive during a session. Add live microphone level, live VAD speech activity, and a visual level meter. Fix top-chrome button clash and system back handling.

**Root causes addressed:**
- UI only updated on completed speech segments — frozen between segments
- `isSpeechDetected` was a historical sticky bool, not a live "speaking right now" signal
- No mic level or audio activity ever reached the UI
- History / Settings buttons overlapped phone status bar on tall-display devices (e.g. Pixel Fold)
- System back on Settings / History screens exited the app instead of returning to main screen

**Changes:**
- [x] Add `isSpeechActive: Boolean` to `LiveSessionState` — live frame-cadence VAD signal (~100 ms)
- [x] Add `micLevel: Float` to `LiveSessionState` — normalized RMS [0, 1] updated per frame window
- [x] Add frame-level monitor in `SpeechCoachSessionManager`: share audio frames via `shareIn`, launch parallel coroutine computing RMS and speech-active every `FRAME_LEVEL_UPDATE_INTERVAL` (3) frames
- [x] Add `computeFrameRms()` private utility in session manager (mirrors `EnergyBasedVad` logic)
- [x] Add `isSpeechActive` and `micLevel` to `MainUiState`; map from `LiveSessionState` in `MainViewModel`
- [x] Update `statusText` in `MainViewModel` to show "Speaking…" when `isSpeechActive`
- [x] Add `AudioLevelBars` composable to `MainScreen` — five animated bars in the status card header driven by `micLevel`, colored by `isSpeechActive`
- [x] Update status subtitle to use three-state messaging: "Speaking now" / "Speech detected this session" / "Waiting for speech…"
- [x] Update debug panel to show live `micLevel` and three-state `isSpeechActive` / `isSpeechDetected` label
- [x] Add `statusBarsPadding()` to `MainScreen`, `SettingsScreen`, and `HistoryScreen` columns to avoid status bar overlap on tall-display devices
- [x] Add `BackHandler` in `AppNavigation` for Settings / History screens so system back returns to main screen
- [x] Add `implementation(libs.androidx.activity.compose)` to `ui/build.gradle.kts` for `BackHandler`
- [x] Add unit tests: mic level > 0 after high-energy frames, `isSpeechActive` true/false for high/low-energy frames, `isSpeechDetected` historical persistence
- [x] Update `phase1_architecture.md` with live audio activity section, updated data flow, speech-activity semantics table

---

## Iteration 12 — Segment Finalization + Debug Observability ✅

**Goal:** Fix "mic alive but pace stuck at zero" by making segmentation state observable and ensuring pace estimation is reachable in real-device sessions.

**Root causes addressed:**
- Debug target/config values only became fully visible after segment processing paths ran
- VAD threshold was too permissive for real-device ambient noise, causing near-continuous speech classification
- Long-enough silence was often not observed, so segments were not finalized and pace estimation never ran
- UI did not expose segmentation progress (open segment / silence accumulation / finalized count)

**Changes:**
- [x] Raise `EnergyBasedVad` default threshold from `300` to `750` RMS to improve speech/silence separation on-device
- [x] Reduce `VadSpeechSegmenter.MIN_SILENCE_FRAMES` from `10` to `6` to finalize segments faster once silence begins
- [x] Add real-time segmentation diagnostics (`SegmentationDebugSnapshot`) emitted per frame
- [x] Extend `DebugPipelineInfo` with VAD energy/classification, segment-open counters, and finalized segment count
- [x] Initialize debug state at session start with configured target WPM and VAD threshold (no longer defaulting to zero)
- [x] Surface new diagnostics in the debug panel (`MainScreen`)
- [x] Add deterministic unit tests for segmentation debug snapshots and session debug initialization/finalized-segment reporting

---

## Iteration 16 — Transcription Backend Strategy: Vosk-First with Android SR Fallback ✅

**Goal:** Address the root cause of unreliable transcription — replace direct dependence on `AndroidSpeechRecognizer` as the primary transcript path with a dedicated on-device STT architecture.

**Diagnosis:**
- Android `SpeechRecognizer` is device/service-dependent: offline mode is a hint, not a guarantee
- Recognition quality, latency, and session continuity vary unpredictably across devices
- The app fell back to heuristic pace too often because transcript was unreliable, not because the architecture was wrong
- The fix is to make dedicated on-device STT (Vosk) the preferred backend and demote `AndroidSpeechRecognizer` to fallback

**Changes:**
- [x] Add `TranscriptionBackend` enum (`DedicatedLocalStt`, `AndroidSpeechRecognizer`, `None`)
- [x] Extend `TranscriptionEngineStatus` with `InitializingModel` and `ModelUnavailable` to cover Vosk lifecycle states
- [x] Add `activeBackend: StateFlow<TranscriptionBackend>` to `LocalTranscriber` interface
- [x] Implement `VoskLocalTranscriber` — preferred dedicated on-device STT backend:
  - Checks for Vosk model assets at `context.filesDir/vosk-model-small-en-us`
  - Reports `ModelUnavailable` immediately when model directory is absent
  - Architecture is production-ready for Vosk library integration (see TODO markers in `runRecognition()`)
  - `activeBackend` is always `DedicatedLocalStt`
- [x] Implement `RoutingLocalTranscriber` — automatic backend selection at session start:
  - Tries `VoskLocalTranscriber` (preferred)
  - Falls back to `AndroidSpeechRecognizerTranscriber` when Vosk reports `ModelUnavailable`
  - Exposes which backend is active via `activeBackend`
- [x] Demote `AndroidSpeechRecognizerTranscriber` to explicit fallback/compatibility role (updated KDoc)
- [x] Update `TranscriptDebugState` to include `activeBackend` field and `ModelUnavailable` status
- [x] Update `TranscriptDebugStatus` with `ModelUnavailable` case
- [x] Update `resolveTranscriptDebugStatus` to handle `InitializingModel` and `ModelUnavailable`
- [x] Update `PaceSignalSelector` to treat `ModelUnavailable` as unavailable (fallback to heuristic)
- [x] Update `SpeechCoachSessionManager` to collect and surface `activeBackend` from the transcriber
- [x] Update `MainViewModel` to use `RoutingLocalTranscriber` (Vosk primary + Android SR fallback)
- [x] Update `LiveSessionPresentation` with `ModelUnavailable` status text
- [x] Update debug panel to show active backend (`Transcript backend` row)
- [x] Add `VoskLocalTranscriberTest`: model availability checks, `ModelUnavailable` status on absent model, lifecycle
- [x] Add `RoutingLocalTranscriberTest`: primary backend selection, fallback activation, stop/restart behavior
- [x] Extend `TranscriptDebugStateTest` with `ModelUnavailable` and `InitializingModel` cases
- [x] Extend `PaceSignalSelectorTest` with `ModelUnavailable` fallback case
- [x] Update `SpeechCoachSessionManagerTest` stubs to satisfy updated `LocalTranscriber` interface
- [x] Update README, `phase1_architecture.md`, `plan.md`

**Remaining limitations:**
- `VoskLocalTranscriber.runRecognition()` contains `TODO: Vosk API` placeholder loops — actual Vosk recognition is not yet active. To enable:
  1. Add `com.alphacephei:vosk-android:0.3.47@aar` and `net.java.dev.jna:jna:5.13.0@aar` to `transcription/build.gradle.kts`
  2. Place Vosk model assets in `context.filesDir/vosk-model-small-en-us`
  3. Implement the `TODO: Vosk API` blocks in `VoskLocalTranscriber`
- Until model assets are present, `RoutingLocalTranscriber` automatically activates `AndroidSpeechRecognizerTranscriber` as fallback with no user action required

---

## Iteration 17 — Transcription as First-Class Feature ✅

**Goal:** Promote transcription from a debug/experimental toggle to a first-class, on-by-default feature.

**Motivation:**
- Transcription backend architecture is now solid (Vosk-first with Android SR fallback)
- The "Local transcript debug" naming and `false` default were holdovers from exploratory work
- Transcription is the primary pace signal path; it should be on by default for all users
- File sessions also need the same transcriber as live sessions

**Changes:**
- [x] Rename `UserPreferences.localTranscriptDebugEnabled` → `transcriptionEnabled`, default `false` → `true`
- [x] Update `DataStoreAppSettings` DataStore key: `local_transcript_debug_enabled` → `transcription_enabled` (new key means existing stored `false` values are ignored — users get the new `true` default)
- [x] Rename `SettingsViewModel.updateLocalTranscriptDebugEnabled` → `updateTranscriptionEnabled`
- [x] Rename Settings UI: "Local transcript debug" → "Transcription" with clear user-facing description
- [x] Rename `MainUiState.transcriptDebugEnabled` → `transcriptionEnabled`
- [x] Wire `RoutingLocalTranscriber` for file sessions (previously file sessions used `NoOpLocalTranscriber`)
- [x] Update `MainViewModel` all references; both live and file session managers pass the correct transcriber
- [x] `SpeechCoachSessionManager.create()` and `createForFile()` factory defaults: `transcriptDebugEnabled=false` → `true`
- [x] Fix `MainScreen` transcript card visibility: show only during active sessions (avoids "disabled" clutter on idle screen)
- [x] Fix `MainScreen` debug panel visibility: removed redundant `transcriptionEnabled` condition (debug panel gates on `isListening || isSpeechDetected`)
- [x] Update `LiveSessionPresentation.kt` field reference
- [x] Update `LiveSessionPresentationTest.kt` field references
- [x] Update `UserPreferencesTest`: test default is now `true`, field renamed
- [x] Update README, `phase1_architecture.md`, `plan.md`

---

## Iteration 18 — Vosk Backend Fully Implemented ✅

**Goal:** Finish the Vosk transcription backend so it is actually operational, not just scaffolded.

**Motivation:**
- Previous iterations introduced `VoskLocalTranscriber` with lifecycle, model detection, and
  routing wired — but `runRecognition()` still contained TODO placeholders
- Vosk AAR library was not added to `build.gradle.kts`
- No audio was actually fed to Vosk (it had no access to the shared frame stream)
- `emitResult()` passed raw JSON as-is instead of extracting transcript text
- This meant the app always fell back to Android SpeechRecognizer despite Vosk being "preferred"

**Changes:**
- [x] Add `com.alphacephei:vosk-android:0.3.47@aar` and `net.java.dev.jna:jna:5.13.0@aar` to
      `transcription/build.gradle.kts`
- [x] Add `implementation(project(":audio"))` to `transcription/build.gradle.kts` so
      `VoskLocalTranscriber` can receive `Flow<AudioFrame>` from the session pipeline
- [x] Add `setAudioSource(Flow<AudioFrame>)` default no-op method to `LocalTranscriber` interface;
      backends that own their own audio (Android SR) inherit the no-op; Vosk overrides it
- [x] Implement `VoskLocalTranscriber.setAudioSource()` — stores the shared frame flow reference
- [x] Implement `VoskLocalTranscriber.runRecognition()` — opens `org.vosk.Model` +
      `org.vosk.Recognizer`, collects PCM frames from the audio source, feeds them to Vosk,
      emits partial/final results; releases resources in `finally`
- [x] Implement `VoskLocalTranscriber.stop()` using `recognitionJob.cancelAndJoin()` for clean
      lifecycle (previously just set a flag and ran a no-op `withContext`)
- [x] Implement `VoskLocalTranscriber.emitResult()` with real JSON parsing via `parseVoskResult()`
- [x] Implement `VoskLocalTranscriber.parseVoskResult()` — lightweight regex extracts `"text"`
      (final) or `"partial"` (partial) from Vosk JSON; never passes raw JSON through
- [x] Add `ShortArray.toLeByteArray()` helper — converts 16-bit PCM samples to little-endian
      byte pairs as required by `Recognizer.acceptWaveForm()`
- [x] Update `RoutingLocalTranscriber.setAudioSource()` to forward to both primary and fallback
- [x] Restructure `SpeechCoachSessionManager`: `sharedFrames` is set up **before**
      `localTranscriber.setAudioSource(sharedFrames)` and `startTranscriptionCollector()` —
      ensures Vosk has the audio source when `start()` is called
- [x] Add `parseVoskResult` unit tests (final, partial, blank, missing key, whitespace, compact JSON)
- [x] Add `setAudioSource` unit test
- [x] Remove stale "add library / implement TODO" notes from README and `phase1_architecture.md`
- [x] Update README Vosk section to reflect actual wired state
- [x] Update `phase1_architecture.md` runtime limitations → runtime behaviour section

**Architecture note:** Vosk reads from the existing `MicrophoneCapture` shared frame stream — it
does **not** open a second `AudioRecord`. This avoids mic conflicts and keeps audio capture
centralized. Android SpeechRecognizer (fallback) manages its own audio via the system service and
is unaffected.

**Remaining limitations:**
- Vosk recognition quality in production requires model assets on the device (push via ADB — see README)
- End-to-end recognition cannot be tested in unit tests (Vosk JNI not available on JVM); only
  model availability detection and JSON parsing are covered by unit tests
- Vosk `setWords(true)` enables word-level timestamps in results; these are available in the JSON
  but are not currently consumed (only the `text` field is extracted)

---

## Iteration 19 — Local Model Provisioning System ✅

**Goal:** Eliminate the manual ADB model installation requirement. Automatically download and
install the Vosk speech model when missing. Add a generic model-management layer that can later
support additional models (e.g. Gemma 4 E2B).

**Motivation:**
- Vosk requires model assets at `filesDir/vosk-model-small-en-us` before it can run
- Previous setup required manual `adb push` — not viable for normal users
- Users had no visibility into why transcription wasn't using the Vosk backend
- A generic provisioning layer is needed to scale to future on-device models

**Changes:**
- [x] Introduce `:modelmanager` module — generic local model management infrastructure:
  - `ModelType` enum (`STT`, `LLM`) — model family classification
  - `LocalModelDescriptor` data class — id, type, purpose, downloadUrl, installDirName, archiveRootDir, version, optional sha256
  - `ModelInstallState` sealed class — `NotInstalled`, `Queued`, `Downloading(progress)`, `Unpacking`, `Verifying`, `Ready`, `Failed(reason)`
  - `LocalModelManager` interface — `stateOf()`, `isReady()`, `ensureInstalled()`, `retry()`
  - `DefaultLocalModelManager` — downloads archive via `HttpURLConnection`, extracts via `ZipInputStream`, strips archive root prefix, uses staging directory for atomic install; state exposed as `StateFlow`
  - `KnownModels` object — registry of predefined model descriptors; currently contains `VOSK_SMALL_EN_US`; includes commented-out placeholder for future `GEMMA_4_E2B`
- [x] Implement Vosk model as first managed model (`KnownModels.VOSK_SMALL_EN_US`):
  - Downloads `vosk-model-small-en-us-0.22.zip` (~40 MB) from alphacephei.com
  - Extracts to `filesDir/vosk-model-small-en-us` with root-dir stripping
  - Readiness check mirrors `VoskLocalTranscriber.isModelAvailable()`: looks for `am/final.mdl` or flat `final.mdl`
- [x] Add `INTERNET` permission to `app/src/main/AndroidManifest.xml`
- [x] Register `:modelmanager` in `settings.gradle.kts`
- [x] Add `:modelmanager` dependency to `ui/build.gradle.kts`
- [x] Add `voskModelInstallState: ModelInstallState?` to `MainUiState`
- [x] Update `MainViewModel`:
  - Creates `DefaultLocalModelManager` (bound to `viewModelScope`)
  - Calls `ensureInstalled(VOSK_SMALL_EN_US.id)` when transcription is enabled at startup or on pref change
  - Observes model state flow and mirrors it into `MainUiState`
  - Exposes `retryVoskModelInstall()` for UI retry action
- [x] Add `ModelProvisioningCard` composable to `MainScreen`:
  - Shown when transcription is enabled and model is not yet `Ready`
  - Progress bar + byte-level progress text during `Downloading`
  - Indeterminate spinner for `Queued`, `Unpacking`, `Verifying`
  - Error message + Retry button on `Failed`
  - Disappears automatically when model becomes `Ready`
- [x] Add `DefaultLocalModelManagerTest` covering:
  - Readiness detection from disk (`am/final.mdl`, flat `final.mdl`, absent directory)
  - `ensureInstalled` is a no-op when already Ready
  - `ensureInstalled` transitions to Queued then Failed on no-network
  - `retry` is a no-op when not Failed; re-starts provisioning from Failed state
  - `knownModels` registry contains Vosk model
  - Unknown model id throws
  - `isInstalledOnDisk` path resolution
  - `extractArchive` strips root prefix, handles missing directory entry, removes stale install
  - `sha256Hex` correct digest for known content
- [x] Update README.md with model provisioning section
- [x] Update `phase1_architecture.md` with `:modelmanager` module documentation
- [x] Update `plan.md`

**Architecture note:** `DefaultLocalModelManager` is designed to be fully model-agnostic. Adding
Gemma 4 E2B support in a future iteration requires only:
1. Adding a `GEMMA_4_E2B` entry to `KnownModels`
2. Optionally extending `isInstalledOnDisk` with LLM-specific readiness heuristics
3. Calling `ensureInstalled("gemma-4-e2b")` in the appropriate ViewModel

**Known limitations (first iteration):**
- Downloads are not resumable. A mid-download process kill discards the partial archive; provisioning restarts on next app launch.
- No WorkManager scheduling. Provisioning runs in `viewModelScope` and is cancelled if the ViewModel is cleared before download completes.
- No automatic retry on network failure; user must press Retry in the UI.
- No per-model download quotas or WiFi-only gating.

---

## Iteration 7 — Polish and QA

**Goal:** Release-candidate quality.

- [ ] End-to-end UI tests (Espresso / Compose test)
- [ ] Fix all lint warnings
- [ ] ProGuard rules
- [ ] Accessibility audit (TalkBack, font scaling)
- [ ] README updated with screenshots

---

## Iteration 8 — Whisper.cpp Alternative STT Backend

**Goal:** Add Whisper.cpp as a real on-device STT backend to enable transcript quality comparison, especially for accented English (e.g. Indian English). Use `ggml-tiny.en.bin` as the default model.

**Motivation:** Vosk transcript quality for accented English speakers is inconsistent. Whisper.cpp may provide better results. This iteration adds the backend behind the existing routing abstraction so both can be evaluated side-by-side.

- [x] **Extend model provisioning for single-file models**
  - Add `ModelArchiveFormat` enum (`ZIP`, `SINGLE_FILE`)
  - Extend `LocalModelDescriptor` with `archiveFormat` and `singleFileName` fields
  - Update `DefaultLocalModelManager.provision()` to handle `SINGLE_FILE` (direct binary placement, no extraction)
  - Update `isInstalledOnDisk()` to check single-file binary existence
  - Add `installSingleFile()` helper
  - Register `WHISPER_GGML_SMALL` in `KnownModels`:
    - Model ID: `whisper-ggml-tiny-en`
    - File: `ggml-tiny.en.bin`
    - URL: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin`
    - Install path: `filesDir/whisper/ggml-tiny.en.bin`
- [x] **Add `WhisperCpp` to `TranscriptionBackend` enum**
- [x] **Add `WhisperNative.kt`** — JNI bridge for whisper.cpp native library
  - Attempts `System.loadLibrary("whisper")` at init; `isAvailable` flag
  - Declares `external` functions: `whisperInit`, `whisperFull`, `whisperFullNSegments`, `whisperFullGetSegmentText`, `whisperFree`
  - Native library ships as `transcription/src/main/jniLibs/*/libwhisper.so` (compiled externally, not in repo)
- [x] **Add `WhisperCppLocalTranscriber.kt`**
  - Consumes shared PCM audio via `setAudioSource()` (same as Vosk)
  - Buffers 5-second PCM chunks, converts Short→Float, runs inference via `WhisperRunner`
  - Emits **Final-only** `TranscriptUpdate` (no streaming partials — chunk-based by design)
  - Reports `ModelUnavailable` if model file absent or native library not loaded
  - Full lifecycle: `start()`/`stop()`, off main thread (IO dispatcher)
  - `activeBackend` always returns `TranscriptionBackend.WhisperCpp`
- [x] **Add `WhisperRunner` interface + `WhisperNativeRunner` + `FakeWhisperRunner`**
  - Testability: tests inject `FakeWhisperRunner` without native library
- [x] **Backend selection preference**
  - Add `preferWhisperBackend: Boolean` to `UserPreferences` (default: `false`)
  - Persist in `DataStoreAppSettings`
  - `MainViewModel.recreateSessionManager()` selects `WhisperCppLocalTranscriber` when `true`
  - Provisioning trigger routes to `triggerWhisperModelProvisioning()` when Whisper is selected
- [x] **UI changes**
  - Add `whisperModelInstallState: ModelInstallState?` to `MainUiState`
  - `MainViewModel.observeModelState()` watches both Vosk and Whisper model states
  - Add `retryWhisperModelInstall()` method
  - `SettingsScreen`: new "Use Whisper.cpp backend" toggle (shown when transcription is enabled)
  - `SettingsViewModel.updatePreferWhisperBackend(Boolean)`
- [x] **Tests**
  - `WhisperCppLocalTranscriberTest`: model availability checks, lifecycle, ModelUnavailable paths, Error on bad init context, Listening state, transcript emission with `FakeWhisperRunner`, model path consistency with `KnownModels`
  - `DefaultLocalModelManagerTest`: SINGLE_FILE `isInstalledOnDisk`, `installSingleFile` placement and replacement, Whisper model in `knownModels`, SINGLE_FILE readiness at init
- [x] **Documentation**: Update README.md, phase1_architecture.md, plan.md

**Architecture notes:**
- Whisper is integrated behind the existing `LocalTranscriber` interface and `RoutingLocalTranscriber` — no changes to session orchestration
- Chunk-based inference is honest about semantics: Final-only updates, inherent latency up to chunk duration
- `RoutingLocalTranscriber` fallback to Android SR works identically regardless of whether Vosk or Whisper is the primary
- `ModelArchiveFormat.SINGLE_FILE` makes the provisioning system directly usable for future Gemma weights

**Remaining limitations:**
- `libwhisper.so` is not bundled in this repo and must be compiled from source using Android NDK
- Without the native library, the Whisper backend always reports `ModelUnavailable` and falls back to Android SR
- Whisper ggml-tiny.en model is ~75 MB — larger than Vosk (~40 MB) but small enough for default provisioning
- Chunk-based inference adds up to 5 seconds latency vs Vosk's streaming frame output
- No WorkManager for large background downloads; Whisper model download may be interrupted if app is backgrounded

---

## Deferred / Out of Scope (unless explicitly requested)

- Cloud upload or sync
- LLM-based feedback
- Multi-language support
- Backend API
- Analytics / crash reporting
- DI framework (Hilt / Koin)

---

## Phase 2 — Whisper Backend Stabilisation (iteration 2)

**Goal:** Fix native runtime packaging, move provisioning to WorkManager, tighten active-backend provisioning, improve chunked WPM UX, add model download metadata.

### Changes

- [x] **CMake/JNI native packaging** (`transcription`)
  - `src/android/transcription/src/main/cpp/CMakeLists.txt`: uses `FetchContent` to pull whisper.cpp v1.7.2 from GitHub during `./src/android/gradlew assembleDebug` CMake configure
  - `transcription/src/main/cpp/whisper_jni.cpp`: JNI bridge matching `WhisperNative.kt` `external` function signatures
  - `transcription/build.gradle.kts`: adds `externalNativeBuild { cmake { path } }`, `ndkVersion = "26.3.11579264"`, `ndk.abiFilters = ["arm64-v8a", "x86_64"]`
  - No manual build step required: first `./src/android/gradlew assembleDebug` downloads and compiles whisper.cpp automatically

- [x] **Model metadata** (`modelmanager/LocalModelDescriptor`)
  - Added `displayName: String`, `approxSizeMb: Int`, `wifiRecommended: Boolean`
  - `KnownModels.VOSK_SMALL_EN_US`: displayName="Vosk small (en-US)", approxSizeMb=40, wifiRecommended=false
  - `KnownModels.WHISPER_GGML_TINY_EN`: displayName="Whisper tiny.en (ggml)", approxSizeMb=75, wifiRecommended=false
  - `KnownModels.WHISPER_GGML_BASE_EN`: displayName="Whisper base.en (ggml)", approxSizeMb=142, wifiRecommended=true

- [x] **WorkManager provisioning** (`modelmanager`)
  - Added `work-runtime-ktx:2.9.1` to `libs.versions.toml` and `modelmanager/build.gradle.kts`
  - `ModelDownloadWorker` (CoroutineWorker): downloads via `HttpURLConnection`, optional SHA-256 verify, ZIP extract or single-file install, reports progress via `setProgress()` Data
  - `WorkManagerLocalModelManager` (implements `LocalModelManager`): schedules `ModelDownloadWorker` (REPLACE policy), maps `WorkInfo` → `StateFlow<ModelInstallState>` via `getWorkInfosForUniqueWorkFlow()`
  - Provisioning now survives app backgrounding — WorkManager reschedules interrupted workers

- [x] **Active-backend-only provisioning** (`ui/MainViewModel`)
  - Replaced `DefaultLocalModelManager` with `WorkManagerLocalModelManager`
  - Consolidated two provisioning methods into `triggerActiveBackendModelProvisioning(prefs)` — provisions only the active backend's model
  - `observeActiveModelState()` restarts when preferences change, observes only the active model
  - `MainUiState` simplified: replaced `voskModelInstallState` + `whisperModelInstallState` with unified `activeModelInstallState`, `activeModelDisplayName`, `activeModelApproxSizeMb`, `activeModelWifiRecommended`
  - `MainScreen.ModelProvisioningCard` shows model name, size, and Wi-Fi recommendation

- [x] **Chunk-based WPM hold** (`transcription`)
  - `RollingTranscriptWpmCalculator`: new `chunkBased` mode; when enabled, `heldWpm` is preserved for `wpmHoldDurationMs` (default 2× chunk duration = 10 s) after each Final update, preventing oscillation between chunks
  - `setChunkBased(Boolean)` allows runtime reconfiguration
  - `TranscriptWpmSnapshot` gains `isChunkBased: Boolean`, `lastChunkAtMs: Long?`
  - `TranscriptDebugState` gains `isChunkBased: Boolean`, `lastChunkAtMs: Long?`
  - `SpeechCoachSessionManager.startTranscriptionCollector()` sets `chunkBased=true` when active backend is `WhisperCpp`

- [x] **Tests**
  - `RollingTranscriptWpmCalculatorTest`: 9 new chunk/hold tests covering chunk mode, hold behavior, expiry, reset
  - `WorkManagerLocalModelManagerTest`: disk-check logic (SINGLE_FILE and ZIP), model metadata validation, work-name uniqueness

- [x] **Documentation**: Updated README.md, phase1_architecture.md, plan.md

**Architecture notes:**
- `WorkManagerLocalModelManager` replaces `DefaultLocalModelManager` in production; `DefaultLocalModelManager` is retained for unit tests
- The CMake `FetchContent` approach mirrors standard Android native library patterns; no hidden manual steps
- `chunkBased` WPM hold is transparent to the pace decision layer — `selectPaceSignal()` sees the same smooth WPM signal regardless of backend
- `LocalModelDescriptor` metadata fields (`displayName`, `approxSizeMb`, `wifiRecommended`) are generic — future Gemma models will use the same fields

**Remaining limitations:**
- Downloads are not resumable (partial temp file deleted; download restarts from zero)
- No automatic retry on transient failure — user must press Retry
- First CMake build requires network to fetch whisper.cpp source (~100 MB)
- Whisper inference is CPU-only (no GPU/Metal acceleration) — larger models may be slow on low-end devices


---

## Phase 2 — Whisper Benchmarking and Preprocessing Instrumentation (iteration 3)

**Goal:** Compare Whisper tiny/base behavior fairly, expose chunking tradeoffs, and surface preprocessing/timing evidence without destabilizing live microphone sessions.

### Changes

- [x] **Selectable Whisper models** (`settings`, `ui`, `modelmanager`)
  - Persisted `whisperModelId` in `UserPreferences` / `DataStoreAppSettings`
  - `SettingsScreen` now exposes `tiny.en` and `base.en` selection when Whisper is enabled
  - `MainViewModel` provisions and activates the exact selected Whisper descriptor

- [x] **Shared Whisper preprocessing and chunking** (`transcription`)
  - Added `WhisperChunkingConfig`, `WhisperAudioChunkAccumulator`, `WhisperAudioInputReport`, and `runWhisperInferenceChunk(...)`
  - Live Whisper and file benchmarks now share the same normalization, resampling, chunk, and overlap logic
  - Fixed a concrete preprocessing bug: file audio with non-16 kHz sample rates is now resampled before Whisper inference instead of being treated as already 16 kHz

- [x] **Benchmark harness and UI** (`transcription`, `ui`)
  - Added `WhisperBenchmarkRunner`, `WhisperBenchmarkConfig`, `WhisperBenchmarkReport`, `WhisperBenchmarkResult`
  - Added main-screen benchmark launcher/report cards for file-based comparison runs
  - Benchmark matrix currently compares tiny/base across the live default chunking and a longer-context overlap strategy

- [x] **Expanded diagnostics** (`transcription`, `ui`)
  - `TranscriptionDiagnostics` now includes selected/active model identity, chunk duration/overlap, preprocessing metrics, and timing metrics
  - `RoutingLocalTranscriber` preserves those details across primary/fallback routing

- [x] **Tests**
  - Added `WhisperBenchmarkRunnerTest`
  - Expanded `WhisperCppLocalTranscriberTest` and model-manager coverage for the new model-selection and diagnostics paths

### Architecture notes

- Benchmarking is file-based and separate from live microphone sessions so production coaching behavior stays stable
- Evidence collection is structured around the same preprocessing path used by live Whisper, which makes tiny/base and chunking comparisons defensible
- The new diagnostics are intended for engineering evaluation and debugging, not for changing the end-user feedback policy by themselves

### Known limitations

- No benchmark corpus ships in-repo; real quality comparison still depends on representative local recordings
- Benchmarks currently report transcript/timing/preprocessing evidence but do not compute WER against labeled ground truth
- Live Whisper still uses the default 2-second chunking strategy; alternate strategies are exposed through the benchmark path first


---

## Phase 1 — Issue 9: Whisper Runtime Diagnostics and Activation Fix

### Problem

Whisper was selected as the STT backend but real-device testing produced no transcript output. The session started and mic/VAD continued, but the transcript card showed nothing, transcript-derived WPM never activated, and the heuristic fallback remained active.

### Root Cause

**Critical bug:** `WhisperNative.kt` called `System.loadLibrary("whisper")` but the CMakeLists.txt build target is named `whisper_jni` (producing `libwhisper_jni.so`). The native library was never loaded — `WhisperNative.isAvailable` was always `false`. The transcriber immediately reported `ModelUnavailable` (misclassifying a native-library failure), and Android SR fallback activated silently.

**Secondary issues:**
- `ModelUnavailable` was overloaded for both "model file missing" and "native library not loaded" — not distinguishable by the user or UI
- Chunk duration of 5 seconds was too long for interactive use
- No explicit UI warning when Whisper was selected but the runtime couldn't activate
- `RoutingLocalTranscriber` only fell back on `ModelUnavailable`, not on the distinct new status
- `PaceSignalSelector` and `LiveSessionPresentation` did not handle the new status

### Changes

- [x] **Library name fix** (`transcription/WhisperNative.kt`)
  - `System.loadLibrary("whisper")` → `System.loadLibrary("whisper_jni")`
  - This is the critical fix: the loaded name now matches the CMake build target `whisper_jni`

- [x] **Distinct `NativeLibraryUnavailable` status** (`transcription/TranscriptionEngineStatus.kt`)
  - New enum value `NativeLibraryUnavailable`: raised when `System.loadLibrary("whisper_jni")` fails
  - `ModelUnavailable` is now exclusively used for "model file absent on disk"
  - These two failure modes are now distinguishable in the UI and debug panel

- [x] **Whisper transcriber** (`transcription/WhisperCppLocalTranscriber.kt`)
  - Reports `NativeLibraryUnavailable` (not `ModelUnavailable`) when `runner.isAvailable == false`
  - Reduced default chunk duration: `CHUNK_DURATION_SAMPLES = SAMPLE_RATE_HZ * 2` (2 seconds, was 5)
  - Updated KDoc to reflect 2-second default and the two distinct failure statuses

- [x] **Routing fallback** (`transcription/RoutingLocalTranscriber.kt`)
  - Fallback condition now includes `NativeLibraryUnavailable || ModelUnavailable`

- [x] **WPM hold duration** (`transcription/RollingTranscriptWpmCalculator.kt`)
  - Default `chunkDurationMs` changed to 2000 ms (was 5000 ms) to match the new chunk size
  - `wpmHoldDurationMs` defaults to 4 s (2× chunk), was 10 s

- [x] **Session state** (`session/TranscriptDebugState.kt`, `session/PaceSignalSelector.kt`)
  - Added `NativeLibraryUnavailable` to `TranscriptDebugStatus` enum
  - `resolveTranscriptDebugStatus()` maps `NativeLibraryUnavailable` engine status → `NativeLibraryUnavailable` debug status
  - `selectPaceSignal()` treats `NativeLibraryUnavailable` the same as `ModelUnavailable` (heuristic fallback)

- [x] **UI — explicit failure messaging**
  - `MainUiState`: added `whisperSelected: Boolean` and `whisperNativeLibLoaded: Boolean`
  - `MainViewModel`: populates new fields from `prefs.preferWhisperBackend` and `WhisperNative.isAvailable`
  - `MainScreen`: persistent `WhisperRuntimeWarningCard` shown when `whisperSelected && !whisperNativeLibLoaded`; debug panel adds "Whisper selected" and "Whisper native lib" rows; `transcriptStatusLabel` handles new status
  - `LiveSessionPresentation`: `NativeLibraryUnavailable` case added to `resolveTranscriptSurfacePresentation()`

- [x] **Tests**
  - `WhisperCppLocalTranscriberTest`: `start reports NativeLibraryUnavailable when native library not available`, `stop resets status to Disabled after NativeLibraryUnavailable`, `default chunk duration is 2 seconds at 16kHz`; updated existing native-library test to assert `NativeLibraryUnavailable`
  - `RoutingLocalTranscriberTest`: new test for fallback when primary reports `NativeLibraryUnavailable`
  - `TranscriptDebugStateTest`: two new tests for `NativeLibraryUnavailable` status resolution

- [x] **Documentation**: Updated README.md, docs/phase1_architecture.md, docs/plan.md

### Runtime availability verification

Whisper readiness is now determined through a clear sequence:

1. **Library loads?** `WhisperNative.isAvailable` via `System.loadLibrary("whisper_jni")`
2. **Model file present?** `WhisperCppLocalTranscriber.isModelAvailable()` checks `filesDir/whisper/ggml-small.bin`
3. **Init succeeds?** `runner.init(modelPath)` returns a positive context handle → `Listening`
4. **Audio reaches backend?** Shared `Flow<AudioFrame>` collected in inference loop
5. **Chunk fires?** Buffer reaches `CHUNK_DURATION_SAMPLES = 32,000` (2 s) → inference runs
6. **Transcript emits?** Non-empty segments → `Final` `TranscriptUpdate` emitted

### Fallback behavior

| Primary failure | Status reported | Fallback triggered | UI message |
|---|---|---|---|
| Native lib not loaded | `NativeLibraryUnavailable` | Yes — Android SR | "Whisper runtime unavailable — using Android fallback" |
| Model file missing | `ModelUnavailable` | Yes — Android SR | "Dedicated STT model not installed" |
| Init returned 0 / primary init error | `Error` before `Listening` | Yes — Android SR | Explicit primary-init-failed fallback reason in transcript diagnostics |

When `whisperSelected && !whisperNativeLibLoaded`, a persistent error card is shown on the main screen **outside of sessions** — not just in the debug panel.

### Remaining limitations

- Whisper inference on the `small` model is CPU-only — may be slow on low-end devices
- First build requires network access to fetch whisper.cpp source (~100 MB via CMake FetchContent)
- Whisper native runtime is packaged only for `arm64-v8a` and `x86_64`; 32-bit-only devices will surface native-runtime-unavailable diagnostics and use fallback
- Chunk-based transcription (2-second chunks) has inherent latency; it is not low-latency streaming

### Verification notes

- Verified build output: `System.loadLibrary("whisper_jni")` matches the produced and packaged `libwhisper_jni.so`
- Verified packaging: debug APK contains `libwhisper_jni.so` for `arm64-v8a` and `x86_64`
- Added first-class backend diagnostics so selected backend, active backend, fallback reason, native-load result, audio reachability, chunk processing, and transcript update counts are visible without guessing
