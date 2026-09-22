//! Optional, offline Chinese output normalization.
//!
//! Recognition language and output script are deliberately separate: a model
//! may accept `zh-TW` yet still emit Simplified Chinese.  OpenCC conversion is
//! applied after recognition so every output path receives the same text.

use ferrous_opencc::{config::BuiltinConfig, OpenCC};

/// Persisted values written by `ModelsActivity`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ChineseOutput {
    AsTranscribed,
    Simplified,
    #[default]
    TraditionalTaiwan,
}

impl ChineseOutput {
    pub fn from_setting(value: Option<&str>) -> Self {
        match value {
            Some("none") | Some("as_transcribed") => Self::AsTranscribed,
            Some("simplified") => Self::Simplified,
            Some("traditional_tw") => Self::TraditionalTaiwan,
            None => Self::TraditionalTaiwan,
            _ => Self::TraditionalTaiwan,
        }
    }
}

/// An initialized converter for the selected output target.
pub struct ChineseConverter(Option<OpenCC>);

impl ChineseConverter {
    pub fn new(output: ChineseOutput) -> Result<Self, String> {
        let config = match output {
            ChineseOutput::AsTranscribed => return Ok(Self(None)),
            ChineseOutput::Simplified => BuiltinConfig::T2s,
            ChineseOutput::TraditionalTaiwan => BuiltinConfig::S2tw,
        };
        OpenCC::from_config(config)
            .map(|converter| Self(Some(converter)))
            .map_err(|e| format!("failed to initialize Chinese output conversion: {e}"))
    }

    pub fn convert(&self, text: &str) -> String {
        match &self.0 {
            Some(converter) => converter.convert(text),
            None => text.to_owned(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_setting_is_traditional_taiwan() {
        assert_eq!(
            ChineseOutput::from_setting(None),
            ChineseOutput::TraditionalTaiwan
        );
        assert_eq!(
            ChineseOutput::default(),
            ChineseOutput::TraditionalTaiwan
        );
    }

    #[test]
    fn explicit_none_disables_conversion() {
        assert_eq!(
            ChineseOutput::from_setting(Some("none")),
            ChineseOutput::AsTranscribed
        );
        assert_eq!(
            ChineseOutput::from_setting(Some("as_transcribed")),
            ChineseOutput::AsTranscribed
        );
    }

    #[test]
    fn leaves_text_unchanged_when_disabled() {
        let converter = ChineseConverter::new(ChineseOutput::AsTranscribed).unwrap();
        assert_eq!(
            converter.convert("汉字與 English 123 🙂"),
            "汉字與 English 123 🙂"
        );
    }

    #[test]
    fn converts_to_simplified_chinese() {
        let converter = ChineseConverter::new(ChineseOutput::Simplified).unwrap();
        assert_eq!(converter.convert("漢字轉換與資料庫"), "汉字转换与资料库");
    }

    #[test]
    fn converts_to_taiwan_traditional_chinese() {
        let converter = ChineseConverter::new(ChineseOutput::TraditionalTaiwan).unwrap();
        assert_eq!(converter.convert("汉字转换与数据库"), "漢字轉換與數據庫");
    }
}
