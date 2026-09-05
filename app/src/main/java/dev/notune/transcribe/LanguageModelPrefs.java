package dev.notune.transcribe;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Persists the last selected model for each language. The settings activity
 * and the voice-keyboard IME run in different processes, so this deliberately
 * uses files in the shared app data directory instead of in-memory state.
 */
final class LanguageModelPrefs {
    private static final String TAG = "LanguageModelPrefs";
    private static final String DIRECTORY = "language_models";

    private LanguageModelPrefs() {
    }

    /** Returns null when no preset exists; an empty value means the built-in model. */
    static String read(Context context, String language) {
        File file = mappingFile(context, language, false);
        if (file == null || !file.isFile()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            Log.w(TAG, "Could not read language model preset", e);
            return null;
        }
    }

    /** Saves a model filename; null/empty selects the built-in model. */
    static boolean write(Context context, String language, String modelFile) {
        File file = mappingFile(context, language, true);
        if (file == null) return false;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            String value = modelFile == null ? "" : modelFile;
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Could not write language model preset", e);
            tmp.delete();
            return false;
        }
        if (tmp.renameTo(file)) return true;
        // Some providers/filesystems refuse a rename over an existing file.
        if (file.delete() && tmp.renameTo(file)) return true;
        tmp.delete();
        Log.e(TAG, "Could not install language model preset");
        return false;
    }

    /** True for the built-in model or an installed imported GGUF/Whisper BIN. */
    static boolean isInstalledModel(Context context, String modelFile) {
        if (modelFile == null || modelFile.isEmpty()) return true;
        String lower = modelFile.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".gguf") && !lower.endsWith(".bin")) return false;
        return new File(new File(context.getFilesDir(), "models"), modelFile).isFile();
    }

    private static File mappingFile(Context context, String language, boolean createDirectory) {
        String key = language == null || language.trim().isEmpty()
                ? "auto"
                : language.trim();
        // URL-safe, unpadded Base64 makes a collision-free filename for any
        // legacy/custom language tag while excluding path separators.
        String encoded = Base64.encodeToString(key.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        File dir = new File(context.getFilesDir(), DIRECTORY);
        if (createDirectory && !dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Could not create language model preset directory");
            return null;
        }
        return new File(dir, encoded);
    }
}
