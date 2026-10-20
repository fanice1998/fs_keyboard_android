use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

const CANDIDATE_LIMIT: usize = 40;

static LEXICON: Mutex<Option<Lexicon>> = Mutex::new(None);

#[derive(Clone, Debug)]
struct Entry {
    text: String,
    weight: i32,
}

#[derive(Clone, Debug)]
pub struct Lexicon {
    exact: HashMap<String, Vec<Entry>>,
    predicted: HashMap<String, Vec<Entry>>,
}

impl Lexicon {
    pub fn lookup(&self, code: &str, exact: bool) -> Vec<String> {
        let mut out = String::new();
        self.write_lookup(&mut out, code, exact);
        if out.is_empty() {
            Vec::new()
        } else {
            out.split('\n').map(str::to_string).collect()
        }
    }

    /// One newline-separated block. Callers append from stored rows and do not clone them.
    fn lookup_lines(&self, code: &str, exact: bool) -> String {
        let mut out = String::new();
        self.write_lookup(&mut out, code, exact);
        out
    }

    fn write_lookup(&self, out: &mut String, code: &str, exact: bool) {
        let direct = self.rows(code, exact);
        if !direct.is_empty() {
            write_entries(out, direct);
            return;
        }
        self.write_fallback(out, code, exact);
    }

    fn rows(&self, code: &str, exact: bool) -> &[Entry] {
        if exact {
            self.exact.get(code).map(Vec::as_slice).unwrap_or(&[])
        } else {
            let bare = without_tones(code);
            self.predicted.get(&bare).map(Vec::as_slice).unwrap_or(&[])
        }
    }

    fn write_fallback(&self, out: &mut String, code: &str, exact: bool) {
        let syllables: Vec<&str> = code.split(' ').filter(|part| !part.is_empty()).collect();
        if syllables.len() <= 1 {
            if exact {
                let bare = without_tones(code);
                if bare != code {
                    write_entries(out, self.rows(&bare, false));
                }
            }
            return;
        }
        let mut ranked: Vec<(i32, &str)> = Vec::new();
        let mut index_of: HashMap<&str, usize> = HashMap::new();
        for length in (1..syllables.len()).rev() {
            let prefix = syllables[..length].join(" ");
            let rows = self.rows(&prefix, exact);
            if rows.is_empty() {
                continue;
            }
            consider(&mut ranked, &mut index_of, rows);
            break;
        }
        if let Some(last) = syllables.last() {
            consider(&mut ranked, &mut index_of, self.rows(last, exact));
        }
        ranked.sort_by(|left, right| right.0.cmp(&left.0));
        for (index, (_, text)) in ranked.iter().take(CANDIDATE_LIMIT).enumerate() {
            if index > 0 {
                out.push('\n');
            }
            out.push_str(text);
        }
    }
}

fn write_entries(out: &mut String, rows: &[Entry]) {
    for (index, entry) in rows.iter().take(CANDIDATE_LIMIT).enumerate() {
        if index > 0 {
            out.push('\n');
        }
        out.push_str(&entry.text);
    }
}

fn consider<'a>(
    ranked: &mut Vec<(i32, &'a str)>,
    index_of: &mut HashMap<&'a str, usize>,
    rows: &'a [Entry],
) {
    for entry in rows {
        if let Some(slot) = index_of.get(entry.text.as_str()).copied() {
            if entry.weight > ranked[slot].0 {
                ranked[slot].0 = entry.weight;
            }
        } else {
            index_of.insert(entry.text.as_str(), ranked.len());
            ranked.push((entry.weight, entry.text.as_str()));
        }
    }
}

fn without_tones(code: &str) -> String {
    code.chars()
        .filter(|ch| !matches!(ch, 'ˊ' | 'ˇ' | 'ˋ' | '˙'))
        .collect()
}

fn finish(mut rows: Vec<(String, i32)>) -> Vec<Entry> {
    rows.sort_by(|left, right| right.1.cmp(&left.1));
    let mut seen = HashSet::new();
    rows.into_iter()
        .filter_map(|(text, weight)| {
            if seen.insert(text.clone()) {
                Some(Entry { text, weight })
            } else {
                None
            }
        })
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
    fn a_missing_phrase_falls_back_to_the_longest_prefix_and_the_last_syllable() {
        let lexicon = parse_dict(&project_dict());
        let linked = lexicon.lookup("ㄍㄨㄥ ㄎㄜˋ", true);
        assert!(linked.iter().any(|text| text == "公"));
        assert!(linked.iter().any(|text| text == "課"));
        assert!(linked.iter().all(|text| text != "家"));
        assert_eq!(
            lexicon.lookup("ㄊㄞˊ ㄨㄢ", true).first().map(String::as_str),
            Some("臺灣")
        );

        let yaml = "\
...
公\tㄍㄨㄥ\t80
課\tㄎㄜˋ\t60
台灣\tㄊㄞˊ ㄨㄢ\t500
灣\tㄨㄢ\t40
";
        let sample = parse_dict(yaml);
        assert_eq!(sample.lookup("ㄊㄞˊ ㄨㄢ", false).first().map(String::as_str), Some("台灣"));
        let split = sample.lookup("ㄍㄨㄥ ㄎㄜˋ", true);
        assert_eq!(split, vec!["公".to_string(), "課".to_string()]);
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
