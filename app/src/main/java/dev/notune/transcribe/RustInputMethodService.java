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
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.ViewGroup;
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
    private View progressContainer;
    private TextView progressPercentView;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private TextView languageSwitchButton;
    private View modelButton;
    private View appButton;
    private LinearLayout actionRow;
    private View deleteWordButton;
    private View qwertyContainer;
    private android.widget.ImageView expandToggleButton;
    private android.widget.ImageView shiftKey;
    private final List<TextView> letterKeys = new ArrayList<>();
    private ShiftState shiftState = ShiftState.OFF;
    private long lastShiftTapTime = 0L;

    private enum ShiftState {
        OFF,
        ONE_SHOT,
        CAPS_LOCK
    }
    private View selectAllButton;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordCircle;
    private android.widget.ImageView pasteButton;
    private android.widget.ImageView copyButton;
    private android.widget.ImageView clearClipboardButton;
    private android.widget.ImageView cutButton;
    private android.widget.ImageView privacyToggleButton;
    private android.widget.ImageView historyButton;
    private View historyContainer;
    private android.widget.ImageView historyClearAllButton;
    private android.widget.ImageView historyCloseButton;
    private LinearLayout historyItemsContainer;
    private TextView historyEmptyView;
    private boolean isHistoryVisible = false;
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
    private long totalAudioMs = 0L;
    private long processingStartedAtMs = 0L;
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
                if (isProcessingStatus()) {
                    updateProcessingProgress();
                }
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

            statusView = null;
            lastWpmView = view.findViewById(R.id.ime_last_wpm_text);
            if (lastWpmView != null) {
                lastWpmView.setOnClickListener(v -> openAppStats());
            }
            showLastWpmIfAvailable();
            modelSpinner = view.findViewById(R.id.ime_model_spinner);
            statsView = view.findViewById(R.id.ime_stats_text);
            progressBar = view.findViewById(R.id.ime_progress);
            progressContainer = view.findViewById(R.id.ime_progress_container);
            progressPercentView = view.findViewById(R.id.ime_progress_percent);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordCircle = view.findViewById(R.id.ime_record_circle);
            hintView = null;
            backspaceButton = view.findViewById(R.id.ime_backspace);
            selectAllButton = view.findViewById(R.id.ime_select_all);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            languageSwitchButton = view.findViewById(R.id.ime_language_switch);
            modelButton = view.findViewById(R.id.ime_model_button);
            appButton = view.findViewById(R.id.ime_app_button);
            actionRow = view.findViewById(R.id.ime_action_row);
            deleteWordButton = view.findViewById(R.id.ime_delete_word);
            qwertyContainer = view.findViewById(R.id.ime_qwerty_container);
            expandToggleButton = view.findViewById(R.id.ime_expand_toggle);
            shiftKey = view.findViewById(R.id.ime_key_shift);

            pasteButton = view.findViewById(R.id.ime_paste_button);
            copyButton = view.findViewById(R.id.ime_copy_button);
            clearClipboardButton = view.findViewById(R.id.ime_clear_clipboard_button);
            cutButton = view.findViewById(R.id.ime_cut_button);
            privacyToggleButton = view.findViewById(R.id.ime_privacy_toggle);
            historyButton = view.findViewById(R.id.ime_history_button);
            historyContainer = view.findViewById(R.id.ime_history_container);
            historyClearAllButton = view.findViewById(R.id.ime_history_clear_all);
            historyCloseButton = view.findViewById(R.id.ime_history_close);
            historyItemsContainer = view.findViewById(R.id.ime_history_items_container);
            historyEmptyView = view.findViewById(R.id.ime_history_empty);

            if (pasteButton != null) {
                pasteButton.setOnClickListener(v -> pasteClipboardText());
                pasteButton.setOnLongClickListener(v -> {
                    previewClipboardText();
                    return true;
                });
            }

            if (copyButton != null) {
                copyButton.setOnClickListener(v -> copySelectedText());
            }

            if (clearClipboardButton != null) {
                clearClipboardButton.setOnClickListener(v -> clearClipboard());
            }

            if (cutButton != null) {
                cutButton.setOnClickListener(v -> cutSelectedText());
            }

            if (privacyToggleButton != null) {
                updatePrivacyToggleUI();
                privacyToggleButton.setOnClickListener(v -> {
                    boolean current = TranscriptionHistoryManager.isPrivateMode(this);
                    TranscriptionHistoryManager.setPrivateMode(this, !current);
                    updatePrivacyToggleUI();
                    android.widget.Toast.makeText(this, !current
                            ? R.string.ime_privacy_toast_on
                            : R.string.ime_privacy_toast_off,
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }

            if (historyButton != null) {
                historyButton.setOnClickListener(v -> toggleHistoryView());
            }

            if (historyCloseButton != null) {
                historyCloseButton.setOnClickListener(v -> showHistoryView(false));
            }

            if (historyClearAllButton != null) {
                historyClearAllButton.setOnClickListener(v -> {
                    TranscriptionHistoryManager.clearAll(this);
                    loadHistoryItems();
                    android.widget.Toast.makeText(this, R.string.ime_history_empty,
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }

            setupModelSpinner();

            if (modelButton != null) {
                modelButton.setOnClickListener(v -> {
                    if (modelSpinner != null) {
                        modelSpinner.performClick();
                    }
                });
                modelButton.setOnLongClickListener(v -> {
                    openModelsActivity();
                    return true;
                });
            }

            if (appButton != null) {
                appButton.setOnClickListener(v -> openMainActivity());
            }

            if (selectAllButton != null) {
                selectAllButton.setOnClickListener(v -> selectAllText());
            }

            if (languageSwitchButton != null) {
                updateLanguageButtonText();
                languageSwitchButton.setOnClickListener(v -> cycleLanguage());
                languageSwitchButton.setOnLongClickListener(v -> {
                    switchLanguageOrKeyboard();
                    return true;
                });
            }

            if (deleteWordButton != null) {
                deleteWordButton.setOnClickListener(v -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    deletePreviousWord();
                });
            }

            if (expandToggleButton != null) {
                expandToggleButton.setOnClickListener(v -> toggleQwertyKeyboard());
            }

            if (shiftKey != null) {
                shiftKey.setOnClickListener(v -> onShiftKeyClicked());
            }

            setupQwertyKeys(view);
            updateQwertyVisibility();

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
                    android.widget.Toast.makeText(this, "No mic permission - grant in app", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!ModelUtils.hasAnyModelInstalled(this)) {
                    if (statusView != null) statusView.setText(getString(R.string.models_none_installed));
                    if (hintView != null) hintView.setText(getString(R.string.models_open_settings_hint));
                    android.widget.Toast.makeText(this, R.string.models_none_installed, android.widget.Toast.LENGTH_SHORT).show();
                    openModelsActivity();
                    return;
                }
                String active = ModelUtils.getActiveModel(this);
                if (active == null) {
                    if (statusView != null) statusView.setText(getString(R.string.models_none_selected));
                    if (hintView != null) hintView.setText(getString(R.string.models_open_settings_hint));
                    android.widget.Toast.makeText(this, R.string.models_none_selected, android.widget.Toast.LENGTH_SHORT).show();
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

            if (recordContainer != null) {
                recordContainer.setOnLongClickListener(v -> {
                    if (!recordContainer.isEnabled()) return false;
                    if (!isRecording) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        switchLanguageOrKeyboard();
                        return true;
                    }
                    return false;
                });
            }

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
        updatePrivacyToggleUI();
        if (!isRecording) {
            refreshModelSpinner();
            updateLanguageButtonText();
            updateQwertyVisibility();
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
        showHistoryView(false);
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
            if (isHistoryVisible) {
                showHistoryView(false);
            }
            TranscriptionHistoryManager.startSession();
            recordingStartedAtMs = android.os.SystemClock.elapsedRealtime();
            lastRecordingDurationMs = 0L;
            totalAudioMs = 0L;
            processingStartedAtMs = 0L;
            processedAudioMs = 0L;
            processedWords = 0;
            currentProcessingSpeed = -1f;
            averageProcessingSpeed = -1f;
            lastStreamingStatsAtMs = 0L;
            updateProcessingProgress();
            updateStatsView();
            mainHandler.removeCallbacks(statsTicker);
            mainHandler.post(statsTicker);
        } else if (!recording && wasRecording) {
            lastRecordingDurationMs = Math.max(0L,
                    android.os.SystemClock.elapsedRealtime() - recordingStartedAtMs);
            processingStartedAtMs = android.os.SystemClock.elapsedRealtime();
            updateProcessingProgress();
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
            if (statusView != null) statusView.setText("Listening... (tap to stop)");
            if (hintView != null) hintView.setVisibility(View.GONE);
            if (progressContainer != null) progressContainer.setVisibility(View.GONE);
            else if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (micIcon != null) {
                micIcon.setImageResource(R.drawable.ic_stop);
                micIcon.setContentDescription("Stop listening");
                micIcon.setVisibility(View.VISIBLE);
            }
        } else {
            boolean isProcessing = isProcessingStatus();
            if (isProcessing) {
                if (statusView != null) statusView.setText("Processing...");
                if (hintView != null) hintView.setVisibility(View.GONE);
                if (progressContainer != null) progressContainer.setVisibility(View.VISIBLE);
                else if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                if (micIcon != null) micIcon.setVisibility(View.GONE);
                updateProcessingProgress();
            } else {
                if (statusView != null) statusView.setText("Tap to speak");
                if (hintView != null) hintView.setVisibility(View.GONE);
                if (progressContainer != null) progressContainer.setVisibility(View.GONE);
                else if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (micIcon != null) {
                    micIcon.setImageResource(R.drawable.ic_mic);
                    micIcon.setContentDescription(getString(R.string.section_ime));
                    micIcon.setVisibility(View.VISIBLE);
                }
            }
            if (micLevelView != null) {
                micLevelView.setLevel(0f);
                micLevelView.setVisibility(View.GONE);
            }
        }
    }

    /** Tints the record button surface + mic/text: idle = key surface, recording = primary, processing = secondary container. */
    private void tintRecordButton(boolean recording) {
        boolean isBusy = !recording && isProcessingStatus();
        int circleAttr;
        int iconAttr;
        if (recording) {
            circleAttr = com.google.android.material.R.attr.colorPrimary;
            iconAttr = com.google.android.material.R.attr.colorOnPrimary;
        } else if (isBusy) {
            circleAttr = com.google.android.material.R.attr.colorSecondaryContainer;
            iconAttr = com.google.android.material.R.attr.colorOnSecondaryContainer;
        } else {
            circleAttr = com.google.android.material.R.attr.colorPrimaryContainer;
            iconAttr = com.google.android.material.R.attr.colorOnPrimaryContainer;
        }
        if (recordCircle != null) {
            recordCircle.setBackgroundTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(recordCircle, circleAttr)));
        }
        if (micIcon != null) {
            micIcon.setColorFilter(MaterialColors.getColor(micIcon, iconAttr));
        }
        if (micLevelView != null) {
            micLevelView.setColor(MaterialColors.getColor(recordCircle != null ? recordCircle : micLevelView, iconAttr));
            micLevelView.setVisibility(recording ? View.VISIBLE : View.GONE);
        }
        if (statusView != null) {
            statusView.setTextColor(MaterialColors.getColor(statusView, iconAttr));
        }
        if (progressBar != null) {
            progressBar.setIndeterminateTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(progressBar, iconAttr)));
        }
        if (progressPercentView != null) {
            progressPercentView.setTextColor(MaterialColors.getColor(progressPercentView, iconAttr));
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

    private static final String[] CYCLE_LANGUAGES = {"sv-SE", "zh-TW", "ja-JP", "en-US"};

    private void cycleLanguage() {
        if (isRecording || isProcessingStatus()) {
            return;
        }
        if (languageSwitchButton != null) {
            languageSwitchButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
        String current = readConfig("model_language");
        int currentIndex = -1;
        for (int i = 0; i < CYCLE_LANGUAGES.length; i++) {
            if (CYCLE_LANGUAGES[i].equalsIgnoreCase(current)) {
                currentIndex = i;
                break;
            }
        }
        int nextIndex = (currentIndex + 1) % CYCLE_LANGUAGES.length;
        String nextLanguage = CYCLE_LANGUAGES[nextIndex];

        Boolean modelChanged = applyLanguageSelection(nextLanguage);
        if (modelChanged == null) return;

        updateLanguageButtonText();
        refreshModelSpinner();

        if (modelChanged) {
            lastStatus = "Loading model...";
            updateUiState();
            try {
                reloadModelNative();
            } catch (Throwable t) {
                Log.e(TAG, "Could not reload keyboard model for language " + nextLanguage, t);
                onStatusUpdate("Error: could not reload model");
            }
        } else {
            try {
                setLanguageNative(nextLanguage);
            } catch (Throwable t) {
                Log.e(TAG, "Could not update native keyboard language", t);
            }
        }
    }

    private void updateLanguageButtonText() {
        if (languageSwitchButton == null) return;
        String language = readConfig("model_language");
        String label = getLanguageDisplayLabel(language);
        languageSwitchButton.setText(label);
        String desc = language.isEmpty()
                ? getString(R.string.models_language_auto)
                : java.util.Locale.forLanguageTag(language).getDisplayName();
        languageSwitchButton.setContentDescription(getString(R.string.ime_switch_language_desc, desc));
    }

    private String getLanguageDisplayLabel(String language) {
        if (language == null || language.isEmpty()) {
            return getString(R.string.ime_language_auto);
        }
        if ("sv-SE".equalsIgnoreCase(language) || "sv".equalsIgnoreCase(language)) {
            return getString(R.string.ime_language_sv);
        }
        if ("zh-TW".equalsIgnoreCase(language)) {
            return getString(R.string.ime_language_zh_tw);
        }
        if ("ja-JP".equalsIgnoreCase(language) || "ja".equalsIgnoreCase(language)) {
            return getString(R.string.ime_language_ja);
        }
        if ("en-US".equalsIgnoreCase(language) || "en-GB".equalsIgnoreCase(language) || "en".equalsIgnoreCase(language)) {
            return getString(R.string.ime_language_en);
        }
        int dash = language.indexOf('-');
        if (dash > 0 && dash <= 3) {
            if (language.length() <= 5) {
                return language.toUpperCase(java.util.Locale.ROOT);
            }
            return language.substring(0, dash).toUpperCase(java.util.Locale.ROOT);
        }
        return language.toUpperCase(java.util.Locale.ROOT);
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
                if ("Canceled".equals(status) || (status.startsWith("Error") && !isRecording)) {
                    recordingStartedAtMs = 0L;
                    lastRecordingDurationMs = 0L;
                    totalAudioMs = 0L;
                    processedAudioMs = 0L;
                    processingStartedAtMs = 0L;
                    updateProcessingProgress();
                }
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

        tintRecordButton(isRecording);

        boolean isBusy = (isTranscribing || isWaiting) && !isRecording;

        // Don't show internal loading states to the user
        if (statusView != null && !isRecording) {
            if (noModelInstalled) {
                statusView.setText(getString(R.string.models_none_installed));
            } else if (noModelSelected) {
                statusView.setText(getString(R.string.models_none_selected));
            } else if (isError) {
                statusView.setText(lastStatus);
            } else if (isBusy) {
                statusView.setText("Processing...");
            } else {
                statusView.setText("Tap to speak");
            }
        }

        if (hintView != null && !isRecording) {
            if (noModelInstalled || noModelSelected) {
                hintView.setText(getString(R.string.models_open_settings_hint));
                hintView.setVisibility(View.VISIBLE);
            } else {
                hintView.setVisibility(View.GONE);
            }
        }

        // Show progress spinner & percent in record button when busy
        if (progressContainer != null) {
            progressContainer.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        } else if (progressBar != null) {
            progressBar.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        }
        if (micIcon != null) {
            micIcon.setVisibility(isBusy ? View.GONE : View.VISIBLE);
            micIcon.setImageResource(isRecording ? R.drawable.ic_stop : R.drawable.ic_mic);
        }
        if (isBusy) {
            updateProcessingProgress();
        }

        // Disable button only during transcription/processing/waiting or fatal errors
        if (recordContainer != null) {
            boolean disable = isTranscribing || isWaiting || isError;
            recordContainer.setEnabled(!disable);
            recordContainer.setAlpha(disable ? 0.75f : 1.0f);
        }

        if (languageSwitchButton != null) {
            boolean disable = isRecording || isTranscribing || isWaiting;
            languageSwitchButton.setEnabled(!disable);
            languageSwitchButton.setAlpha(disable ? 0.5f : 1.0f);
        }

        if (selectAllButton != null) {
            boolean disable = isRecording || isTranscribing || isWaiting;
            selectAllButton.setEnabled(!disable);
            selectAllButton.setAlpha(disable ? 0.5f : 1.0f);
        }

        // Switching models resets the shared native engine, so keep the
        // selector unavailable while a recording or model load is in flight.
        updateModelSelectorState(isRecording || isLoading || isTranscribing || isWaiting);
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            totalAudioMs = 0L;
            processedAudioMs = 0L;
            processingStartedAtMs = 0L;
            updateProcessingProgress();
            if (text == null || text.trim().isEmpty()) {
                // Nothing recognized — don't insert a stray space.
                updateRecordButtonUI(false);
                lastRecordingDurationMs = 0L;
                recordingStartedAtMs = 0L;
                if (statusView != null) statusView.setText("Tap to speak");
                if (hintView != null) hintView.setVisibility(View.GONE);
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
            long nowMs = android.os.SystemClock.elapsedRealtime();
            long dur = lastRecordingDurationMs > 0 ? lastRecordingDurationMs
                    : (recordingStartedAtMs > 0
                    ? Math.max(0L, nowMs - recordingStartedAtMs)
                    : 0L);
            long totalDur = recordingStartedAtMs > 0
                    ? Math.max(dur, nowMs - recordingStartedAtMs)
                    : dur;
            lastRecordingDurationMs = 0L;
            recordingStartedAtMs = 0L;
            String currentLang = readConfig("model_language");
            String currentModel = readConfig("active_model");
            TranscriptionHistoryManager.finalizeEntry(
                    RustInputMethodService.this,
                    text,
                    currentLang,
                    currentModel,
                    dur);
            if (isHistoryVisible) {
                loadHistoryItems();
            }
            DictationStatsManager.SessionRecord session = DictationStatsManager.recordPaste(
                    RustInputMethodService.this, text, dur, totalDur, currentLang, currentModel);
            if (session != null) {
                displayLastWpm(session);
            }
            updateRecordButtonUI(false);
            if (statusView != null) statusView.setText("Tap to speak");
            if (hintView != null) hintView.setVisibility(View.GONE);
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

    // Called from Rust streaming worker on each partial hypothesis
    public void onStreamingPartialText(String partialText) {
        mainHandler.post(() -> {
            if (partialText != null && !partialText.trim().isEmpty()) {
                TranscriptionHistoryManager.updateDraft(
                        RustInputMethodService.this,
                        partialText.trim(),
                        readConfig("model_language"),
                        readConfig("active_model"));
                if (isHistoryVisible) {
                    loadHistoryItems();
                }
            }
        });
    }

    private void toggleHistoryView() {
        showHistoryView(!isHistoryVisible);
    }

    private void showHistoryView(boolean show) {
        isHistoryVisible = show;
        if (historyContainer != null) {
            historyContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (actionRow != null) {
            actionRow.setVisibility(show ? View.GONE : View.VISIBLE);
        } else if (recordContainer != null) {
            recordContainer.setVisibility(show ? View.GONE : View.VISIBLE);
        }
        if (qwertyContainer != null) {
            qwertyContainer.setVisibility(show ? View.GONE : (isQwertyExpanded() ? View.VISIBLE : View.GONE));
        }
        if (historyButton != null) {
            int activeColor = MaterialColors.getColor(historyButton,
                    com.google.android.material.R.attr.colorPrimary);
            int inactiveColor = MaterialColors.getColor(historyButton,
                    com.google.android.material.R.attr.colorOnSurfaceVariant);
            historyButton.setColorFilter(show ? activeColor : inactiveColor);
        }
        if (show) {
            loadHistoryItems();
        } else {
            updateActionRowLayout(isQwertyExpanded());
        }
    }

    private void updatePrivacyToggleUI() {
        if (privacyToggleButton == null) return;
        boolean isPrivate = TranscriptionHistoryManager.isPrivateMode(this);
        if (isPrivate) {
            int errorColor = MaterialColors.getColor(privacyToggleButton,
                    com.google.android.material.R.attr.colorError);
            privacyToggleButton.setColorFilter(errorColor);
            privacyToggleButton.setAlpha(1.0f);
            privacyToggleButton.setContentDescription(
                    getString(R.string.ime_privacy_mode) + ": " + getString(R.string.status_ready));
        } else {
            int normalColor = MaterialColors.getColor(privacyToggleButton,
                    com.google.android.material.R.attr.colorOnSurfaceVariant);
            privacyToggleButton.setColorFilter(normalColor);
            privacyToggleButton.setAlpha(0.6f);
            privacyToggleButton.setContentDescription(getString(R.string.ime_privacy_mode));
        }
    }

    private void loadHistoryItems() {
        if (historyItemsContainer == null) return;
        historyItemsContainer.removeAllViews();
        List<TranscriptionHistoryManager.HistoryEntry> entries =
                TranscriptionHistoryManager.getHistory(this);
        if (entries.isEmpty()) {
            if (historyEmptyView != null) historyEmptyView.setVisibility(View.VISIBLE);
            return;
        }
        if (historyEmptyView != null) historyEmptyView.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(historyItemsContainer.getContext());
        for (TranscriptionHistoryManager.HistoryEntry entry : entries) {
            View itemView = inflater.inflate(R.layout.item_ime_history, historyItemsContainer, false);
            TextView textView = itemView.findViewById(R.id.history_item_text);
            TextView metaView = itemView.findViewById(R.id.history_item_meta);
            View deleteButton = itemView.findViewById(R.id.history_item_delete);

            if (entry.isDraft) {
                textView.setText(getString(R.string.ime_history_draft_prefix) + entry.text);
            } else {
                textView.setText(entry.text);
            }

            String relTime = TranscriptionHistoryManager.formatRelativeTime(entry.timestamp);
            String meta = relTime + " · " + entry.language + (entry.isDraft ? " · Draft" : "");
            metaView.setText(meta);

            itemView.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    String toPaste = appendSpaceEnabled() && !Character.isWhitespace(
                            entry.text.charAt(entry.text.length() - 1)) ? entry.text + " " : entry.text;
                    commitTranscribedText(ic, toPaste);
                }
                showHistoryView(false);
            });

            deleteButton.setOnClickListener(v -> {
                TranscriptionHistoryManager.deleteEntry(this, entry.id);
                loadHistoryItems();
            });

            historyItemsContainer.addView(itemView);
        }
    }
    public void onAudioLevel(float level) {
        if (micLevelView != null) {
            mainHandler.post(() -> micLevelView.setLevel(level));
        }
    }

    /** Called from the native streaming worker at a throttled cadence. */
    public void onStreamingStats(long processedAudioMs, long totalAudioMs, int words,
            float currentSpeed, float averageSpeed) {
        mainHandler.post(() -> {
            this.processedAudioMs = Math.max(0L, processedAudioMs);
            if (totalAudioMs > 0L) {
                this.totalAudioMs = totalAudioMs;
            }
            processedWords = Math.max(0, words);
            currentProcessingSpeed = sanitizeSpeed(currentSpeed);
            averageProcessingSpeed = sanitizeSpeed(averageSpeed);
            lastStreamingStatsAtMs = android.os.SystemClock.elapsedRealtime();
            updateStatsView();
            updateProcessingProgress();
        });
    }

    private boolean isProcessingStatus() {
        return lastStatus.contains("Processing") || lastStatus.contains("Transcribing");
    }

    private void setupModelSpinner() {
        modelAdapter = new ArrayAdapter<String>(modelSpinner.getContext(),
                R.layout.ime_model_spinner_item,
                R.id.ime_model_item_text,
                new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                v.setVisibility(View.GONE);
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                TextView textView = v.findViewById(R.id.ime_model_item_text);
                android.widget.ImageView checkView = v.findViewById(R.id.ime_model_item_check);
                boolean isSelected = (position == modelSpinner.getSelectedItemPosition());

                if (checkView != null) {
                    checkView.setVisibility(isSelected ? View.VISIBLE : View.GONE);
                }
                if (textView != null) {
                    if (isSelected) {
                        textView.setTextColor(MaterialColors.getColor(textView,
                                com.google.android.material.R.attr.colorPrimary));
                        textView.setTypeface(null, android.graphics.Typeface.BOLD);
                    } else {
                        textView.setTextColor(MaterialColors.getColor(textView,
                                com.google.android.material.R.attr.colorOnSurface));
                        textView.setTypeface(null, android.graphics.Typeface.NORMAL);
                    }
                }
                return v;
            }
        };
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

        if (modelButton != null) {
            String displayModel = active.isEmpty()
                    ? (hasBuiltin ? getString(R.string.models_builtin) : getString(R.string.models_none_selected))
                    : stripModelExtension(active);
            modelButton.setContentDescription(getString(R.string.ime_model_format, displayModel));
        }
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

    private void pasteClipboardText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            android.widget.Toast.makeText(this, R.string.ime_paste_empty,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            android.widget.Toast.makeText(this, R.string.ime_paste_empty,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            android.widget.Toast.makeText(this, R.string.ime_paste_empty,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        if (pasteButton != null) {
            pasteButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }

        if (!ic.performContextMenuAction(android.R.id.paste)) {
            ic.commitText(text, 1);
        }
    }

    private void previewClipboardText() {
        try {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip()) {
                ClipData c = cb.getPrimaryClip();
                if (c != null && c.getItemCount() > 0) {
                    CharSequence t = c.getItemAt(0).coerceToText(this);
                    if (t != null && t.length() > 0) {
                        String preview = t.length() > 60 ? t.subSequence(0, 60) + "…" : t.toString();
                        android.widget.Toast.makeText(this, getString(R.string.ime_paste_preview, preview),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
            }
            android.widget.Toast.makeText(this, R.string.ime_paste_empty,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "Failed to preview clipboard: " + e.getMessage());
        }
    }

    private void copySelectedText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (copyButton != null) {
            copyButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }

        if (!ic.performContextMenuAction(android.R.id.copy)) {
            CharSequence selected = ic.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("copied_text", selected));
                }
            } else {
                android.widget.Toast.makeText(this, R.string.ime_no_selection,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void clearClipboard() {
        if (clearClipboardButton != null) {
            clearClipboardButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;

        if (!clipboard.hasPrimaryClip()) {
            android.widget.Toast.makeText(this, R.string.ime_paste_empty,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip();
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
        }
        android.widget.Toast.makeText(this, R.string.ime_clipboard_cleared,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void cutSelectedText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        if (cutButton != null) {
            cutButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }

        if (!ic.performContextMenuAction(android.R.id.cut)) {
            CharSequence selected = ic.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("copied_text", selected));
                }
                ic.commitText("", 1);
            } else {
                android.widget.Toast.makeText(this, R.string.ime_no_selection,
                        android.widget.Toast.LENGTH_SHORT).show();
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
        long totalMs = totalAudioMs > 0L
                ? totalAudioMs
                : (isRecording ? elapsedMs : lastRecordingDurationMs);
        long delayMs = Math.max(0L, totalMs - processedAudioMs);

        statsView.setText(getString(R.string.ime_stats_format, formatElapsed(elapsedMs),
                processedWords, formatSpeed(currentRateFresh ? currentProcessingSpeed : -1f),
                formatSpeed(averageProcessingSpeed), formatDelay(delayMs)));
    }

    static int calculateCatchUpPercent(long processedAudioMs, long totalAudioMs,
            long processingStartedAtMs, long nowMs, float averageSpeed) {
        long totalMs = totalAudioMs;
        if (totalMs <= 0L) {
            return 0;
        }
        if (processedAudioMs > 0L) {
            return (int) Math.min(100, Math.max(0, (processedAudioMs * 100L) / totalMs));
        }
        if (processingStartedAtMs > 0L && nowMs >= processingStartedAtMs) {
            float speed = averageSpeed > 0f ? averageSpeed : 1.0f;
            long expectedProcessingMs = (long) (totalMs / speed);
            if (expectedProcessingMs > 0) {
                long elapsed = nowMs - processingStartedAtMs;
                return (int) Math.min(95, Math.max(0, (elapsed * 100L) / expectedProcessingMs));
            }
        }
        return 0;
    }

    private void updateProcessingProgress() {
        if (progressPercentView == null) return;
        if (!isProcessingStatus()) {
            progressPercentView.setText("0%");
            return;
        }

        long totalMs = totalAudioMs > 0L ? totalAudioMs : lastRecordingDurationMs;
        long nowMs = android.os.SystemClock.elapsedRealtime();
        int percent = calculateCatchUpPercent(processedAudioMs, totalMs, processingStartedAtMs, nowMs, averageProcessingSpeed);
        progressPercentView.setText(percent + "%");
    }

    private void hideStatsIfIdle() {
        if (!isRecording && !isProcessingStatus() && statsView != null) {
            statsView.setVisibility(View.GONE);
        }
        mainHandler.removeCallbacks(statsTicker);
    }

    private static String formatElapsed(long elapsedMs) {
        return DictationStatsManager.formatElapsed(elapsedMs);
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

    static String formatDelay(long delayMs) {
        return formatElapsed(Math.max(0L, delayMs));
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    /** "Append a space" is default ON; the marker file is the opt-out. */
    private boolean appendSpaceEnabled() {
        return !new File(getFilesDir(), "no_append_space").exists();
    }

    /** "Process while recording" is default ON; the marker file is the opt-out. */
    private boolean isStreamingEnabled() {
        return !new File(getFilesDir(), "no_ime_streaming").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }

    private void updateModelSelectorState(boolean disabled) {
        if (modelSpinner != null) {
            modelSpinner.setEnabled(!disabled);
            modelSpinner.setAlpha(disabled ? 0.5f : 1.0f);
        }
        if (modelButton != null) {
            modelButton.setEnabled(!disabled);
            modelButton.setAlpha(disabled ? 0.5f : 1.0f);
        }
    }

    private void setupQwertyKeys(View root) {
        letterKeys.clear();
        int[] letterIds = {
            R.id.ime_key_q, R.id.ime_key_w, R.id.ime_key_e, R.id.ime_key_r, R.id.ime_key_t,
            R.id.ime_key_y, R.id.ime_key_u, R.id.ime_key_i, R.id.ime_key_o, R.id.ime_key_p,
            R.id.ime_key_aring,
            R.id.ime_key_a, R.id.ime_key_s, R.id.ime_key_d, R.id.ime_key_f, R.id.ime_key_g,
            R.id.ime_key_h, R.id.ime_key_j, R.id.ime_key_k, R.id.ime_key_l, R.id.ime_key_ouml,
            R.id.ime_key_auml,
            R.id.ime_key_z, R.id.ime_key_x, R.id.ime_key_c, R.id.ime_key_v, R.id.ime_key_b,
            R.id.ime_key_n, R.id.ime_key_m
        };
        for (int id : letterIds) {
            TextView tv = root.findViewById(id);
            if (tv != null) {
                letterKeys.add(tv);
                tv.setOnClickListener(v -> onLetterKeyClicked(tv));
            }
        }

        int[] symbolIds = {
            R.id.ime_key_backtick, R.id.ime_key_quote_single, R.id.ime_key_quote_double,
            R.id.ime_key_brace_open, R.id.ime_key_brace_close, R.id.ime_key_bracket_open,
            R.id.ime_key_bracket_close, R.id.ime_key_underscore, R.id.ime_key_minus,
            R.id.ime_key_equal, R.id.ime_key_question,
            R.id.ime_key_exclamation, R.id.ime_key_at, R.id.ime_key_hash, R.id.ime_key_dollar,
            R.id.ime_key_percent, R.id.ime_key_caret, R.id.ime_key_ampersand, R.id.ime_key_asterisk,
            R.id.ime_key_paren_open, R.id.ime_key_paren_close, R.id.ime_key_slash,
            R.id.ime_key_1, R.id.ime_key_2, R.id.ime_key_3, R.id.ime_key_4, R.id.ime_key_5,
            R.id.ime_key_6, R.id.ime_key_7, R.id.ime_key_8, R.id.ime_key_9, R.id.ime_key_0,
            R.id.ime_key_comma, R.id.ime_key_dot
        };
        for (int id : symbolIds) {
            TextView tv = root.findViewById(id);
            if (tv != null) {
                tv.setOnClickListener(v -> onSymbolKeyClicked(tv));
            }
        }
    }

    private void onLetterKeyClicked(TextView tv) {
        tv.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        CharSequence text = tv.getText();
        if (text == null || text.length() == 0) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
        if (shiftState == ShiftState.ONE_SHOT) {
            shiftState = ShiftState.OFF;
            updateShiftUI();
        }
    }

    private void onSymbolKeyClicked(TextView tv) {
        tv.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        CharSequence text = tv.getText();
        if (text == null || text.length() == 0) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
    }

    private void onShiftKeyClicked() {
        if (shiftKey == null) return;
        shiftKey.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        long now = android.os.SystemClock.uptimeMillis();
        if (shiftState == ShiftState.CAPS_LOCK) {
            shiftState = ShiftState.OFF;
        } else if (shiftState == ShiftState.ONE_SHOT) {
            if (now - lastShiftTapTime < android.view.ViewConfiguration.getDoubleTapTimeout()) {
                shiftState = ShiftState.CAPS_LOCK;
            } else {
                shiftState = ShiftState.OFF;
            }
        } else {
            shiftState = ShiftState.ONE_SHOT;
        }
        lastShiftTapTime = now;
        updateShiftUI();
    }

    private void updateShiftUI() {
        boolean isUpper = (shiftState != ShiftState.OFF);
        for (TextView tv : letterKeys) {
            CharSequence current = tv.getText();
            if (current != null && current.length() > 0) {
                tv.setText(isUpper ? current.toString().toUpperCase(java.util.Locale.ROOT)
                                   : current.toString().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (shiftKey != null) {
            if (shiftState == ShiftState.CAPS_LOCK) {
                shiftKey.setImageResource(R.drawable.ic_shift_caps);
                shiftKey.setColorFilter(MaterialColors.getColor(shiftKey,
                        com.google.android.material.R.attr.colorPrimary));
            } else if (shiftState == ShiftState.ONE_SHOT) {
                shiftKey.setImageResource(R.drawable.ic_shift);
                shiftKey.setColorFilter(MaterialColors.getColor(shiftKey,
                        com.google.android.material.R.attr.colorPrimary));
            } else {
                shiftKey.setImageResource(R.drawable.ic_shift);
                shiftKey.setColorFilter(MaterialColors.getColor(shiftKey,
                        com.google.android.material.R.attr.colorOnSurfaceVariant));
            }
        }
    }

    private static final String PREF_QWERTY_EXPANDED = "qwerty_expanded";

    private boolean isQwertyExpanded() {
        return new File(getFilesDir(), PREF_QWERTY_EXPANDED).exists();
    }

    private void setQwertyExpanded(boolean expanded) {
        File file = new File(getFilesDir(), PREF_QWERTY_EXPANDED);
        try {
            if (expanded) {
                if (!file.exists()) file.createNewFile();
            } else {
                if (file.exists()) file.delete();
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not update qwerty expanded state", e);
        }
        updateQwertyVisibility();
    }

    private void toggleQwertyKeyboard() {
        if (expandToggleButton != null) {
            expandToggleButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
        setQwertyExpanded(!isQwertyExpanded());
    }

    private void updateQwertyVisibility() {
        if (isHistoryVisible) return;
        boolean expanded = isQwertyExpanded();
        if (qwertyContainer != null) {
            qwertyContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (expandToggleButton != null) {
            expandToggleButton.setImageResource(expanded
                    ? R.drawable.ic_expand_less
                    : R.drawable.ic_expand_more);
            expandToggleButton.setContentDescription(getString(expanded
                    ? R.string.ime_collapse_keys
                    : R.string.ime_expand_keys));
        }
        updateActionRowLayout(expanded);
    }

    private void updateActionRowLayout(boolean expanded) {
        if (actionRow == null || recordContainer == null || spaceButton == null
                || backspaceButton == null || deleteWordButton == null || enterButton == null) {
            return;
        }
        int h44 = dp(44);
        int margin6 = dp(6);

        actionRow.removeAllViews();
        if (expanded) {
            // Expanded (QWERTY mode): Push-to-speak (132dp) on the left, Space (flex) in center
            LinearLayout.LayoutParams recordLp = new LinearLayout.LayoutParams(dp(132), h44, 0.0f);
            recordLp.setMarginEnd(margin6);
            recordContainer.setLayoutParams(recordLp);

            LinearLayout.LayoutParams spaceLp = new LinearLayout.LayoutParams(0, h44, 1.0f);
            spaceLp.setMarginEnd(margin6);
            spaceButton.setLayoutParams(spaceLp);

            actionRow.addView(recordContainer);
            actionRow.addView(spaceButton);
        } else {
            // Collapsed (Voice mode): Space (44dp) on the left, Mic (flex) in center
            LinearLayout.LayoutParams spaceLp = new LinearLayout.LayoutParams(h44, h44, 0.0f);
            spaceLp.setMarginEnd(margin6);
            spaceButton.setLayoutParams(spaceLp);

            LinearLayout.LayoutParams recordLp = new LinearLayout.LayoutParams(0, h44, 1.0f);
            recordLp.setMarginEnd(margin6);
            recordContainer.setLayoutParams(recordLp);

            actionRow.addView(spaceButton);
            actionRow.addView(recordContainer);
        }

        LinearLayout.LayoutParams backspaceLp = new LinearLayout.LayoutParams(h44, h44, 0.0f);
        backspaceLp.setMarginEnd(margin6);
        backspaceButton.setLayoutParams(backspaceLp);
        actionRow.addView(backspaceButton);

        LinearLayout.LayoutParams deleteWordLp = new LinearLayout.LayoutParams(h44, h44, 0.0f);
        deleteWordLp.setMarginEnd(margin6);
        deleteWordButton.setLayoutParams(deleteWordLp);
        actionRow.addView(deleteWordButton);

        LinearLayout.LayoutParams enterLp = new LinearLayout.LayoutParams(h44, h44, 0.0f);
        enterButton.setLayoutParams(enterLp);
        actionRow.addView(enterButton);
    }

    private int dp(float dpVal) {
        return Math.round(dpVal * getResources().getDisplayMetrics().density);
    }

    private void deletePreviousWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            ic.commitText("", 1);
            return;
        }

        CharSequence before = ic.getTextBeforeCursor(128, 0);
        int deleteCount = calculateDeleteCount(before);
        ic.deleteSurroundingText(deleteCount, 0);
    }

    static int calculateDeleteCount(CharSequence before) {
        if (before == null || before.length() == 0) {
            return 1;
        }

        int len = before.length();
        int i = len - 1;

        // 1. Consume trailing whitespace
        while (i >= 0 && Character.isWhitespace(before.charAt(i))) {
            i--;
        }

        // 2. Consume trailing punctuation (if any)
        boolean hasPunctuation = false;
        while (i >= 0 && isPunctuation(before.charAt(i))) {
            hasPunctuation = true;
            i--;
        }

        // 3. Consume whitespace between punctuation and word (if any, e.g. "word , ")
        if (hasPunctuation) {
            while (i >= 0 && Character.isWhitespace(before.charAt(i))) {
                i--;
            }
        }

        // 4. Consume word characters
        if (i >= 0) {
            char c = before.charAt(i);
            if (isCjk(c)) {
                // For CJK ideographs/kana, delete one ideograph
                i--;
            } else if (isWordChar(c)) {
                while (i >= 0) {
                    char cur = before.charAt(i);
                    if (isWordChar(cur)) {
                        i--;
                    } else if ((cur == '\'' || cur == '’') && i > 0 && isWordChar(before.charAt(i - 1))) {
                        // Contraction apostrophe like don't or it's
                        i--;
                    } else {
                        break;
                    }
                }

                // 5. Also consume leading whitespace before this word, if any
                while (i >= 0 && Character.isWhitespace(before.charAt(i))) {
                    i--;
                }
            }
        }

        int deleteCount = len - (i + 1);
        return deleteCount <= 0 ? 1 : deleteCount;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isPunctuation(char c) {
        int type = Character.getType(c);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    private void displayLastWpm(DictationStatsManager.SessionRecord session) {
        if (lastWpmView == null || session == null) return;
        int speechWpm = Math.round(session.wpm);
        int totalWpm = Math.round(session.totalWpm > 0 ? session.totalWpm : session.wpm);
        String speechTime = formatElapsed(session.durationMs);
        String totalTime = formatElapsed(session.totalDurationMs > 0 ? session.totalDurationMs : session.durationMs);

        lastWpmView.setText(getString(R.string.ime_last_wpm_format,
                speechWpm, totalWpm, session.words, speechTime, totalTime));
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

    private void openMainActivity() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to open MainActivity", t);
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
