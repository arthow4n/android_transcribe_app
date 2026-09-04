# Language Support Plan

Status: discussion draft

This branch tracks two related features:

1. Convert Simplified Chinese transcription output to Traditional Chinese.
2. Investigate language/accent steering so Swedish speech is less likely to be
   detected as another European language. One idea is a fixed reference-audio
   prompt, but model support and the correct mechanism still need validation.

## Feature 1: Traditional Chinese output

### Goal

Provide a simple, completely offline way to request Taiwan-standard Traditional
Chinese even when the selected speech model emits Simplified Chinese.

Language recognition and output orthography are separate concerns. Selecting
`zh-TW` should continue to tell the model which language/locale to recognize;
the conversion setting should control how recognized Chinese text is written.

### Proposed user experience

Add a **Chinese output** choice near the existing model language setting:

- **As transcribed** (default; no conversion)
- **Traditional Chinese (Taiwan)**

Keep the setting explicit rather than silently tying it to `zh-TW`. This lets a
user choose `zh-TW` for recognition while preserving the model's exact output,
and it also supports models that accept only `zh` or ignore language hints.

For the first version, use OpenCC's `s2tw` conversion profile. It converts
Simplified Chinese to Taiwan-standard Traditional characters without the more
opinionated Taiwan vocabulary substitutions performed by `s2twp`.

Open question: if Taiwan-specific vocabulary conversion is wanted (for example,
terms analogous to “software” becoming the Taiwan-preferred form), add it later
as a separate **Traditional Chinese (Taiwan wording)** choice using `s2twp`.

### Processing design

Conversion should happen in the shared Rust transcription layer after a model
returns text and before that text reaches JNI callbacks. This gives identical
behavior to:

- voice-input popup;
- Android `RecognitionService` clients;
- the voice keyboard;
- live-subtitle partial and final results;
- audio-file transcription.

The converter should be initialized once and reused. Conversion must be local,
deterministic, and safe to call repeatedly because live subtitles regenerate
partial hypotheses. Non-Chinese text, punctuation, whitespace, and numbers
should pass through unchanged.

Store the choice in the same small-file configuration style as the current
model settings. Changing it should reload or refresh the shared processing
configuration without restarting the app.

### Conversion engine

Use OpenCC dictionaries rather than a hand-written character table. Chinese
conversion is context-sensitive, so one-to-one replacement produces incorrect
results for ambiguous characters and phrases.

Preferred implementation direction: integrate the Apache-2.0 OpenCC engine and
bundle only the configuration/dictionaries needed for `s2tw`. Before choosing a
binding, make a small Android cross-compilation prototype and compare:

- official OpenCC C/C++ through its C API;
- a maintained Rust implementation compatible with OpenCC data.

Choose the option that preserves OpenCC behavior while adding the least APK
size, native-build complexity, and runtime memory. Record the exact dependency
version and include its license/notice in the app.

Android ICU transliteration and a custom character map are not preferred: they
do not provide OpenCC's phrase-aware conversion quality.

### Verification

Add unit/golden tests that cover:

- common Simplified → Taiwan Traditional character conversion;
- context-sensitive phrases where character-by-character conversion fails;
- mixed Chinese/Latin text, punctuation, emoji, and numbers;
- already-Traditional input;
- empty input and non-Chinese languages;
- repeat conversion of live-subtitle partial text;
- setting persistence and the default no-conversion behavior.

Manually verify at least the voice popup, keyboard, recognition service, live
subtitles, and file transcription with a model known to emit Simplified Chinese.

### Delivery sequence

1. Confirm whether `s2tw` or `s2twp` matches the desired output.
2. Prototype and measure the two OpenCC integration options on Android ARM64.
3. Add the persisted output setting and localized UI strings.
4. Apply conversion at the shared transcription boundary.
5. Add automated golden tests and complete the manual output-path checks.
6. Document third-party licensing and any APK-size change.

## Feature 2: Swedish language/accent steering

This feature is intentionally not designed yet. A fixed audio prompt helps only
if a model/runtime supports reference-audio or acoustic-prompt conditioning;
it cannot be assumed to work for every imported GGUF model. The investigation
should first catalog the prompt/language controls exposed by each supported
model family and by `transcribe.cpp`.

The existing explicit `sv-SE` language hint is the baseline. Possible next
steps include preserving a stronger model-specific language constraint, text
decoder prompts where supported, or reference audio only for models that truly
accept it. The UI should describe unsupported steering per model rather than
silently pretending it is active.
