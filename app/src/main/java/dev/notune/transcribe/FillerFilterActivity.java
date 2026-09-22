package dev.notune.transcribe;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

public class FillerFilterActivity extends AppCompatActivity {
    private FillerFilterPrefs prefs;

    private MaterialSwitch switchFilterMaster;
    private MaterialSwitch switchCleanPunctuation;

    private EditText editCustomWord;
    private ChipGroup chipGroupCustom;
    private TextView textCustomEmpty;

    private EditText editTestInput;
    private TextView textTestOutput;

    private boolean updatingUi = false;

    interface GroupCallback {
        void onGroupChanged(boolean enabled, List<String> activeWords);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filler_filter);

        prefs = FillerFilterPrefs.load(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        switchFilterMaster = findViewById(R.id.switch_filter_master);
        switchCleanPunctuation = findViewById(R.id.switch_clean_punctuation);

        switchFilterMaster.setChecked(prefs.enabled);
        switchFilterMaster.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingUi) return;
            prefs.enabled = isChecked;
            saveAndUpdate();
        });

        switchCleanPunctuation.setChecked(prefs.cleanPunctuation);
        switchCleanPunctuation.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingUi) return;
            prefs.cleanPunctuation = isChecked;
            saveAndUpdate();
        });

        // 1. English Presets
        setupPresetGroup(
                findViewById(R.id.header_group_en),
                findViewById(R.id.cb_parent_en),
                findViewById(R.id.text_count_en),
                findViewById(R.id.icon_expand_en),
                findViewById(R.id.container_children_en),
                prefs.presetEnEnabled,
                prefs.enWords,
                FillerFilterPrefs.ALL_EN_WORDS,
                (enabled, words) -> {
                    prefs.presetEnEnabled = enabled;
                    prefs.enWords = words;
                    saveAndUpdate();
                }
        );

        // 2. Simplified Chinese Presets
        setupPresetGroup(
                findViewById(R.id.header_group_zh_hans),
                findViewById(R.id.cb_parent_zh_hans),
                findViewById(R.id.text_count_zh_hans),
                findViewById(R.id.icon_expand_zh_hans),
                findViewById(R.id.container_children_zh_hans),
                prefs.presetZhHansEnabled,
                prefs.zhHansWords,
                FillerFilterPrefs.ALL_ZH_HANS_WORDS,
                (enabled, words) -> {
                    prefs.presetZhHansEnabled = enabled;
                    prefs.zhHansWords = words;
                    saveAndUpdate();
                }
        );

        // 3. Traditional Chinese Presets
        setupPresetGroup(
                findViewById(R.id.header_group_zh_hant),
                findViewById(R.id.cb_parent_zh_hant),
                findViewById(R.id.text_count_zh_hant),
                findViewById(R.id.icon_expand_zh_hant),
                findViewById(R.id.container_children_zh_hant),
                prefs.presetZhHantEnabled,
                prefs.zhHantWords,
                FillerFilterPrefs.ALL_ZH_HANT_WORDS,
                (enabled, words) -> {
                    prefs.presetZhHantEnabled = enabled;
                    prefs.zhHantWords = words;
                    saveAndUpdate();
                }
        );

        // Custom Words
        editCustomWord = findViewById(R.id.edit_custom_word);
        chipGroupCustom = findViewById(R.id.chip_group_custom);
        textCustomEmpty = findViewById(R.id.text_custom_empty);

        findViewById(R.id.btn_add_custom_word).setOnClickListener(v -> addCustomWord());
        editCustomWord.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCustomWord();
                return true;
            }
            return false;
        });

        refreshCustomChips();

        // Live Preview Playground
        editTestInput = findViewById(R.id.edit_test_input);
        textTestOutput = findViewById(R.id.text_test_output);

        editTestInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int count, int after) {
                updateLivePreview();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        updateLivePreview();
    }

    private void setupPresetGroup(
            View headerGroup,
            MaterialCheckBox cbParent,
            TextView textCount,
            ImageView iconExpand,
            LinearLayout containerChildren,
            boolean initiallyEnabled,
            List<String> activeWords,
            List<String> allWords,
            GroupCallback callback
    ) {
        final List<String> currentActive = new ArrayList<>(activeWords);
        final List<MaterialCheckBox> childBoxes = new ArrayList<>();

        // Populate child checkboxes
        containerChildren.removeAllViews();
        for (String word : allWords) {
            MaterialCheckBox cb = new MaterialCheckBox(this);
            cb.setText(word);
            cb.setChecked(currentActive.contains(word));
            cb.setPadding(0, 8, 0, 8);

            cb.setOnCheckedChangeListener((btn, isChecked) -> {
                if (updatingUi) return;
                if (isChecked) {
                    if (!currentActive.contains(word)) currentActive.add(word);
                } else {
                    currentActive.remove(word);
                }
                updateParentState(cbParent, textCount, currentActive, allWords);
                callback.onGroupChanged(cbParent.isChecked(), new ArrayList<>(currentActive));
            });

            childBoxes.add(cb);
            containerChildren.addView(cb);
        }

        // Set initial parent checkbox state
        cbParent.setChecked(initiallyEnabled && !currentActive.isEmpty());
        updateCountText(textCount, currentActive.size(), allWords.size());

        // Parent checkbox toggle
        cbParent.setOnClickListener(v -> {
            boolean checked = cbParent.isChecked();
            updatingUi = true;
            currentActive.clear();
            if (checked) {
                currentActive.addAll(allWords);
            }
            for (MaterialCheckBox cb : childBoxes) {
                cb.setChecked(checked);
            }
            updatingUi = false;
            updateCountText(textCount, currentActive.size(), allWords.size());
            callback.onGroupChanged(checked, new ArrayList<>(currentActive));
        });

        // Expand/collapse logic on header row click (excluding the checkbox itself)
        headerGroup.setOnClickListener(v -> {
            boolean isExpanded = containerChildren.getVisibility() == View.VISIBLE;
            containerChildren.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
            iconExpand.setImageResource(isExpanded ? R.drawable.ic_expand_more : R.drawable.ic_expand_less);
        });
    }

    private void updateParentState(MaterialCheckBox cbParent, TextView textCount, List<String> currentActive, List<String> allWords) {
        boolean allChecked = currentActive.size() == allWords.size();
        boolean noneChecked = currentActive.isEmpty();

        updatingUi = true;
        if (allChecked) {
            cbParent.setChecked(true);
        } else if (noneChecked) {
            cbParent.setChecked(false);
        } else {
            // Partial selection: check the box if at least one is selected
            cbParent.setChecked(true);
        }
        updatingUi = false;

        updateCountText(textCount, currentActive.size(), allWords.size());
    }

    private void updateCountText(TextView tv, int active, int total) {
        tv.setText(getString(R.string.filler_filter_selected_count, active, total));
    }

    private void addCustomWord() {
        String raw = editCustomWord.getText().toString().trim();
        if (raw.isEmpty()) return;

        // Support adding comma-separated words at once
        String[] parts = raw.split("[,\\n]+");
        boolean added = false;
        for (String p : parts) {
            String word = p.trim();
            if (!word.isEmpty() && !prefs.customWords.contains(word)) {
                prefs.customWords.add(word);
                added = true;
            }
        }

        if (added) {
            editCustomWord.setText("");
            saveAndUpdate();
            refreshCustomChips();
        }
    }

    private void refreshCustomChips() {
        chipGroupCustom.removeAllViews();
        if (prefs.customWords.isEmpty()) {
            textCustomEmpty.setVisibility(View.VISIBLE);
            chipGroupCustom.setVisibility(View.GONE);
            return;
        }

        textCustomEmpty.setVisibility(View.GONE);
        chipGroupCustom.setVisibility(View.VISIBLE);

        for (String word : prefs.customWords) {
            Chip chip = new Chip(this);
            chip.setText(word);
            chip.setCloseIconVisible(true);
            chip.setOnCloseIconClickListener(v -> {
                prefs.customWords.remove(word);
                saveAndUpdate();
                refreshCustomChips();
            });
            chipGroupCustom.addView(chip);
        }
    }

    private void saveAndUpdate() {
        prefs.save(this);
        updateLivePreview();
    }

    private void updateLivePreview() {
        if (editTestInput == null || textTestOutput == null) return;
        String input = editTestInput.getText().toString();
        String json = prefs.toJson();

        try {
            String filtered = FillerFilterPrefs.testFilterNative(input, json);
            textTestOutput.setText(filtered != null ? filtered : input);
        } catch (UnsatisfiedLinkError e) {
            textTestOutput.setText(input);
        }
    }
}
