package dev.notune.transcribe;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages persistence for per-model and active streaming mode preferences.
 *
 * Supported modes:
 * - legacy: Existing repeated-transcription implementation (default).
 * - ultra_fast: Native streaming with 0 right attention (~80 ms nominal chunk).
 * - low_latency: Native streaming with 3 right attention (~320 ms nominal chunk).
 * - balanced: Native streaming with 6 right attention (~560 ms nominal chunk).
 * - accuracy: Native streaming with 13 right attention (~1,120 ms nominal chunk).
 */
public final class StreamingModePrefs {
    private static final String TAG = "StreamingModePrefs";

    public static final String MODE_LEGACY = "legacy";
    public static final String MODE_ULTRA_FAST = "ultra_fast";
    public static final String MODE_LOW_LATENCY = "low_latency";
    public static final String MODE_BALANCED = "balanced";
    public static final String MODE_ACCURACY = "accuracy";

    public static final String DEFAULT_MODE = MODE_LEGACY;

    public static final String[] MODES = {
            MODE_LEGACY,
            MODE_ULTRA_FAST,
            MODE_LOW_LATENCY,
            MODE_BALANCED,
            MODE_ACCURACY,
    };

    public static final int[] MODE_LABELS = {
            R.string.models_streaming_mode_legacy,
            R.string.models_streaming_mode_ultra_fast,
            R.string.models_streaming_mode_low_latency,
            R.string.models_streaming_mode_balanced,
            R.string.models_streaming_mode_accuracy,
    };

    /**
     * Returns a short nominal latency label (e.g. "80ms", "320ms", "560ms", "1.1s")
     * for native streaming modes, or null for legacy mode.
     */
    public static String getNominalLatencyLabel(String mode) {
        if (mode == null) return null;
        switch (mode) {
            case MODE_ULTRA_FAST:
                return "80ms";
            case MODE_LOW_LATENCY:
                return "320ms";
            case MODE_BALANCED:
                return "560ms";
            case MODE_ACCURACY:
                return "1.1s";
            default:
                return null;
        }
    }

    private static final String DIRECTORY = "model_streaming_modes";
    private static final String ACTIVE_CONFIG_FILE = "model_streaming_mode";

    private static final Map<String, Boolean> CAPABILITY_CACHE = new ConcurrentHashMap<>();

    private StreamingModePrefs() {
    }

    /**
     * Reads the configured streaming mode for a specific model name.
     * Returns "legacy" if no custom preference is stored.
     */
    public static String getModeForModel(Context context, String modelName) {
        File file = mappingFile(context, modelName, false);
        if (file == null || !file.isFile()) {
            return DEFAULT_MODE;
        }
        try {
            String val = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            for (String mode : MODES) {
                if (mode.equals(val)) return mode;
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read streaming mode for model: " + modelName, e);
        }
        return DEFAULT_MODE;
    }

    /**
     * Persists the streaming mode preference for a specific model name.
     */
    public static boolean setModeForModel(Context context, String modelName, String mode) {
        File file = mappingFile(context, modelName, true);
        if (file == null) return false;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            String val = (mode == null || mode.isEmpty()) ? DEFAULT_MODE : mode;
            out.write(val.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Could not write streaming mode for model: " + modelName, e);
            tmp.delete();
            return false;
        }
        if (tmp.renameTo(file)) return true;
        if (file.delete() && tmp.renameTo(file)) return true;
        tmp.delete();
        return false;
    }

    /**
     * Reads the currently active streaming mode config (read by Rust engine).
     */
    public static String getActiveMode(Context context) {
        File f = new File(context.getFilesDir(), ACTIVE_CONFIG_FILE);
        if (!f.isFile()) return DEFAULT_MODE;
        try {
            String val = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            for (String mode : MODES) {
                if (mode.equals(val)) return mode;
            }
        } catch (IOException ignored) {}
        return DEFAULT_MODE;
    }

    /**
     * Writes the active streaming mode config file for the Rust engine.
     */
    public static boolean setActiveMode(Context context, String mode) {
        File f = new File(context.getFilesDir(), ACTIVE_CONFIG_FILE);
        if (mode == null || mode.isEmpty() || DEFAULT_MODE.equals(mode)) {
            return !f.exists() || f.delete();
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(mode.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + ACTIVE_CONFIG_FILE, e);
            return false;
        }
    }

    /**
     * Synchronizes the active streaming mode file with the preference saved for
     * the given model.
     */
    public static void syncActiveModelStreamingMode(Context context, String activeModelName) {
        if (!isStreamingCapableModel(context, activeModelName)) {
            // Non-streaming models always use legacy mode
            setActiveMode(context, DEFAULT_MODE);
            return;
        }
        String mode = getModeForModel(context, activeModelName);
        setActiveMode(context, mode);
    }

    /**
     * Returns true if the model is known to support Nemotron cache-aware streaming.
     */
    public static boolean isStreamingCapableModel(Context context, String modelFileName) {
        if (modelFileName == null || modelFileName.trim().isEmpty()) {
            // Built-in model (Parakeet TDT 110M) does not support native streaming
            return false;
        }
        String trimmed = modelFileName.trim();
        Boolean cached = CAPABILITY_CACHE.get(trimmed);
        if (cached != null) {
            return cached;
        }

        File file = new File(ModelUtils.getModelsDir(context), trimmed);
        if (!file.isFile()) {
            return false;
        }

        boolean capable = false;
        try {
            capable = ModelsActivity.isStreamingCapableNative(file.getAbsolutePath());
        } catch (UnsatisfiedLinkError | Exception e) {
            Log.w(TAG, "Native streaming check failed, falling back to name heuristic", e);
            capable = trimmed.toLowerCase(Locale.ROOT).contains("nemotron");
        }

        CAPABILITY_CACHE.put(trimmed, capable);
        return capable;
    }

    private static File mappingFile(Context context, String modelName, boolean createDirectory) {
        String key = (modelName == null || modelName.trim().isEmpty())
                ? "__builtin__"
                : modelName.trim();
        String encoded = Base64.encodeToString(key.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        File dir = new File(context.getFilesDir(), DIRECTORY);
        if (createDirectory && !dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Could not create streaming mode preferences directory");
            return null;
        }
        return new File(dir, encoded);
    }
}
