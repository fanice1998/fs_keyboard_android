use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

/// Longest phrase, in syllables, that a candidate list looks for.
const MAX_SPAN: usize = 8;
/// Tones are packed three bits per syllable, so longer readings are skipped.
const MAX_SYLLABLES: usize = 10;
const CANDIDATE_LIMIT: usize = 60;
/// Phrases are capped per length so single characters still reach the list.
const PHRASE_LIMIT: usize = 20;
/// Score for a row that only the supplementary dictionary has. Below every phrase-table row.
const SUPPLEMENT_SCORE: f32 = -9.0;

static LEXICON: Mutex<Option<Lexicon>> = Mutex::new(None);

#[derive(Clone, Debug)]
struct Entry {
    text: Box<str>,
    /// Tone of syllable i in bits 3i..3i+3: 1 level, 2 ˊ, 3 ˇ, 4 ˋ, 5 ˙.
    tones: u32,
    score: f32,
}

/// Rows keyed by the toneless reading, syllables joined by a space, best score first.
#[derive(Clone, Debug, Default)]
pub struct Lexicon {
    rows: HashMap<Box<str>, Vec<Entry>>,
}

/// One candidate for the front of a composition. [span] is how many syllables it uses.
#[derive(Clone, Debug, PartialEq)]
pub struct Hit<'a> {
    pub span: usize,
    pub text: &'a str,
}

impl Lexicon {
    /// Candidates for the front of [code], longest phrase first, then by score.
    ///
    /// [code] is syllables joined by a space. A syllable ends with its tone mark,
    /// no mark is the level tone, and `*` means the tone is not chosen yet.
    pub fn candidates(&self, code: &str) -> Vec<Hit<'_>> {
        let query: Vec<(String, Option<u8>)> = code
            .split(' ')
            .filter(|part| !part.is_empty())
            .map(parse_query)
            .collect();
        let mut out = Vec::new();
        let mut seen = HashSet::new();
        for span in (1..=query.len().min(MAX_SPAN)).rev() {
            let key = query[..span]
                .iter()
                .map(|(body, _)| body.as_str())
                .collect::<Vec<_>>()
                .join(" ");
            let Some(rows) = self.rows.get(key.as_str()) else {
                continue;
            };
            let limit = if span > 1 { PHRASE_LIMIT } else { CANDIDATE_LIMIT };
            let mut taken = 0;
            for entry in rows {
                if out.len() >= CANDIDATE_LIMIT || taken >= limit {
                    break;
                }
                if !tones_match(entry.tones, &query[..span]) || !seen.insert(&*entry.text) {
                    continue;
                }
                out.push(Hit { span, text: &entry.text });
                taken += 1;
            }
        }
        out
    }

    /// `span<TAB>text` lines, one per candidate.
    fn candidate_lines(&self, code: &str) -> String {
        let mut out = String::new();
        for (index, hit) in self.candidates(code).iter().enumerate() {
            if index > 0 {
                out.push('\n');
            }
            out.push_str(&hit.span.to_string());
            out.push('\t');
            out.push_str(hit.text);
        }
        out
    }

    /// [unique] skips the duplicate check for a source that is already deduplicated.
    fn insert(&mut self, reading: &str, text: &str, score: f32, unique: bool) {
        let Some((key, tones)) = split_reading(reading) else {
            return;
        };
        let rows = self.rows.entry(key.into_boxed_str()).or_default();
        if !unique && rows.iter().any(|row| row.tones == tones && &*row.text == text) {
            return;
        }
        rows.push(Entry { text: text.into(), tones, score });
    }

    fn finish(&mut self) {
        for rows in self.rows.values_mut() {
            rows.sort_by(|left, right| right.score.total_cmp(&left.score));
            rows.shrink_to_fit();
        }
    }

    pub fn len(&self) -> usize {
        self.rows.values().map(Vec::len).sum()
    }
}

/// Builds the lexicon from the phrase table and, below it, the Rime dictionary.
/// The table rows are `reading<TAB>text<TAB>log10 probability`, one per reading and text,
/// as tools/import-mcbopomofo.py writes them; `#` lines are comments.
/// Dictionary rows that the table already has keep the table score.
pub fn build(table: &str, dict_yaml: &str) -> Lexicon {
    let mut lexicon = Lexicon::default();
    for line in table.lines() {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut parts = line.split('\t');
        let (Some(reading), Some(text), Some(score)) = (parts.next(), parts.next(), parts.next()) else {
            continue;
        };
        let Ok(score) = score.trim().parse::<f32>() else {
            continue;
        };
        if !text.is_empty() {
            lexicon.insert(reading, text, score, true);
        }
    }
    for (reading, text) in dict_rows(dict_yaml) {
        lexicon.insert(reading, text, SUPPLEMENT_SCORE, false);
    }
    lexicon.finish();
    lexicon
}

/// Rows of a Rime dictionary: the body after the `...` line, `text<TAB>code<TAB>weight`.
fn dict_rows(yaml: &str) -> impl Iterator<Item = (&str, &str)> {
    yaml.lines()
        .skip_while(|line| line.trim() != "...")
        .skip(1)
        .filter_map(|line| {
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with('#') {
                return None;
            }
            let mut parts = line.split('\t');
            let text = parts.next()?;
            let code = parts.next()?;
            (!text.is_empty() && !code.is_empty()).then_some((code, text))
        })
}

fn tone_of(ch: char) -> Option<u8> {
    match ch {
        'ˊ' => Some(2),
        'ˇ' => Some(3),
        'ˋ' => Some(4),
        '˙' => Some(5),
        _ => None,
    }
}

/// `ㄍㄨㄥˋ` is fourth tone, `ㄍㄨㄥ` level tone, `ㄍㄨㄥ*` any tone.
fn parse_query(syllable: &str) -> (String, Option<u8>) {
    if let Some(body) = syllable.strip_suffix('*') {
        return (body.to_string(), None);
    }
    split_tone(syllable)
}

fn split_tone(syllable: &str) -> (String, Option<u8>) {
    match syllable.chars().last().and_then(tone_of) {
        Some(tone) => {
            let body: String = syllable.chars().take(syllable.chars().count() - 1).collect();
            (body, Some(tone))
        }
        None => (syllable.to_string(), Some(1)),
    }
}

/// Toneless key and packed tones of a reading, or None when it is too long.
fn split_reading(reading: &str) -> Option<(String, u32)> {
    let mut key = String::new();
    let mut tones = 0u32;
    let mut count = 0;
    for syllable in reading.split(' ').filter(|part| !part.is_empty()) {
        if count == MAX_SYLLABLES {
            return None;
        }
        let (body, tone) = split_tone(syllable);
        if !key.is_empty() {
            key.push(' ');
        }
        key.push_str(&body);
        tones |= u32::from(tone.unwrap_or(1)) << (3 * count);
        count += 1;
    }
    (count > 0).then_some((key, tones))
}

fn tones_match(tones: u32, query: &[(String, Option<u8>)]) -> bool {
    query.iter().enumerate().all(|(index, (_, want))| match want {
        Some(tone) => (tones >> (3 * index)) & 0b111 == u32::from(*tone),
        None => true,
    })
}

mod android {
    use super::{build, LEXICON};
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jint, jstring};
    use jni::JNIEnv;

    fn utf8(env: &JNIEnv, bytes: &JByteArray) -> Option<String> {
        let raw = env.convert_byte_array(bytes).ok()?;
        String::from_utf8(raw).ok()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_fskeyboard_ime_engine_RustLexiconNative_nativeLoad<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        table: JByteArray<'local>,
        dict: JByteArray<'local>,
    ) -> jint {
        let (Some(table), Some(dict)) = (utf8(&env, &table), utf8(&env, &dict)) else {
            return -1;
        };
        let lexicon = build(&table, &dict);
        match LEXICON.lock() {
            Ok(mut guard) => *guard = Some(lexicon),
            Err(poisoned) => *poisoned.into_inner() = Some(lexicon),
        }
        0
    }

    #[no_mangle]
    pub extern "system" fn Java_com_fskeyboard_ime_engine_RustLexiconNative_nativeLookup<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        code: JString<'local>,
    ) -> jstring {
        let key: String = match env.get_string(&code) {
            Ok(value) => value.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let found = match LEXICON.lock() {
            Ok(guard) => guard.as_ref().map(|lexicon| lexicon.candidate_lines(&key)),
            Err(poisoned) => poisoned
                .into_inner()
                .as_ref()
                .map(|lexicon| lexicon.candidate_lines(&key)),
        }
        .unwrap_or_default();
        env.new_string(found)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }
}

#[cfg(test)]
mod tests {
    use super::{build, Hit, Lexicon, CANDIDATE_LIMIT};
    use std::path::Path;

    fn project() -> Lexicon {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../app/src/main/assets");
        let table = std::fs::read_to_string(root.join("lexicon/mcbopomofo.tsv")).expect("mcbopomofo.tsv");
        let dict = std::fs::read_to_string(root.join("rime/shared/fs_zhuyin.dict.yaml")).expect("dict");
        build(&table, &dict)
    }

    fn texts(hits: &[Hit<'_>]) -> Vec<String> {
        hits.iter().map(|hit| hit.text.to_string()).collect()
    }

    const SAMPLE: &str = "\
# comment
ㄍㄨㄥ\t公\t-3.0
ㄍㄨㄥ\t工\t-3.1
ㄍㄨㄥˋ\t貢\t-4.0
ㄎㄜˋ\t課\t-3.5
ㄍㄨㄥ ㄎㄜˋ\t功課\t-4.5
ㄐㄧㄣ ㄊㄧㄢ\t今天\t-3.2
ㄐㄧㄣ\t今\t-3.3
ㄊㄧㄢ\t天\t-3.0
";

    const DICT: &str = "\
---
name: sample
...
# comment
公\tㄍㄨㄥ\t80
攻\tㄍㄨㄥ\t24
";

    #[test]
    fn the_longest_phrase_comes_first_and_shorter_ones_follow() {
        let lexicon = build(SAMPLE, "");
        let hits = lexicon.candidates("ㄍㄨㄥ* ㄎㄜ*");
        assert_eq!(hits[0], Hit { span: 2, text: "功課" });
        assert_eq!(texts(&hits[1..]), vec!["公", "工", "貢"]);
        assert!(hits[1..].iter().all(|hit| hit.span == 1));
    }

    #[test]
    fn candidates_only_cover_the_front_of_the_composition() {
        let lexicon = build(SAMPLE, "");
        let hits = lexicon.candidates("ㄍㄨㄥ ㄊㄧㄢ");
        assert_eq!(texts(&hits), vec!["公", "工"]);
        assert!(texts(&hits).iter().all(|text| text != "天"));
    }

    #[test]
    fn a_chosen_tone_narrows_and_an_open_one_predicts_every_tone() {
        let lexicon = build(SAMPLE, "");
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ")), vec!["公", "工"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥˋ")), vec!["貢"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ*")), vec!["公", "工", "貢"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ ㄎㄜˋ")), vec!["功課", "公", "工"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥˋ ㄎㄜˋ")), vec!["貢"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ* ㄎㄜˋ"))[0], "功課");
    }

    #[test]
    fn the_dictionary_adds_missing_rows_below_the_table() {
        let lexicon = build(SAMPLE, DICT);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ")), vec!["公", "工", "攻"]);
        assert_eq!(lexicon.len(), 9);
    }

    #[test]
    fn candidate_lines_carry_the_span() {
        let lexicon = build(SAMPLE, "");
        assert_eq!(lexicon.candidate_lines("ㄐㄧㄣ ㄊㄧㄢ"), "2\t今天\n1\t今");
        assert_eq!(lexicon.candidate_lines("ㄅㄚ"), "");
    }

    #[test]
    fn the_project_lexicon_finds_common_phrases() {
        let lexicon = project();
        assert_eq!(lexicon.candidates("ㄍㄨㄥ")[0].text, "公");
        assert_eq!(lexicon.candidates("ㄋㄧˇ ㄏㄠˇ")[0], Hit { span: 2, text: "你好" });
        let today = lexicon.candidates("ㄐㄧㄣ* ㄊㄧㄢ* ㄊㄧㄢ* ㄑㄧ*");
        assert_eq!(today[0].span, 2);
        assert!(today.iter().any(|hit| hit.text == "今天"));
        assert!(today.len() <= CANDIDATE_LIMIT);
        assert!(today.iter().any(|hit| hit.span == 1 && hit.text == "今"));
    }
}
