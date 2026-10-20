use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

const CANDIDATE_LIMIT: usize = 40;

static LEXICON: Mutex<Option<Lexicon>> = Mutex::new(None);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Lexicon {
    exact: HashMap<String, Vec<String>>,
    predicted: HashMap<String, Vec<String>>,
}

impl Lexicon {
    pub fn lookup(&self, code: &str, exact: bool) -> Vec<String> {
        let table = if exact { &self.exact } else { &self.predicted };
        table.get(code).cloned().unwrap_or_default()
    }

    fn lookup_lines(&self, code: &str, exact: bool) -> String {
        self.lookup(code, exact).join("\n")
    }
}

fn without_tones(code: &str) -> String {
    code.chars()
        .filter(|ch| !matches!(ch, 'ˊ' | 'ˇ' | 'ˋ' | '˙'))
        .collect()
}

fn finish(mut rows: Vec<(String, i32)>) -> Vec<String> {
    rows.sort_by(|left, right| right.1.cmp(&left.1));
    let mut seen = HashSet::new();
    rows.into_iter()
        .filter_map(|(text, _weight)| seen.insert(text.clone()).then_some(text))
        .take(CANDIDATE_LIMIT)
        .collect()
}

/// Reads a Rime dictionary. The header ends at a line whose trim is `...`.
/// Later rows are `text<code><weight`, and `#` lines are skipped.
pub fn parse_dict(yaml: &str) -> Lexicon {
    let mut exact_raw: HashMap<String, Vec<(String, i32)>> = HashMap::new();
    let mut predicted_raw: HashMap<String, Vec<(String, i32)>> = HashMap::new();
    let mut body = false;
    for line in yaml.lines() {
        if !body {
            if line.trim() == "..." {
                body = true;
            }
            continue;
        }
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let mut parts = line.split('\t');
        let (Some(text), Some(code), Some(weight)) = (parts.next(), parts.next(), parts.next()) else {
            continue;
        };
        if text.is_empty() || code.is_empty() {
            continue;
        }
        let Ok(weight) = weight.trim().parse::<i32>() else {
            continue;
        };
        let row = (text.to_string(), weight);
        exact_raw.entry(code.to_string()).or_default().push(row.clone());
        predicted_raw
            .entry(without_tones(code))
            .or_default()
            .push(row);
    }

    Lexicon {
        exact: exact_raw.into_iter().map(|(code, rows)| (code, finish(rows))).collect(),
        predicted: predicted_raw
            .into_iter()
            .map(|(code, rows)| (code, finish(rows)))
            .collect(),
    }
}

mod android {
    use super::LEXICON;
    use super::parse_dict;
    use jni::JNIEnv;
    use jni::objects::{JClass, JString};
    use jni::sys::{jboolean, jint, jstring};

    #[no_mangle]
    pub extern "system" fn Java_com_fskeyboard_ime_engine_RustLexiconNative_nativeLoad<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        yaml: JString<'local>,
    ) -> jint {
        let text: String = match env.get_string(&yaml) {
            Ok(value) => value.into(),
            Err(_) => return -1,
        };
        let lexicon = parse_dict(&text);
        match LEXICON.lock() {
            Ok(mut guard) => {
                *guard = Some(lexicon);
                0
            }
            Err(poisoned) => {
                *poisoned.into_inner() = Some(lexicon);
                0
            }
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_fskeyboard_ime_engine_RustLexiconNative_nativeLookup<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        code: JString<'local>,
        exact: jboolean,
    ) -> jstring {
        let key: String = match env.get_string(&code) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let exact = exact != 0;
        let found = match LEXICON.lock() {
            Ok(guard) => guard
                .as_ref()
                .map(|lexicon| lexicon.lookup_lines(&key, exact)),
            Err(poisoned) => poisoned
                .into_inner()
                .as_ref()
                .map(|lexicon| lexicon.lookup_lines(&key, exact)),
        }
        .unwrap_or_default();
        env.new_string(found)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }
}

#[cfg(test)]
mod tests {
    use super::parse_dict;
    use std::path::Path;

    fn project_dict() -> String {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../app/src/main/assets/rime/shared/fs_zhuyin.dict.yaml");
        std::fs::read_to_string(path).expect("fs_zhuyin.dict.yaml")
    }

    #[test]
    fn gong_does_not_return_jia_and_a_phrase_matches_its_code() {
        let lexicon = parse_dict(&project_dict());
        let gong = lexicon.lookup("ㄍㄨㄥ", true);
        assert_eq!(gong.first().map(String::as_str), Some("公"));
        assert!(gong.iter().any(|text| text == "工"));
        assert!(gong.iter().all(|text| text != "家"));
        assert!(gong.iter().all(|text| text != "貢"));
        assert!(lexicon.lookup("ㄐㄧㄚ", true).iter().any(|text| text == "家"));
        assert_eq!(
            lexicon.lookup("ㄋㄧˇ ㄏㄠˇ", true).first().map(String::as_str),
            Some("你好")
        );
    }

    #[test]
    fn an_untoned_reading_predicts_every_tone_and_a_tone_narrows_it() {
        let lexicon = parse_dict(&project_dict());
        let predicted = lexicon.lookup("ㄍㄨㄥ", false);
        assert_eq!(predicted.first().map(String::as_str), Some("公"));
        assert!(predicted.iter().any(|text| text == "貢"));
        assert!(predicted.iter().all(|text| text != "家"));
        let falling = lexicon.lookup("ㄍㄨㄥˋ", true);
        assert!(falling.iter().any(|text| text == "貢"));
        assert!(falling.iter().all(|text| text != "公"));
        assert_eq!(
            lexicon.lookup("ㄋㄧ ㄏㄠ", false).first().map(String::as_str),
            Some("你好")
        );
    }

    #[test]
    fn header_and_comments_are_skipped_and_weight_orders_the_row() {
        let yaml = "\
---
name: sample
...
# comment
甲\tㄚ\t1
乙\tㄚ\t9
甲\tㄚ\t3
丙\tㄅㄚˋ ㄅㄚ\t4
";
        let lexicon = parse_dict(yaml);
        assert_eq!(lexicon.lookup("ㄚ", true), vec!["乙".to_string(), "甲".to_string()]);
        assert_eq!(
            lexicon.lookup("ㄅㄚˋ ㄅㄚ", true),
            vec!["丙".to_string()]
        );
        assert!(lexicon.lookup("name: sample", true).is_empty());
        assert_eq!(
            lexicon.lookup("ㄅㄚ ㄅㄚ", false),
            vec!["丙".to_string()]
        );
    }

    #[test]
    fn lookup_keeps_forty_highest_weights() {
        let mut yaml = String::from("...\n");
        for index in 0..45 {
            yaml.push_str(&format!("字{index}\tㄍㄨㄥ\t{index}\n"));
        }
        let found = parse_dict(&yaml).lookup("ㄍㄨㄥ", true);
        assert_eq!(found.len(), 40);
        assert_eq!(found[0], "字44");
        assert_eq!(found[39], "字5");
    }
}
