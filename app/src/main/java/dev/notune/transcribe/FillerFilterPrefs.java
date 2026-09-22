package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FillerFilterPrefs {
    private static final String TAG = "FillerFilterPrefs";
    public static final String FILE_NAME = "filler_filter.json";

    public static final List<String> ALL_EN_WORDS = Collections.unmodifiableList(Arrays.asList(
            "uh", "um", "er", "ah", "like", "you know", "I mean", "basically", "actually"
    ));

    public static final List<String> ALL_ZH_HANS_WORDS = Collections.unmodifiableList(Arrays.asList(
            "那个", "就是", "然后", "嗯", "呃", "其实", "怎么说"
    ));

    public static final List<String> ALL_ZH_HANT_WORDS = Collections.unmodifiableList(Arrays.asList(
            "那個", "就是", "然後", "嗯", "呃", "其實", "怎麼說"
    ));

    public static final List<String> DEFAULT_EN_WORDS = Collections.unmodifiableList(Arrays.asList(
            "uh", "um", "er", "ah"
    ));

    public static final List<String> DEFAULT_ZH_HANS_WORDS = Collections.unmodifiableList(Arrays.asList(
            "嗯", "呃"
    ));

    public static final List<String> DEFAULT_ZH_HANT_WORDS = Collections.unmodifiableList(Arrays.asList(
            "嗯", "呃"
    ));

    public boolean enabled = true;
    public boolean cleanPunctuation = true;
    public boolean presetEnEnabled = true;
    public boolean presetZhHansEnabled = true;
    public boolean presetZhHantEnabled = true;

    public List<String> enWords = new ArrayList<>(DEFAULT_EN_WORDS);
    public List<String> zhHansWords = new ArrayList<>(DEFAULT_ZH_HANS_WORDS);
    public List<String> zhHantWords = new ArrayList<>(DEFAULT_ZH_HANT_WORDS);
    public List<String> customWords = new ArrayList<>();

    public static native String testFilterNative(String text, String configJson);

    public static FillerFilterPrefs load(Context context) {
        FillerFilterPrefs prefs = new FillerFilterPrefs();
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) {
            return prefs;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = fis.read(bytes);
            if (read > 0) {
                String jsonStr = new String(bytes, 0, read, StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(jsonStr);
                prefs.enabled = obj.optBoolean("enabled", true);
                prefs.cleanPunctuation = obj.optBoolean("clean_punctuation", true);
                prefs.presetEnEnabled = obj.optBoolean("preset_en_enabled", true);
                prefs.presetZhHansEnabled = obj.optBoolean("preset_zh_hans_enabled", true);
                prefs.presetZhHantEnabled = obj.optBoolean("preset_zh_hant_enabled", true);

                if (obj.has("en_words")) {
                    prefs.enWords = jsonArrayToList(obj.getJSONArray("en_words"));
                }
                if (obj.has("zh_hans_words")) {
                    prefs.zhHansWords = jsonArrayToList(obj.getJSONArray("zh_hans_words"));
                }
                if (obj.has("zh_hant_words")) {
                    prefs.zhHantWords = jsonArrayToList(obj.getJSONArray("zh_hant_words"));
                }
                if (obj.has("custom_words")) {
                    prefs.customWords = jsonArrayToList(obj.getJSONArray("custom_words"));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load " + FILE_NAME + ", using defaults", e);
        }

        return prefs;
    }

    public void save(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try {
            String jsonStr = toJson();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save " + FILE_NAME, e);
        }
    }

    public String toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("enabled", enabled);
            obj.put("clean_punctuation", cleanPunctuation);
            obj.put("preset_en_enabled", presetEnEnabled);
            obj.put("preset_zh_hans_enabled", presetZhHansEnabled);
            obj.put("preset_zh_hant_enabled", presetZhHantEnabled);
            obj.put("en_words", listToJsonArray(enWords));
            obj.put("zh_hans_words", listToJsonArray(zhHansWords));
            obj.put("zh_hant_words", listToJsonArray(zhHantWords));
            obj.put("custom_words", listToJsonArray(customWords));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize JSON", e);
        }
        return obj.toString();
    }

    public int getActiveWordCount() {
        int count = 0;
        if (presetEnEnabled) count += enWords.size();
        if (presetZhHansEnabled) count += zhHansWords.size();
        if (presetZhHantEnabled) count += zhHantWords.size();
        count += customWords.size();
        return count;
    }

    private static List<String> jsonArrayToList(JSONArray arr) throws JSONException {
        List<String> list = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            list.add(arr.getString(i));
        }
        return list;
    }

    private static JSONArray listToJsonArray(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) {
            arr.put(s);
        }
        return arr;
    }
}
