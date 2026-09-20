package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Utility methods for checking speech model availability and installation status.
 */
public final class ModelUtils {
    private static final String TAG = "ModelUtils";
    public static final String BUILTIN_MODEL_DIR = "builtin-model";
    public static final String EXTRACTION_COMPLETE_MARKER = ".extraction_complete";
    public static final String ACTIVE_MODEL_FILE = "active_model";

    private ModelUtils() {
    }

    /**
     * Checks if the built-in model is available either from APK assets or
     * already extracted into filesDir.
     */
    public static boolean hasBuiltinModel(Context context) {
        if (context == null) return false;

        // 1. Check if already extracted in filesDir/builtin-model
        File modelDir = new File(context.getFilesDir(), BUILTIN_MODEL_DIR);
        File marker = new File(modelDir, EXTRACTION_COMPLETE_MARKER);
        if (marker.exists()) {
            File[] files = modelDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".gguf"));
            if (files != null && files.length > 0) {
                return true;
            }
        }

        // 2. Check if APK assets contain builtin-model
        try {
            String[] list = context.getAssets().list(BUILTIN_MODEL_DIR);
            if (list != null) {
                for (String item : list) {
                    if (item.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Error checking assets for builtin model", e);
        }
        return false;
    }

    public static File getModelsDir(Context context) {
        return new File(context.getFilesDir(), "models");
    }

    public static boolean isModelFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gguf") || lower.endsWith(".bin");
    }

    public static List<String> getInstalledImportedModels(Context context) {
        List<String> list = new ArrayList<>();
        File dir = getModelsDir(context);
        File[] files = dir.listFiles((d, name) -> isModelFileName(name));
        if (files != null) {
            for (File f : files) {
                list.add(f.getName());
            }
        }
        list.sort(String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    public static boolean hasAnyModelInstalled(Context context) {
        return hasBuiltinModel(context) || !getInstalledImportedModels(context).isEmpty();
    }

    /**
     * Resolves the active model selection:
     * - Returns the filename if an imported model is selected and installed.
     * - Returns "" if the built-in model is selected and available in this build.
     * - Returns null if NO model is selected or available.
     */
    public static String getActiveModel(Context context) {
        File file = new File(context.getFilesDir(), ACTIVE_MODEL_FILE);
        String name = "";
        if (file.isFile()) {
            try {
                name = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                name = "";
            }
        }

        if (!name.isEmpty()) {
            if (new File(getModelsDir(context), name).isFile()) {
                return name;
            }
            // Stored active model file no longer exists
        }

        if (hasBuiltinModel(context)) {
            return "";
        }

        return null;
    }
}
