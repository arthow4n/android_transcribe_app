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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages persistence and calculations for dictation paste statistics,
 * words-per-minute (WPM), and breakdowns by language and model.
 * Because the IME runs in the :ime process and the settings activity runs
 * in the main process, statistics are persisted atomically in the shared
 * app data files directory.
 */
public final class DictationStatsManager {
    private static final String TAG = "DictationStats";
    private static final String STATS_FILE = "dictation_stats.json";
    private static final int MAX_HISTORY = 500;

    private DictationStatsManager() {
    }

    /**
     * Represents a single voice dictation paste event.
     */
    public static class SessionRecord {
        public final long timestamp;
        public final int words;
        public final int chars;
        public final long durationMs;
        public final float wpm;
        public final String language;
        public final String model;

        public SessionRecord(long timestamp, int words, int chars, long durationMs,
                             float wpm, String language, String model) {
            this.timestamp = timestamp;
            this.words = words;
            this.chars = chars;
            this.durationMs = durationMs;
            this.wpm = wpm;
            this.language = language == null || language.isEmpty() ? "Auto" : language;
            this.model = model == null || model.isEmpty() ? "Built-in (Parakeet TDT 0.6B v3)" : model;
        }

        JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("timestamp", timestamp);
            obj.put("words", words);
            obj.put("chars", chars);
            obj.put("durationMs", durationMs);
            obj.put("wpm", (double) wpm);
            obj.put("language", language);
            obj.put("model", model);
            return obj;
        }

        static SessionRecord fromJson(JSONObject obj) {
            long ts = obj.optLong("timestamp", System.currentTimeMillis());
            int w = obj.optInt("words", 0);
            int c = obj.optInt("chars", 0);
            long d = obj.optLong("durationMs", 0L);
            float wpm = (float) obj.optDouble("wpm", 0.0);
            String lang = obj.optString("language", "Auto");
            String mod = obj.optString("model", "Built-in (Parakeet TDT 0.6B v3)");
            return new SessionRecord(ts, w, c, d, wpm, lang, mod);
        }
    }

    /**
     * Aggregated statistics for a group (a specific language or model).
     */
    public static class GroupStats {
        public final String name;
        public int count = 0;
        public int totalWords = 0;
        public long totalDurationMs = 0L;

        public GroupStats(String name) {
            this.name = name;
        }

        public void add(SessionRecord record) {
            count++;
            totalWords += record.words;
            totalDurationMs += record.durationMs;
        }

        public float getAverageWpm() {
            if (totalDurationMs > 0) {
                return (totalWords * 60000.0f) / totalDurationMs;
            }
            return 0f;
        }
    }

    /**
     * Overall statistics summary across all pastes.
     */
    public static class StatsSummary {
        public int totalPastes = 0;
        public int totalWords = 0;
        public long totalDurationMs = 0L;
        public float averageWpm = 0f;
        public float peakWpm = 0f;
        public SessionRecord lastPaste = null;
        public final Map<String, GroupStats> byLanguage = new LinkedHashMap<>();
        public final Map<String, GroupStats> byModel = new LinkedHashMap<>();
        public final List<SessionRecord> recentSessions = new ArrayList<>();
    }

    /**
     * Records a new dictation paste event. Returns the created session record,
     * or null if the text contained no valid words.
     */
    public static synchronized SessionRecord recordPaste(Context context, String text,
                                                         long durationMs, String rawLanguage,
                                                         String rawModel) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        int words = countWords(text);
        if (words <= 0) {
            return null;
        }
        int chars = text.length();
        long dur = Math.max(300L, durationMs);
        float wpm = (words * 60000.0f) / dur;
        String lang = normalizeLanguage(rawLanguage);
        String model = normalizeModelName(rawModel);

        SessionRecord record = new SessionRecord(
                System.currentTimeMillis(), words, chars, dur, wpm, lang, model);

        List<SessionRecord> list = loadRecords(context);
        list.add(record);
        if (list.size() > MAX_HISTORY) {
            list = new ArrayList<>(list.subList(list.size() - MAX_HISTORY, list.size()));
        }
        saveRecords(context, list);
        return record;
    }

    /**
     * Retrieves the most recent dictation paste session, or null if none recorded.
     */
    public static synchronized SessionRecord getLastPaste(Context context) {
        List<SessionRecord> list = loadRecords(context);
        if (list.isEmpty()) return null;
        return list.get(list.size() - 1);
    }

    /**
     * Aggregates all recorded sessions into an overall summary with language
     * and model breakdowns.
     */
    public static synchronized StatsSummary getSummary(Context context) {
        List<SessionRecord> list = loadRecords(context);
        StatsSummary summary = new StatsSummary();
        summary.totalPastes = list.size();

        float peak = 0f;
        for (SessionRecord rec : list) {
            summary.totalWords += rec.words;
            summary.totalDurationMs += rec.durationMs;
            if (rec.wpm > peak) {
                peak = rec.wpm;
            }

            // By Language
            GroupStats langStats = summary.byLanguage.get(rec.language);
            if (langStats == null) {
                langStats = new GroupStats(rec.language);
                summary.byLanguage.put(rec.language, langStats);
            }
            langStats.add(rec);

            // By Model
            GroupStats modelStats = summary.byModel.get(rec.model);
            if (modelStats == null) {
                modelStats = new GroupStats(rec.model);
                summary.byModel.put(rec.model, modelStats);
            }
            modelStats.add(rec);
        }

        if (summary.totalDurationMs > 0) {
            summary.averageWpm = (summary.totalWords * 60000.0f) / summary.totalDurationMs;
        }
        summary.peakWpm = peak;
        if (!list.isEmpty()) {
            summary.lastPaste = list.get(list.size() - 1);
            // Reverse order for recent sessions (most recent first)
            for (int i = list.size() - 1; i >= 0 && summary.recentSessions.size() < 50; i--) {
                summary.recentSessions.add(list.get(i));
            }
        }
        return summary;
    }

    /**
     * Clears all recorded statistics.
     */
    public static synchronized boolean clearStats(Context context) {
        File file = new File(context.getFilesDir(), STATS_FILE);
        if (file.exists()) {
            return file.delete();
        }
        return true;
    }

    /**
     * Splits text into words. Supports space-separated languages and CJK scripts
     * (Chinese/Japanese/Korean) where characters function as individual words/morphemes.
     */
    public static int countWords(String text) {
        if (text == null) return 0;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return 0;

        int count = 0;
        boolean inWord = false;
        final int len = trimmed.length();
        for (int i = 0; i < len; ) {
            int cp = trimmed.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (isCjk(cp)) {
                count++;
                inWord = false;
            } else if (Character.isWhitespace(cp)) {
                inWord = false;
            } else {
                if (!inWord) {
                    count++;
                    inWord = true;
                }
            }
            i += charCount;
        }
        return count;
    }

    private static boolean isCjk(int cp) {
        if (Character.isIdeographic(cp)) return true;
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    public static String normalizeLanguage(String lang) {
        if (lang == null || lang.trim().isEmpty() || "auto".equalsIgnoreCase(lang.trim())) {
            return "Auto";
        }
        String trimmed = lang.trim();
        if ("en-US".equalsIgnoreCase(trimmed) || "en".equalsIgnoreCase(trimmed)) {
            return "English (en-US)";
        }
        if ("sv-SE".equalsIgnoreCase(trimmed) || "sv".equalsIgnoreCase(trimmed)) {
            return "Swedish (sv-SE)";
        }
        if ("zh-TW".equalsIgnoreCase(trimmed) || "zh".equalsIgnoreCase(trimmed)) {
            return "Chinese (zh-TW)";
        }
        if ("de".equalsIgnoreCase(trimmed) || "de-DE".equalsIgnoreCase(trimmed)) {
            return "German (de-DE)";
        }
        if ("es".equalsIgnoreCase(trimmed) || "es-ES".equalsIgnoreCase(trimmed)) {
            return "Spanish (es-ES)";
        }
        if ("fr".equalsIgnoreCase(trimmed) || "fr-FR".equalsIgnoreCase(trimmed)) {
            return "French (fr-FR)";
        }
        if ("it".equalsIgnoreCase(trimmed) || "it-IT".equalsIgnoreCase(trimmed)) {
            return "Italian (it-IT)";
        }
        if ("pt".equalsIgnoreCase(trimmed) || "pt-BR".equalsIgnoreCase(trimmed) || "pt-PT".equalsIgnoreCase(trimmed)) {
            return "Portuguese";
        }
        return trimmed;
    }

    public static String normalizeModelName(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            return "Built-in (Parakeet TDT 0.6B v3)";
        }
        String trimmed = modelName.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gguf")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".gguf".length());
        } else if (lower.endsWith(".bin")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".bin".length());
        }
        if ("builtin-model".equalsIgnoreCase(trimmed) || trimmed.contains("parakeet-tdt-0.6b-v3")) {
            return "Built-in (Parakeet TDT 0.6B v3)";
        }
        return trimmed;
    }

    public static String formatDuration(long ms) {
        long secs = ms / 1000L;
        if (secs < 60L) {
            return String.format(Locale.getDefault(), "%.1fs", ms / 1000.0f);
        }
        long mins = secs / 60L;
        long remSecs = secs % 60L;
        if (mins < 60L) {
            return String.format(Locale.getDefault(), "%dm %02ds", mins, remSecs);
        }
        long hours = mins / 60L;
        long remMins = mins % 60L;
        return String.format(Locale.getDefault(), "%dh %02dm", hours, remMins);
    }

    private static List<SessionRecord> loadRecords(Context context) {
        List<SessionRecord> list = new ArrayList<>();
        File file = new File(context.getFilesDir(), STATS_FILE);
        if (!file.isFile()) {
            return list;
        }
        try {
            String jsonStr = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                list.add(SessionRecord.fromJson(obj));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not load dictation stats", e);
        }
        return list;
    }

    private static void saveRecords(Context context, List<SessionRecord> records) {
        File dir = context.getFilesDir();
        File file = new File(dir, STATS_FILE);
        File tmp = new File(dir, STATS_FILE + ".tmp");
        try {
            JSONArray array = new JSONArray();
            for (SessionRecord rec : records) {
                array.put(rec.toJson());
            }
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (!tmp.renameTo(file)) {
                if (file.delete() && tmp.renameTo(file)) {
                    return;
                }
                tmp.delete();
                Log.w(TAG, "Failed to rename stats temp file to " + STATS_FILE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save dictation stats", e);
            tmp.delete();
        }
    }
}
