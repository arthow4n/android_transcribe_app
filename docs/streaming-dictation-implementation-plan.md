# Streaming dictation: implementation handoff

Status: implemented on this branch; physical-device latency validation remains.
Baseline: `feature/language-support`, commit `c0543ac`.

## Required behavior and scope

When enabled, decode microphone audio while the user speaks. Keep all partial
text inside the app. On Stop, drain pending audio, finalize, convert Chinese
output if configured, and insert the complete result exactly once. The purpose
is to reduce the delay between Stop and insertion.

First version: the dedicated voice keyboard (`RustInputMethodService`) using
Nemotron 3.5's native cache-aware streaming. Retain batch transcription for
Whisper, unsupported models, and when the setting is off. Do not implement
overlapping Whisper windows, live insertion, model downloads, or changes to
subtitles, file transcription, RecognitionService, or the voice popup.

Shared code must not accidentally enable this for the voice popup: pass an
explicit keyboard-only recording mode into the shared recording function,
or introduce a separate keyboard entry point that reuses microphone helpers.
Keep other callers on the existing batch mode.

## Read these files before editing

| File | What to inspect / change |
| --- | --- |
| `src/voice_session.rs` | `VoiceSessionState`, `start_recording`, `stop_recording`, `cancel_recording`; capture currently appends PCM to `audio_buffer` and decoding begins only at Stop. |
| `src/engine.rs` | `Engine`, `Engine::load`, `Engine::run`, `transcribe_shared`, `set_language`, `reset`; own model options, capability checks and streaming decode here. |
| `src/ime.rs` | Keyboard JNI entry points; select the recording mode and cancel on cleanup. |
| `src/recognize.rs` (locate actual popup bridge with `rg`) | Find every `voice_session::start_recording` caller and preserve batch behavior. |
| `app/src/main/java/dev/notune/transcribe/RustInputMethodService.java` | Record/Stop, `updateRecordButtonUI`, `updateUiState`, `onStatusUpdate`, `onTextTranscribed`, pending text, keyboard switching and lifecycle. |
| `app/src/main/java/dev/notune/transcribe/MainActivity.java` | `bindMarkerSwitch` and existing setting bindings. |
| `app/src/main/res/layout/activity_main.xml` | Add a setting beside the existing voice keyboard settings. |
| `app/src/main/res/values/strings.xml` | Setting label, explanation and user-visible status/error strings. |
| `src/chinese.rs` | Reuse conversion on the complete final text. |

The locked dependency is `transcribe-cpp` / `transcribe-cpp-sys` 0.1.3.
Inspect its actual source under Cargo's registry before implementing; do not
assume APIs from a newer upstream checkout. Relevant modules:

- `session.rs`: `Session::stream(&RunOptions, &StreamOptions)`, returned borrowed
  `Stream`, `feed`, `finalize`, text snapshots, cancellation and drop behavior.
- `streaming.rs`: `StreamOptions`, `StreamText.full`, `.committed`, `.tentative`.
- `family.rs`: `StreamExtension::ParakeetStream`, `ParakeetStreamOptions`.
- `model.rs` and sys headers: capability fields, extension acceptance, model
  variant identity and supported streaming options.

These APIs exist in the installed version. Exact capability field names and
the model's accepted latency configuration still need checking. Do not edit
Cargo registry files. Avoid a dependency upgrade unless the pinned runtime
actually lacks a required capability; document that finding first.

## Implementation sequence

### 1. Add the opt-in setting

Use marker file `ime_streaming`, absent = disabled. Bind it through the existing
marker-switch helper. Label: “Process while recording”. Description: “Reduce
the wait after stopping with supported speech models. Text is inserted once
you stop. Other models process the recording after you finish.”

Read it at each keyboard recording start. No model reload is needed. Show the
chosen mode in keyboard status: “Listening…” or “Listening (processes after
Stop)”. Do not promise streaming merely because a model's name contains that
word. Avoid adding a latency slider in this first version.

### 2. Establish capability and language handling

At model load, retain the capability/variant information needed to choose
native Nemotron 3.5 streaming. Require both the intended variant and the
runtime's relevant streaming capability/extension support. A generic streaming
flag may include buffered streaming; do not use that alone to select this path.

Snapshot recognition language, strict-language flag, task and Chinese conversion
choice for the recording. Construct streaming RunOptions from that snapshot.
Keep existing locale-to-primary retry and strict-mode semantics, but retry only
an identified language rejection, before any audio has been fed. Do not treat
every Unsupported error as a language error: it can indicate unsupported task
or streaming options. Verify how the pinned backend reports these failures.

Nemotron's documented Mandarin locale is `zh-CN`, not `zh-TW`. Do not silently
add a Taiwan-to-China mapping as part of this task. Users can select `zh-CN`
in Models and select Taiwan Traditional output separately. For an unsupported
strict language, show the error; never silently choose automatic detection.

Start with the backend's validated default lookahead (verify the pinned source;
the intended conservative setting is right context 13). Do not confuse the
audio transport chunk size with the model's trained lookahead.

### 3. Introduce a recording worker and bounded audio transport

Add a small helper module (suggested `src/streaming_dictation.rs`, register it
in `src/lib.rs`) for queue/lifecycle coordination. Keep model internals private
to `engine.rs`; expose a worker entry point there instead of making Session
public.

One background thread owns the engine lock and borrowed Stream for the entire
stream lifetime. Construct and destroy Stream inside that thread. Do not try
to store a Stream borrowing Session alongside its owner in VoiceSessionState,
use unsafe lifetime extensions, or move a locked guard between threads.

The microphone callback must never wait for inference, model loading, JNI, or
engine locks. Feed captured 16 kHz mono PCM through bounded nonblocking storage.
Use a sample-count budget (initially 30 seconds / 480,000 samples), not merely
a count of callback messages. Drain into small batches on the worker; an
initial transport target of 100 ms / 1,600 samples is reasonable. Preserve
sample order and feed every accepted sample once, including the final short
batch. Keep existing meter and endpointing behavior.

Use a separate stop/cancel flag or control channel so a full audio queue cannot
prevent Stop or Cancel. On overflow, fail clearly and discard the result;
never drop audio and then report a successful transcription. Do not retain a
second unbounded full recording in streaming mode.

Model loading may overlap capture: buffer within the same limit while waiting.
Cancellation must work during loading as well. If another operation owns the
engine, avoid blocking the UI and show a waiting status; honor cancellation
and the queue limit while waiting. Audit `set_language` and `reset`: streaming
holds the engine much longer than a batch call, so no UI/JNI path may wait on
that lock. Route updates asynchronously or defer them to the next recording.

### 4. Define lifecycle explicitly

Use states equivalent to Idle, Starting, Recording, Finishing and terminal
Success/Error/Canceled. A recording ID/generation must accompany worker results
so a delayed callback cannot paste into a subsequent recording.

- Start: reject a second active session, snapshot options, create transport and
  worker, begin capture. Native startup failure must restore Java's idle state.
- Stop: end capture, mark input closed after the producer has finished, drain
  accepted PCM, call finalize exactly once, read the final authoritative text.
- Success: drop Stream before accessing the converter, convert the full text
  once, deliver one terminal result. Empty/no-speech results insert nothing.
- Cancel: set cancellation independently of queue capacity, request the library
  cancellation token for in-flight inference, drop/reset Stream, discard text.
  Recheck recording ID and cancellation immediately before delivering success.
- Failure: release capture/stream/engine resources and restore controls. Surface
  incomplete/truncated output as an error rather than silently pasting it.
- Cleanup/destruction: cancel active work and suppress callbacks to a retired
  recording. Do not join a potentially slow worker on the Android main thread.

Inspect the library CancelToken API before wiring it; reset cancellation for
each new run so cancellation cannot poison later recordings.

Fallback to batch is allowed when the setting is off or eligibility is false
before streaming starts. After feeding starts, a stream failure is an error;
do not restart batch with only the remaining audio.

### 5. Preserve one-time insertion and keyboard behavior

Reuse `onTextTranscribed` / `commitTranscribedText` for the final result,
including pending text when no editor is focused, selection highlighting,
audio-focus restoration and switch-back behavior. Never invoke it with partial
snapshots and never concatenate successive `full` snapshots (they can rewrite).

Prefer an explicit session-state callback over deriving busy state from English
status text. Disable language buttons immediately on Start, through Finishing;
enable them on every terminal path. Stop must remain usable while recording.
Prevent repeated Stop and quick Start from creating duplicate final callbacks.
Existing ready-before-text callback ordering must not reopen controls before
the terminal result is handled.

Honor “Record in background”: hiding the keyboard continues capture when on,
and cancels/discards when the stop-on-hide marker is set. Preserve automatic
recording and permission failure recovery. No live transcript preview required.

## Verification and acceptance

Use a fake streaming backend or transport harness for meaningful lifecycle
tests without downloading a model:

- PCM ordering and exact sample counts, including final short chunk.
- Stop drains before finalize; finalize and successful delivery each occur once.
- Cancel during starting, feed and finishing produces no text callback.
- Queue overflow fails visibly; no silent sample loss or partial success.
- Stale worker results are ignored after a new recording begins.
- Unsupported model chooses batch before feeding; strict language errors remain
  errors. Existing Chinese conversion tests continue passing.

Run host Rust tests and the full ARM64 debug build with the local toolchain.
Inspect the existing repository build instructions and environment under
`/home/hevar/.local/share/android-transcribe-toolchain`; do not install a second
toolchain. Run `git diff --check`. Do not claim device validation from a build.

On a physical phone, compare streaming on/off with the same Nemotron model,
language, CPU-thread setting and fixed recordings (approximately 5, 30 and 90
seconds). Log capture start/stop, first feed, received/fed sample counts, maximum
queue depth, finalize completion and final text delivery. Do not log transcripts
or microphone content by default. Measure Stop-to-insertion latency and whether
the worker keeps up; do not substitute desktop benchmarks for phone results.

Check English and Swedish; test Mandarin using `zh-CN` plus Taiwan conversion.
Exercise instant Stop, silence, repeated Stop, cancel/restart, hide/show, keyboard
switching, missing permission, model initially loading, and a concurrent engine
user. Test Whisper with the setting on and off to verify batch fallback. Verify
the other shared voice-session callers still behave as before.

Done means: one insertion after Stop, no lost/duplicated words due to transport,
no insertion after cancellation, no UI freeze, bounded buffering, and measured
latency results with limitations reported. If hardware cannot keep up, report
the backlog instead of promising instant completion.

## Delivery for the implementing agent

Work on `feature/language-support` and preserve unrelated changes. Implement in
reviewable steps: capability/options, worker lifecycle, microphone integration,
then UI. Update this document with actual validation results. Commit and push
with `[skip ci]` per the thread's standing instruction; keep fork Actions
disabled. Do not publish a release or overwrite a draft asset unless requested.
The implementation currently covers the keyboard-only Nemotron path described
above. The setting is `ime_streaming`, disabled by default. The worker uses a
30-second sample-count budget, 100 ms feed chunks, final-only insertion, and
batch fallback whenever the selected loaded model is not Nemotron 3.5 with the
Parakeet cache-aware streaming extension. Host tests cover queue splitting,
sample-budget overflow, and producer lifecycle; the full ARM64 debug build has
also passed. Device testing should record the latency and lifecycle scenarios
listed above before enabling this setting by default.
