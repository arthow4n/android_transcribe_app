package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Manages transcription history persistence, private dictation mode,
 * and real-time streaming drafts. Stored in dictation_history.json
 * in the shared files directory.
 */
public final class TranscriptionHistoryManager {
    private static final String TAG = "TranscriptionHistory";
    private static final String HISTORY_FILE = "dictation_history.json";
    private static final String PRIVATE_MODE_MARKER = "incognito_mode";
    private static final int MAX_HISTORY = 50;
    public static final long RETENTION_MS = 10 * 60 * 1000L; // 10 minutes
    private static final Object LOCK = new Object();

    // ID of the in-flight draft for the current recording session
    private static volatile String activeDraftId = null;

    private TranscriptionHistoryManager() {
    }

    public static class HistoryEntry {
        public final String id;
        public final long timestamp;
        public String text;
        public boolean isDraft;
        public final String language;
        public final String model;
        public final long durationMs;

        public HistoryEntry(String id, long timestamp, String text, boolean isDraft,
                            String language, String model, long durationMs) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.timestamp = timestamp;
            this.text = text != null ? text : "";
            this.isDraft = isDraft;
            this.language = language == null || language.isEmpty() ? "Auto" : language;
            this.model = model == null || model.isEmpty() ? "Built-in" : model;
            this.durationMs = durationMs;
        }

        JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("timestamp", timestamp);
            obj.put("text", text);
            obj.put("isDraft", isDraft);
            obj.put("language", language);
            obj.put("model", model);
            obj.put("durationMs", durationMs);
            return obj;
        }

        static HistoryEntry fromJson(JSONObject obj) {
            String id = obj.optString("id", UUID.randomUUID().toString());
            long ts = obj.optLong("timestamp", System.currentTimeMillis());
            String text = obj.optString("text", "");
            boolean isDraft = obj.optBoolean("isDraft", false);
            String lang = obj.optString("language", "Auto");
            String mod = obj.optString("model", "Built-in");
            long dur = obj.optLong("durationMs", 0L);
            return new HistoryEntry(id, ts, text, isDraft, lang, mod, dur);
        }
    }

    public static boolean isPrivateMode(Context context) {
        return new File(context.getFilesDir(), PRIVATE_MODE_MARKER).exists();
    }

    public static void setPrivateMode(Context context, boolean privateMode) {
        File file = new File(context.getFilesDir(), PRIVATE_MODE_MARKER);
        if (privateMode) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create private mode marker", e);
            }
        } else {
            if (file.exists()) {
                file.delete();
            }
        }
    }

    public static void startSession() {
        activeDraftId = null;
    }

    /**
     * Updates or creates a live streaming draft on the fly.
     */
    public static void updateDraft(Context context, String partialText, String language, String model) {
        if (isPrivateMode(context) || partialText == null || partialText.trim().isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            List<HistoryEntry> list = loadEntriesInternal(context);
            long now = System.currentTimeMillis();
            if (activeDraftId != null) {
                for (HistoryEntry e : list) {
                    if (activeDraftId.equals(e.id)) {
                        e.text = partialText.trim();
                        e.isDraft = true;
                        saveEntriesInternal(context, list);
                        return;
                    }
                }
            }
            // Create a new draft entry at the top of history
            String newId = UUID.randomUUID().toString();
            activeDraftId = newId;
            HistoryEntry entry = new HistoryEntry(newId, now, partialText.trim(), true, language, model, 0L);
            list.add(0, entry);
            trimInternal(list);
            saveEntriesInternal(context, list);
        }
    }

    /**
     * Finalizes the current draft or records the final transcribed text.
     */
    public static void finalizeEntry(Context context, String finalText, String language, String model, long durationMs) {
        if (isPrivateMode(context) || finalText == null || finalText.trim().isEmpty()) {
            activeDraftId = null;
            return;
        }
        synchronized (LOCK) {
            List<HistoryEntry> list = loadEntriesInternal(context);
            long now = System.currentTimeMillis();
            if (activeDraftId != null) {
                for (HistoryEntry e : list) {
                    if (activeDraftId.equals(e.id)) {
                        e.text = finalText.trim();
                        e.isDraft = false;
                        activeDraftId = null;
                        saveEntriesInternal(context, list);
                        return;
                    }
                }
            }
            activeDraftId = null;
            String newId = UUID.randomUUID().toString();
            HistoryEntry entry = new HistoryEntry(newId, now, finalText.trim(), false, language, model, durationMs);
            list.add(0, entry);
            trimInternal(list);
            saveEntriesInternal(context, list);
        }
    }

    public static List<HistoryEntry> getHistory(Context context) {
        synchronized (LOCK) {
            return new ArrayList<>(loadEntriesInternal(context));
        }
    }

    public static void deleteEntry(Context context, String id) {
        if (id == null) return;
        synchronized (LOCK) {
            List<HistoryEntry> list = loadEntriesInternal(context);
            boolean removed = false;
            Iterator<HistoryEntry> it = list.iterator();
            while (it.hasNext()) {
                if (id.equals(it.next().id)) {
                    it.remove();
                    removed = true;
                    break;
                }
            }
            if (id.equals(activeDraftId)) {
                activeDraftId = null;
            }
            if (removed) {
                saveEntriesInternal(context, list);
            }
        }
    }

    public static void clearAll(Context context) {
        synchronized (LOCK) {
            activeDraftId = null;
            File file = new File(context.getFilesDir(), HISTORY_FILE);
            if (file.exists()) {
                file.delete();
            }
        }
    }

    public static String formatRelativeTime(long timestamp) {
        long diffMs = Math.max(0L, System.currentTimeMillis() - timestamp);
        long seconds = diffMs / 1000L;
        if (seconds < 60) {
            return "Just now";
        }
        long minutes = seconds / 60L;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60L;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24L;
        return days + "d ago";
    }

    private static boolean pruneExpired(List<HistoryEntry> list, long now) {
        boolean pruned = false;
        Iterator<HistoryEntry> it = list.iterator();
        while (it.hasNext()) {
            HistoryEntry e = it.next();
            if (now - e.timestamp > RETENTION_MS) {
                if (activeDraftId != null && activeDraftId.equals(e.id)) {
                    activeDraftId = null;
                }
                it.remove();
                pruned = true;
            }
        }
        return pruned;
    }

    private static void trimInternal(List<HistoryEntry> list) {
        long now = System.currentTimeMillis();
        pruneExpired(list, now);
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
    }

    private static List<HistoryEntry> loadEntriesInternal(Context context) {
        List<HistoryEntry> list = new ArrayList<>();
        File file = new File(context.getFilesDir(), HISTORY_FILE);
        if (!file.exists()) {
            return list;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String jsonStr = new String(bytes, StandardCharsets.UTF_8);
            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                list.add(HistoryEntry.fromJson(obj));
            }
        } catch (Exception e) {
            Log.w(TAG, "Error loading history, starting fresh: " + e.getMessage());
        }

        long now = System.currentTimeMillis();
        boolean pruned = pruneExpired(list, now);
        if (pruned) {
            saveEntriesInternal(context, list);
        }

        return list;
    }

    private static void saveEntriesInternal(Context context, List<HistoryEntry> list) {
        File file = new File(context.getFilesDir(), HISTORY_FILE);
        File tempFile = new File(context.getFilesDir(), HISTORY_FILE + ".tmp");
        try {
            JSONArray array = new JSONArray();
            for (HistoryEntry e : list) {
                array.put(e.toJson());
            }
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (tempFile.renameTo(file)) {
                // renamed successfully
            } else {
                if (file.delete() && tempFile.renameTo(file)) {
                    // renamed after delete
                } else {
                    Log.e(TAG, "Failed to atomic-rename history temp file");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving history: " + e.getMessage(), e);
        }
    }
}
