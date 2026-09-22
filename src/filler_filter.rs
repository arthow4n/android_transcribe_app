//! Configurable filler word filter and punctuation/whitespace cleanup.
//!
//! Removes disfluent filler words across English, Simplified Chinese, Traditional
//! Chinese, and user-defined custom words, while cleaning up orphaned punctuation
//! (commas, periods), collapsing whitespace, and restoring sentence capitalization.

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

pub const FILLER_FILTER_FILE: &str = "filler_filter.json";

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct FillerFilterConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_true")]
    pub clean_punctuation: bool,
    #[serde(default = "default_true")]
    pub preset_en_enabled: bool,
    #[serde(default)]
    pub preset_zh_hans_enabled: bool,
    #[serde(default)]
    pub preset_zh_hant_enabled: bool,
    #[serde(default)]
    pub en_words: Vec<String>,
    #[serde(default)]
    pub zh_hans_words: Vec<String>,
    #[serde(default)]
    pub zh_hant_words: Vec<String>,
    #[serde(default)]
    pub custom_words: Vec<String>,
}

fn default_true() -> bool {
    true
}

pub fn default_en_words() -> Vec<String> {
    vec![
        "uh".into(),
        "um".into(),
        "er".into(),
        "ah".into(),
        "like".into(),
        "you know".into(),
        "I mean".into(),
        "basically".into(),
        "actually".into(),
    ]
}

pub fn default_zh_hans_words() -> Vec<String> {
    vec![
        "那个".into(),
        "就是".into(),
        "然后".into(),
        "嗯".into(),
        "呃".into(),
        "其实".into(),
        "怎么说".into(),
    ]
}

pub fn default_zh_hant_words() -> Vec<String> {
    vec![
        "那個".into(),
        "就是".into(),
        "然後".into(),
        "嗯".into(),
        "呃".into(),
        "其實".into(),
        "怎麼說".into(),
    ]
}

impl Default for FillerFilterConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            clean_punctuation: true,
            preset_en_enabled: true,
            preset_zh_hans_enabled: false,
            preset_zh_hant_enabled: false,
            en_words: default_en_words(),
            zh_hans_words: default_zh_hans_words(),
            zh_hant_words: default_zh_hant_words(),
            custom_words: Vec::new(),
        }
    }
}

pub struct FillerFilter {
    config: FillerFilterConfig,
    regex: Option<regex::Regex>,
    config_path: Option<PathBuf>,
    last_mtime: Option<SystemTime>,
}

impl FillerFilter {
    pub fn new(config: FillerFilterConfig) -> Self {
        let regex = Self::build_regex(&config);
        Self {
            config,
            regex,
            config_path: None,
            last_mtime: None,
        }
    }

    pub fn load_from_dir(dir: &Path) -> Self {
        let path = dir.join(FILLER_FILTER_FILE);
        let mut filter = if let Ok(contents) = fs::read_to_string(&path) {
            match serde_json::from_str::<FillerFilterConfig>(&contents) {
                Ok(cfg) => Self::new(cfg),
                Err(e) => {
                    log::warn!("failed to parse filler_filter.json: {e}; using defaults");
                    Self::new(FillerFilterConfig::default())
                }
            }
        } else {
            Self::new(FillerFilterConfig::default())
        };

        filter.last_mtime = fs::metadata(&path).and_then(|m| m.modified()).ok();
        filter.config_path = Some(path);
        filter
    }

    /// Reloads config from disk if modified.
    pub fn reload_if_changed(&mut self) {
        let Some(path) = &self.config_path else {
            return;
        };
        let Ok(mtime) = fs::metadata(path).and_then(|m| m.modified()) else {
            return;
        };
        if self.last_mtime.map_or(true, |last| mtime > last) {
            if let Ok(contents) = fs::read_to_string(path) {
                if let Ok(cfg) = serde_json::from_str::<FillerFilterConfig>(&contents) {
                    self.regex = Self::build_regex(&cfg);
                    self.config = cfg;
                    self.last_mtime = Some(mtime);
                    log::info!("reloaded filler filter config");
                }
            }
        }
    }

    /// Compiles all active filler words into a single regex.
    fn build_regex(config: &FillerFilterConfig) -> Option<regex::Regex> {
        if !config.enabled {
            return None;
        }

        let mut all_words: Vec<String> = Vec::new();

        if config.preset_en_enabled {
            all_words.extend(config.en_words.iter().cloned());
        }
        if config.preset_zh_hans_enabled {
            all_words.extend(config.zh_hans_words.iter().cloned());
        }
        if config.preset_zh_hant_enabled {
            all_words.extend(config.zh_hant_words.iter().cloned());
        }
        all_words.extend(config.custom_words.iter().cloned());

        let mut patterns = Vec::new();
        for raw in all_words {
            let trimmed = raw.trim();
            if trimmed.is_empty() {
                continue;
            }

            if trimmed.chars().any(is_cjk_char) {
                // CJK scripts do not use ASCII word boundaries
                patterns.push(regex::escape(trimmed));
            } else {
                // Latin / alphanumeric scripts: match whole words with word boundaries
                let words: Vec<String> = trimmed
                    .split_whitespace()
                    .map(regex::escape)
                    .collect();
                if !words.is_empty() {
                    let phrase = words.join(r"\s+");
                    patterns.push(format!(r"\b{}\b", phrase));
                }
            }
        }

        if patterns.is_empty() {
            return None;
        }

        // Sort patterns by length descending so longer phrases match first
        patterns.sort_by(|a, b| b.len().cmp(&a.len()));

        let combined = format!("(?i)(?:{})", patterns.join("|"));
        match regex::Regex::new(&combined) {
            Ok(re) => Some(re),
            Err(e) => {
                log::error!("failed to compile filler filter regex: {e}");
                None
            }
        }
    }

    /// Filters filler words and normalizes punctuation/whitespace.
    pub fn filter(&self, text: &str) -> String {
        if !self.config.enabled || text.is_empty() {
            return text.to_string();
        }

        let mut out = if let Some(re) = &self.regex {
            re.replace_all(text, " ").into_owned()
        } else {
            text.to_string()
        };

        if self.config.clean_punctuation {
            out = clean_punctuation_and_whitespace(&out);
        } else {
            out = collapse_whitespace(&out);
        }

        out
    }
}

fn is_cjk_char(c: char) -> bool {
    matches!(c,
        '\u{4E00}'..='\u{9FFF}' |   // CJK Unified Ideographs
        '\u{3400}'..='\u{4DBF}' |   // CJK Extension A
        '\u{F900}'..='\u{FAFF}' |   // CJK Compatibility
        '\u{3040}'..='\u{309F}' |   // Hiragana
        '\u{30A0}'..='\u{30FF}'     // Katakana
    )
}

/// Collapses consecutive whitespace into a single space and trims ends.
fn collapse_whitespace(text: &str) -> String {
    let mut result = String::with_capacity(text.len());
    let mut in_space = false;
    for c in text.trim().chars() {
        if c.is_whitespace() {
            if !in_space {
                result.push(' ');
                in_space = true;
            }
        } else {
            result.push(c);
            in_space = false;
        }
    }
    result
}

/// Normalizes spacing around punctuation, removes duplicate commas/periods,
/// strips orphaned leading/trailing punctuation, and restores sentence capitalization.
pub fn clean_punctuation_and_whitespace(text: &str) -> String {
    if text.is_empty() {
        return String::new();
    }

    // 1. Remove whitespace immediately preceding punctuation marks
    // e.g. "hello , world" -> "hello, world"
    let mut s = String::with_capacity(text.len());
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c.is_whitespace() {
            // Peek ahead past all whitespace
            let mut j = i;
            while j < chars.len() && chars[j].is_whitespace() {
                j += 1;
            }
            if j < chars.len() && is_trailing_punctuation(chars[j]) {
                // Skip the whitespace entirely before punctuation
                i = j;
                continue;
            }
            s.push(' ');
            i = j;
            continue;
        }
        s.push(c);
        i += 1;
    }

    // 2. Collapse duplicate/mixed punctuation
    // e.g. ",," -> ",", ", ." -> ".", ".," -> "."
    let mut collapsed = String::with_capacity(s.len());
    let chars: Vec<char> = s.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if is_comma_like(c) {
            // Look ahead for subsequent commas or sentence terminators
            let mut j = i + 1;
            let mut found_terminator = None;
            while j < chars.len() {
                let next = chars[j];
                if next.is_whitespace() {
                    j += 1;
                } else if is_comma_like(next) {
                    j += 1;
                } else if is_sentence_terminator(next) {
                    found_terminator = Some(next);
                    j += 1;
                    break;
                } else {
                    break;
                }
            }
            if let Some(term) = found_terminator {
                collapsed.push(term);
            } else {
                collapsed.push(c);
            }
            i = j;
            continue;
        } else if is_sentence_terminator(c) {
            // Look ahead for subsequent duplicate terminators or commas
            let mut j = i + 1;
            while j < chars.len() {
                let next = chars[j];
                if next.is_whitespace() {
                    // Don't skip whitespace after a terminator unless followed by another punctuation
                    let mut k = j;
                    while k < chars.len() && chars[k].is_whitespace() {
                        k += 1;
                    }
                    if k < chars.len() && (is_comma_like(chars[k]) || (chars[k] == c && c != '.')) {
                        j = k + 1;
                    } else {
                        break;
                    }
                } else if is_comma_like(next) {
                    j += 1;
                } else if next == c && c != '.' {
                    // Collapse duplicate '!' or '?' to single
                    j += 1;
                } else {
                    break;
                }
            }
            collapsed.push(c);
            i = j;
            continue;
        }
        collapsed.push(c);
        i += 1;
    }

    // 3. Trim leading punctuation that shouldn't begin a sentence (e.g. leading commas/colons)
    let mut trimmed_start = collapsed.as_str();
    while let Some(first) = trimmed_start.chars().next() {
        if first.is_whitespace() || is_comma_like(first) || first == ':' || first == ';' {
            trimmed_start = &trimmed_start[first.len_utf8()..];
        } else {
            break;
        }
    }

    // 4. Trim trailing orphaned commas or pause punctuation
    let mut trimmed_end = trimmed_start;
    while let Some(last) = trimmed_end.chars().last() {
        if last.is_whitespace() || is_comma_like(last) || last == ':' || last == ';' {
            let last_len = last.len_utf8();
            trimmed_end = &trimmed_end[..trimmed_end.len() - last_len];
        } else {
            break;
        }
    }

    // 5. Collapse internal whitespace
    let normalized_space = collapse_whitespace(trimmed_end);

    // 6. Sentence capitalization
    recapitalize_sentences(&normalized_space)
}

fn is_trailing_punctuation(c: char) -> bool {
    matches!(c, ',' | '.' | '!' | '?' | ';' | ':' | '，' | '。' | '！' | '？' | '、' | '；' | '：')
}

fn is_comma_like(c: char) -> bool {
    matches!(c, ',' | '，' | '、' | ';' | '；')
}

fn is_sentence_terminator(c: char) -> bool {
    matches!(c, '.' | '!' | '?' | '。' | '！' | '？')
}

/// Ensures the first character and characters after sentence terminators (. ! ?)
/// are capitalized if they are ASCII lowercase letters.
fn recapitalize_sentences(text: &str) -> String {
    if text.is_empty() {
        return String::new();
    }

    let mut result = String::with_capacity(text.len());
    let chars: Vec<char> = text.chars().collect();
    let mut capitalize_next = true;

    for i in 0..chars.len() {
        let c = chars[i];
        if capitalize_next && c.is_ascii_lowercase() {
            result.push(c.to_ascii_uppercase());
            capitalize_next = false;
        } else {
            result.push(c);
            if is_sentence_terminator(c) {
                capitalize_next = true;
            } else if !c.is_whitespace() && !matches!(c, '"' | '\'' | '“' | '”' | '‘' | '’' | '(' | '[') {
                capitalize_next = false;
            }
        }
    }

    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_filler_filter_basic() {
        let config = FillerFilterConfig {
            enabled: true,
            clean_punctuation: true,
            preset_en_enabled: true,
            preset_zh_hans_enabled: true,
            preset_zh_hant_enabled: true,
            en_words: default_en_words(),
            zh_hans_words: default_zh_hans_words(),
            zh_hant_words: default_zh_hant_words(),
            custom_words: vec!["totally".into()],
        };
        let filter = FillerFilter::new(config);

        // English with leading filler and commas
        assert_eq!(
            filter.filter("Uh, how are you today?"),
            "How are you today?"
        );

        // English filler in the middle
        assert_eq!(
            filter.filter("I think, um, that this is, like, totally cool."),
            "I think, that this is cool."
        );

        // English filler at the end with period
        assert_eq!(
            filter.filter("Yes, uh."),
            "Yes."
        );

        // Multi-word phrase
        assert_eq!(
            filter.filter("It was, you know, quite interesting."),
            "It was, quite interesting."
        );

        // Word boundary safety (should not match inside "much" or "unhappy")
        assert_eq!(
            filter.filter("Much of the work was unhappy."),
            "Much of the work was unhappy."
        );

        // Simplified Chinese filler
        assert_eq!(
            filter.filter("那个，我们今天去吃饭吧。"),
            "我们今天去吃饭吧。"
        );

        // Traditional Chinese filler
        assert_eq!(
            filter.filter("那個、我想想看。"),
            "我想想看。"
        );
    }
}

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FillerFilterPrefs_testFilterNative(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    config_json: JString,
) -> jstring {
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let json_str: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let cfg: FillerFilterConfig = serde_json::from_str(&json_str).unwrap_or_default();
    let filter = FillerFilter::new(cfg);
    let result = filter.filter(&text_str);
    match env.new_string(result) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
