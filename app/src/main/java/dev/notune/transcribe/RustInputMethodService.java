package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.button.MaterialButtonToggleGroup;

public class RustInputMethodService extends InputMethodService {
    
    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView statusView;
    private TextView lastWpmView;
    private Spinner modelSpinner;
    private TextView statsView;
    private TextView hintView;
    private View recordContainer;
    private android.widget.ImageView micIcon;
    private ProgressBar progressBar;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordCircle;
    private MaterialButtonToggleGroup languageGroup;
    private ArrayAdapter<String> modelAdapter;
    private final ArrayList<String> modelFiles = new ArrayList<>();
    private boolean updatingModelSpinner = false;
    // Night flag the current input view was inflated with, so it can be rebuilt
    // if the theme preference changes while this process stays alive.
    private boolean viewIsNight = false;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceLongPressRunnable;
    private boolean spaceLongPressed = false;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    // Whether an editor is currently focused/started for input. Tracked via
    // onStartInput/onFinishInput because getCurrentInputConnection() returns a
    // non-null no-op connection when nothing is focused, so commitText would be
    // silently dropped.
    private boolean inputActive = false;
    // Live recording metrics. Audio processing happens on the Rust worker;
    // the main-thread ticker owns elapsed-time rendering so it stays smooth
    // even when native inference is busy.
    private long recordingStartedAtMs = 0L;
    private long lastRecordingDurationMs = 0L;
    private long processedAudioMs = 0L;
    private int processedWords = 0;
    private float currentProcessingSpeed = -1f;
    private float averageProcessingSpeed = -1f;
    private long lastStreamingStatsAtMs = 0L;
    private final Runnable statsTicker = new Runnable() {
        @Override
        public void run() {
            if (isRecording || isProcessingStatus()) {
                updateStatsView();
                mainHandler.postDelayed(this, 250L);
            }
        }
    };
    // Whether the keyboard window is currently on screen. Some frameworks
    // (notably OEM builds) call onWindowShown again for events that don't
    // follow an onWindowHidden, e.g. tapping the text area to move the
    // cursor while the keyboard stays visible. Auto-record must only fire on
    // a genuine hidden -> shown transition, or a cursor tap starts a
    // recording the user never asked for.
    private boolean windowVisible = false;
    // Transcribed text waiting to be committed because no editor was focused
    // when transcription finished. This happens on long transcribes where the
    // target field (e.g. a web field in Firefox/Gemini) drops focus while we
    // process audio. Flushed from onStartInputView once a field is focused
    // again so the text is never lost.
    private String pendingCommitText = null;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Throwable t) {
            // Native may be unavailable (e.g. wrong-ABI emulator); don't crash the IME.
            Log.e(TAG, "Error in initNative", t);
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            // The IME is a non-AppCompat Service in a separate process, so
            // AppCompat's delegate can't theme it. Build a context that is
            // night-aware (per the saved preference), wears the Material 3 theme,
            // and picks up Material You dynamic color — matching the app.
            Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
            viewIsNight = ThemePrefs.isNight(night);
            Context themed = DynamicColors.wrapContextIfAvailable(
                    new ContextThemeWrapper(night, R.style.AppTheme));
            View view = LayoutInflater.from(themed).inflate(R.layout.ime_layout, null);
            inputView = view;

            // Handle window insets for avoiding navigation bar overlap
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = insets.getSystemWindowInsetBottom();
                int originalPaddingBottom = v.getPaddingTop();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), originalPaddingBottom + paddingBottom);
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            lastWpmView = view.findViewById(R.id.ime_last_wpm_text);
            if (lastWpmView != null) {
                lastWpmView.setOnClickListener(v -> openAppStats());
            }
            showLastWpmIfAvailable();
            modelSpinner = view.findViewById(R.id.ime_model_spinner);
            statsView = view.findViewById(R.id.ime_stats_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordCircle = view.findViewById(R.id.ime_record_circle);
            hintView = view.findViewById(R.id.ime_hint);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            View selectAllButton = view.findViewById(R.id.ime_select_all);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);
            languageGroup = view.findViewById(R.id.ime_language_group);

            setupModelSpinner();
            setupLanguageShortcuts();

            selectAllButton.setOnClickListener(v -> selectAllText());

            switchKeyboardButton.setOnClickListener(v -> switchLanguageOrKeyboard());
            switchKeyboardButton.setOnLongClickListener(v -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showInputMethodPicker();
                    return true;
                }
                return false;
            });

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Long-press runnable for space to switch language
            spaceLongPressRunnable = new Runnable() {
                @Override
                public void run() {
                    spaceLongPressed = true;
                    if (spaceButton != null) {
                        spaceButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    }
                    switchLanguageOrKeyboard();
                }
            };

            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                        }
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        spaceLongPressed = false;
                        v.setPressed(true);
                        mainHandler.postDelayed(spaceLongPressRunnable,
                                android.view.ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!isPointInsideView(v, event.getX(), event.getY())) {
                            mainHandler.removeCallbacks(spaceLongPressRunnable);
                            v.setPressed(false);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        mainHandler.removeCallbacks(spaceLongPressRunnable);
                        v.setPressed(false);
                        if (!spaceLongPressed) {
                            InputConnection ic = getCurrentInputConnection();
                            if (ic != null) {
                                ic.commitText(" ", 1);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceLongPressRunnable);
                        v.setPressed(false);
                        spaceLongPressed = false;
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    if (editorInfo == null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                        return;
                    }
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordContainer.setOnClickListener(v -> {
                if (!recordContainer.isEnabled()) return;

                // Check microphone permission
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (statusView != null) statusView.setText("No mic permission - grant in app");
                    if (hintView != null) hintView.setText("Open the app to grant permission");
                    return;
                }

                if (!ModelUtils.hasAnyModelInstalled(this)) {
                    if (statusView != null) statusView.setText(getString(R.string.models_none_installed));
                    if (hintView != null) hintView.setText(getString(R.string.models_open_settings_hint));
                    openModelsActivity();
                    return;
                }
                String active = ModelUtils.getActiveModel(this);
                if (active == null) {
                    if (statusView != null) statusView.setText(getString(R.string.models_none_selected));
                    if (hintView != null) hintView.setText(getString(R.string.models_open_settings_hint));
                    openModelsActivity();
                    return;
                }

                if (isRecording) {
                    stopRecording();
                    if (pauseAudioActive) {
                        audioPauser.abandon(this);
                        pauseAudioActive = false;
                    }
                    updateRecordButtonUI(false);
                } else {
                    if (isPauseAudioEnabled()) {
                        audioPauser.request(this);
                        pauseAudioActive = true;
                    }
                    updateRecordButtonUI(true);
                    startRecording(isStreamingEnabled());
                }
            });

            tintRecordButton(false);
            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        boolean wasVisible = windowVisible;
        windowVisible = true;
        if (!isRecording) {
            refreshModelSpinner();
            showLastWpmIfAvailable();
        }
        if (isRecording) {
            // A background recording is still running (record-in-background
            // setting): restore the recording UI.
            updateRecordButtonUI(true);
            return;
        }
        if (wasVisible) {
            // Not a real hidden -> shown transition (e.g. a cursor tap in the
            // text area); never auto-start a recording from here.
            return;
        }
        if (new File(getFilesDir(), "auto_record").exists()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                updateRecordButtonUI(true);
                startRecording(isStreamingEnabled());
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        windowVisible = false;
        if (isRecording) {
            if (isStopOnHideEnabled()) {
                // Opt-in behavior: discard the recording when the keyboard hides.
                try {
                    cancelRecording();
                } catch (Throwable t) {
                    Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                    try { stopRecording(); } catch (Throwable ignored) { }
                }
                updateRecordButtonUI(false);
            } else {
                // Default: keep recording in the background. The transcription
                // is committed on return (or held in pendingCommitText).
                return;
            }
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        inputActive = true;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        // Rebuild the keyboard if the theme preference changed while this
        // (long-lived) IME process stayed alive, so it matches the app setting.
        if (inputView != null
                && ThemePrefs.isNight(ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this))) != viewIsNight) {
            setInputView(onCreateInputView());
        }
        // A field is focused and the input connection is live again — commit any
        // text that finished transcribing while nothing was focused.
        flushPendingText();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        inputActive = false;
    }

    private void updateRecordButtonUI(boolean recording) {
        boolean wasRecording = isRecording;
        isRecording = recording;
        if (recording && !wasRecording) {
            recordingStartedAtMs = android.os.SystemClock.elapsedRealtime();
            lastRecordingDurationMs = 0L;
            processedAudioMs = 0L;
            processedWords = 0;
            currentProcessingSpeed = -1f;
            averageProcessingSpeed = -1f;
            lastStreamingStatsAtMs = 0L;
            updateStatsView();
            mainHandler.removeCallbacks(statsTicker);
            mainHandler.post(statsTicker);
        } else if (!recording && wasRecording) {
            lastRecordingDurationMs = Math.max(0L,
                    android.os.SystemClock.elapsedRealtime() - recordingStartedAtMs);
            // Keep the final metrics visible during the short native finalize
            // phase; the ticker stops once the status returns to Ready.
            updateStatsView();
        }
        // Keep the screen awake while recording so it never sleeps mid-capture
        // and cuts the recording short. Cleared automatically once we stop.
        if (inputView != null) {
            inputView.setKeepScreenOn(recording);
        }
        tintRecordButton(recording);
        if (recording) {
            statusView.setText("Listening...");
            hintView.setText("Tap to Stop");
        } else {
            statusView.setText("Processing...");
            hintView.setText("Tap to Record");
            if (micLevelView != null) micLevelView.setLevel(0f);
        }
    }

    /** Tints the round record button + mic: idle = primary, recording = error. */
    private void tintRecordButton(boolean recording) {
        int circleAttr = recording
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorPrimaryContainer;
        int iconAttr = recording
                ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnPrimaryContainer;
        if (recordCircle != null) {
            recordCircle.setBackgroundTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(recordCircle, circleAttr)));
        }
        if (micIcon != null) {
            micIcon.setColorFilter(MaterialColors.getColor(micIcon, iconAttr));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mainHandler != null) {
            mainHandler.removeCallbacks(statsTicker);
            if (spaceLongPressRunnable != null) mainHandler.removeCallbacks(spaceLongPressRunnable);
            if (backspaceRepeatRunnable != null) mainHandler.removeCallbacks(backspaceRepeatRunnable);
        }
        cleanupNative();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording(boolean streaming);
    private native void stopRecording();
    private native void cancelRecording();
    private native void setLanguageNative(String language);

    private void setupLanguageShortcuts() {
        String language = readConfig("model_language");
        if ("sv-SE".equals(language)) {
            languageGroup.check(R.id.ime_language_sv);
        } else if ("zh-TW".equals(language)) {
            languageGroup.check(R.id.ime_language_zh_tw);
        } else if ("en-US".equals(language)) {
            languageGroup.check(R.id.ime_language_en);
        }

        languageGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            final String selected;
            if (checkedId == R.id.ime_language_sv) {
                selected = "sv-SE";
            } else if (checkedId == R.id.ime_language_zh_tw) {
                selected = "zh-TW";
            } else if (checkedId == R.id.ime_language_en) {
                selected = "en-US";
            } else {
                return;
            }
            if (selected.equals(readConfig("model_language"))) return;

            Boolean modelChanged = applyLanguageSelection(selected);
            if (modelChanged == null) return;
            refreshModelSpinner();
            if (modelChanged) {
                lastStatus = "Loading model...";
                updateUiState();
                try {
                    reloadModelNative();
                } catch (Throwable t) {
                    Log.e(TAG, "Could not reload keyboard model for language " + selected, t);
                    onStatusUpdate("Error: could not reload model");
                }
            } else {
                try {
                    setLanguageNative(selected);
                } catch (Throwable t) {
                    Log.e(TAG, "Could not update native keyboard language", t);
                }
            }
        });
    }

    /** Saves the current model for the old language and resolves the new one. */
    private Boolean applyLanguageSelection(String selectedLanguage) {
        String previousLanguage = readConfig("model_language");
        String storedCurrent = readConfig("active_model");
        String current = LanguageModelPrefs.isInstalledModel(this, storedCurrent)
                ? storedCurrent : "";
        LanguageModelPrefs.write(this, previousLanguage, current);

        String remembered = LanguageModelPrefs.read(this, selectedLanguage);
        String target = current;
        if (remembered != null && LanguageModelPrefs.isInstalledModel(this, remembered)) {
            target = remembered;
        }

        boolean modelChanged = !target.equals(storedCurrent);
        if (modelChanged && !writeConfig("active_model", target)) return null;
        if (!writeConfig("model_language", selectedLanguage)) return null;
        LanguageModelPrefs.write(this, selectedLanguage, target);
        return modelChanged;
    }

    private String readConfig(String name) {
        File file = new File(getFilesDir(), name);
        if (!file.isFile()) return "";
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            Log.w(TAG, "Could not read " + name, e);
            return "";
        }
    }

    private boolean writeConfig(String name, String value) {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), name))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Could not write " + name, e);
            return false;
        }
    }

    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            Log.d(TAG, "Status: " + status);
            lastStatus = status;
            if (status != null && status.startsWith("Error") && isRecording) {
                updateRecordButtonUI(false);
            }
            if (status != null && (status.startsWith("Error")
                    || "Canceled".equals(status) || "Ready".equals(status))) {
                mainHandler.removeCallbacks(statsTicker);
            }
            updateUiState();
            if (pendingSwitchBack && status != null && status.startsWith("Error")) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
            if (pauseAudioActive && status != null && status.startsWith("Error")) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
        });
    }

    private void updateUiState() {
        boolean isLoading = lastStatus.contains("Loading") || lastStatus.contains("Initializing");
        boolean isWaiting = lastStatus.contains("Waiting");
        boolean isTranscribing = lastStatus.contains("Transcribing") || lastStatus.contains("Processing");
        boolean isError = lastStatus.startsWith("Error");
        boolean isReady = lastStatus.equals("Ready");

        refreshModelSpinner();
        updateStatsView();

        boolean noModelInstalled = !ModelUtils.hasAnyModelInstalled(this);
        boolean noModelSelected = !noModelInstalled && ModelUtils.getActiveModel(this) == null;

        // Don't show internal loading states to the user
        if (statusView != null && !isRecording) {
            if (noModelInstalled) {
                statusView.setText(getString(R.string.models_none_installed));
            } else if (noModelSelected) {
                statusView.setText(getString(R.string.models_none_selected));
            } else if (isError) {
                statusView.setText(lastStatus);
            } else if (isTranscribing || isWaiting) {
                statusView.setText("Processing...");
            } else {
                statusView.setText("Tap to Record");
            }
        }

        if (hintView != null && !isRecording) {
            if (noModelInstalled || noModelSelected) {
                hintView.setText(getString(R.string.models_open_settings_hint));
            }
        }

        // Hide progress bar - don't expose model loading to user
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        // Disable button only during transcription/processing/waiting or fatal errors
        if (recordContainer != null) {
            boolean disable = isTranscribing || isWaiting || isError;
            recordContainer.setEnabled(!disable);
            recordContainer.setAlpha(disable ? 0.5f : 1.0f);
        }

        if (languageGroup != null) {
            boolean disable = isRecording || isTranscribing || isWaiting;
            for (int i = 0; i < languageGroup.getChildCount(); i++) {
                languageGroup.getChildAt(i).setEnabled(!disable);
            }
            languageGroup.setAlpha(disable ? 0.5f : 1.0f);
        }

        // Switching models resets the shared native engine, so keep the
        // selector unavailable while a recording or model load is in flight.
        updateModelSelectorState(isRecording || isLoading || isTranscribing || isWaiting);

        if (hintView != null && !isRecording) {
            hintView.setText("Tap to Record");
        }
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            if (text == null || text.trim().isEmpty()) {
                // Nothing recognized — don't insert a stray space.
                updateRecordButtonUI(false);
                if (statusView != null) statusView.setText("Tap to Record");
                hideStatsIfIdle();
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchToPreviousInputMethod();
                }
                return;
            }
            String committed = appendSpaceEnabled() && !Character.isWhitespace(
                    text.charAt(text.length() - 1)) ? text + " " : text;
            InputConnection ic = getCurrentInputConnection();
            if (inputActive && ic != null) {
                commitTranscribedText(ic, committed);
            } else {
                // No editor is focused right now (common on long transcribes where
                // a web field in Firefox/Gemini dropped focus while we processed
                // audio). Committing now would be silently dropped, so defer the
                // text until a field is focused again instead of losing it.
                pendingCommitText = committed;
            }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            long dur = lastRecordingDurationMs > 0 ? lastRecordingDurationMs
                    : (recordingStartedAtMs > 0
                    ? Math.max(0L, android.os.SystemClock.elapsedRealtime() - recordingStartedAtMs)
                    : 0L);
            lastRecordingDurationMs = 0L;
            String currentLang = readConfig("model_language");
            String currentModel = readConfig("active_model");
            DictationStatsManager.SessionRecord session = DictationStatsManager.recordPaste(
                    RustInputMethodService.this, text, dur, currentLang, currentModel);
            if (session != null) {
                displayLastWpm(session);
            }
            updateRecordButtonUI(false);
            if (statusView != null) statusView.setText("Tap to Record");
            hideStatsIfIdle();
            if (pendingSwitchBack) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
        });
    }

    // Commits transcribed text into the active input connection, optionally
    // selecting it afterwards (select_transcription setting).
    private void commitTranscribedText(InputConnection ic, String committed) {
        ic.commitText(committed, 1);

        if (!pendingSwitchBack && new File(getFilesDir(), "select_transcription").exists()) {
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (et != null) {
                int end = et.selectionStart;
                int start = end - committed.length();
                if (start >= 0) {
                    ic.setSelection(start, end);
                }
            }
        }
    }

    // Commits text that finished transcribing while no field was focused. Called
    // from onStartInputView when an editor (and a live input connection) is
    // available again.
    private void flushPendingText() {
        if (pendingCommitText == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitTranscribedText(ic, pendingCommitText);
            pendingCommitText = null;
        }
    }
    public void onAudioLevel(float level) {
        if (micLevelView != null) {
            mainHandler.post(() -> micLevelView.setLevel(level));
        }
    }

    /** Called from the native streaming worker at a throttled cadence. */
    public void onStreamingStats(long processedAudioMs, int words,
            float currentSpeed, float averageSpeed) {
        mainHandler.post(() -> {
            this.processedAudioMs = Math.max(0L, processedAudioMs);
            processedWords = Math.max(0, words);
            currentProcessingSpeed = sanitizeSpeed(currentSpeed);
            averageProcessingSpeed = sanitizeSpeed(averageSpeed);
            lastStreamingStatsAtMs = android.os.SystemClock.elapsedRealtime();
            updateStatsView();
        });
    }

    private boolean isProcessingStatus() {
        return lastStatus.contains("Processing") || lastStatus.contains("Transcribing");
    }

    private void setupModelSpinner() {
        modelAdapter = new ArrayAdapter<>(modelSpinner.getContext(),
                R.layout.ime_model_spinner_item,
                new ArrayList<>());
        modelAdapter.setDropDownViewResource(R.layout.ime_model_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingModelSpinner || position < 0 || position >= modelFiles.size()) return;
                if (isRecording || isProcessingStatus()) {
                    refreshModelSpinner();
                    return;
                }

                String selected = modelFiles.get(position);
                if (selected != null && selected.isEmpty()) {
                    // Placeholder item ("No model installed" or "No model selected")
                    openModelsActivity();
                    refreshModelSpinner();
                    return;
                }

                String current = readConfig("active_model");
                boolean same = selected == null ? current.isEmpty() : selected.equals(current);
                if (same) return;

                writeConfig("active_model", selected == null ? "" : selected);
                String saved = readConfig("active_model");
                boolean savedMatches = selected == null ? saved.isEmpty() : selected.equals(saved);
                if (!savedMatches) {
                    refreshModelSpinner();
                    return;
                }
                LanguageModelPrefs.write(RustInputMethodService.this,
                        readConfig("model_language"),
                        selected == null ? "" : selected);

                lastStatus = "Loading model...";
                updateUiState();
                try {
                    reloadModelNative();
                } catch (Throwable t) {
                    Log.e(TAG, "Could not reload selected keyboard model", t);
                    onStatusUpdate("Error: could not reload model");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        refreshModelSpinner();
    }

    /** Refreshes the dropdown from model files shared with ModelsActivity. */
    private void refreshModelSpinner() {
        if (modelSpinner == null || modelAdapter == null) return;

        String active = readConfig("active_model");
        List<String> names = ModelUtils.getInstalledImportedModels(this);
        boolean hasBuiltin = ModelUtils.hasBuiltinModel(this);

        modelFiles.clear();
        List<String> labels = new ArrayList<>();

        if (hasBuiltin) {
            modelFiles.add(null); // Built-in Parakeet model.
            labels.add(getString(R.string.models_builtin));
        }
        for (String name : names) {
            modelFiles.add(name);
            labels.add(stripModelExtension(name));
        }

        int selected = -1;
        if (!active.isEmpty()) {
            selected = modelFiles.indexOf(active);
            if (selected < 0 && hasBuiltin) {
                selected = 0;
                writeConfig("active_model", "");
            }
        } else if (hasBuiltin) {
            selected = 0;
        }

        if (modelFiles.isEmpty()) {
            labels.add(getString(R.string.models_none_installed));
            modelFiles.add("");
            selected = 0;
        } else if (selected < 0) {
            labels.add(0, getString(R.string.models_none_selected));
            modelFiles.add(0, "");
            selected = 0;
        }

        updatingModelSpinner = true;
        modelAdapter.clear();
        modelAdapter.addAll(labels);
        modelAdapter.notifyDataSetChanged();
        if (selected >= 0 && selected < labels.size()) {
            modelSpinner.setSelection(selected, false);
        }
        updatingModelSpinner = false;
    }

    private static boolean isModelFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".gguf") || lower.endsWith(".bin");
    }

    private void selectAllText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!ic.performContextMenuAction(android.R.id.selectAll)) {
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                    new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (et != null && et.text != null) {
                ic.setSelection(0, et.text.length());
            }
        }
    }

    private static String stripModelExtension(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".gguf")) {
            return name.substring(0, name.length() - ".gguf".length());
        }
        if (lower.endsWith(".bin")) {
            return name.substring(0, name.length() - ".bin".length());
        }
        return name;
    }

    private void updateStatsView() {
        if (statsView == null) return;
        boolean visible = isRecording || isProcessingStatus();
        statsView.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;

        long elapsedMs = recordingStartedAtMs == 0L
                ? 0L
                : Math.max(0L, android.os.SystemClock.elapsedRealtime() - recordingStartedAtMs);
        long nowMs = android.os.SystemClock.elapsedRealtime();
        boolean currentRateFresh = lastStreamingStatsAtMs > 0L
                && nowMs - lastStreamingStatsAtMs <= 2_000L;
        statsView.setText(getString(R.string.ime_stats_format, formatElapsed(elapsedMs),
                processedWords, formatSpeed(currentRateFresh ? currentProcessingSpeed : -1f),
                formatSpeed(averageProcessingSpeed)));
    }

    private void hideStatsIfIdle() {
        if (!isRecording && !isProcessingStatus() && statsView != null) {
            statsView.setVisibility(View.GONE);
        }
        mainHandler.removeCallbacks(statsTicker);
    }

    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static float sanitizeSpeed(float speed) {
        return (speed > 0f && !Float.isNaN(speed) && !Float.isInfinite(speed))
                ? speed : -1f;
    }

    private static String formatSpeed(float speed) {
        if (speed < 0f || Float.isNaN(speed) || Float.isInfinite(speed)) return "—";
        if (speed < 0.1f) return "<0.1×";
        return String.format(java.util.Locale.ROOT, "%.1f×", speed);
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    private boolean appendSpaceEnabled() {
        return new File(getFilesDir(), "append_space").exists();
    }

    private boolean isStreamingEnabled() {
        return new File(getFilesDir(), "ime_streaming").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }

    private void updateModelSelectorState(boolean disabled) {
        if (modelSpinner == null) return;
        modelSpinner.setEnabled(!disabled);
        modelSpinner.setAlpha(disabled ? 0.5f : 1.0f);
    }

    private void displayLastWpm(DictationStatsManager.SessionRecord session) {
        if (lastWpmView == null || session == null) return;
        int wpm = Math.round(session.wpm);
        float seconds = session.durationMs / 1000.0f;
        lastWpmView.setText(getString(R.string.ime_last_wpm_format, wpm, session.words, seconds));
        lastWpmView.setVisibility(View.VISIBLE);
    }

    private void showLastWpmIfAvailable() {
        if (lastWpmView == null) return;
        DictationStatsManager.SessionRecord last = DictationStatsManager.getLastPaste(this);
        if (last != null) {
            displayLastWpm(last);
        } else {
            lastWpmView.setVisibility(View.GONE);
        }
    }

    private void openAppStats() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("open_stats", true);
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to open MainActivity stats", t);
        }
    }

    private void openModelsActivity() {
        try {
            Intent intent = new Intent(this, ModelsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to open ModelsActivity", t);
        }
    }

    private void switchLanguageOrKeyboard() {
        if (isRecording) {
            pendingSwitchBack = true;
            stopRecording();
            updateRecordButtonUI(false);
            return;
        }
        boolean switched = false;
        try {
            switched = switchToPreviousInputMethod();
        } catch (Throwable t) {
            Log.w(TAG, "switchToPreviousInputMethod failed", t);
        }
        if (!switched) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                try {
                    imm.showInputMethodPicker();
                } catch (Throwable t) {
                    Log.w(TAG, "showInputMethodPicker failed", t);
                }
            }
        }
    }

    private static boolean isPointInsideView(View view, float x, float y) {
        if (view == null) return false;
        float slop = 24f;
        return x >= -slop && x <= (view.getWidth() + slop) && y >= -slop && y <= (view.getHeight() + slop);
    }

    private native void reloadModelNative();
}
