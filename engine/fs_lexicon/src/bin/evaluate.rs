//! Measures conversion accuracy on the reviewed test set.
//!
//!     cargo run --release --bin evaluate -- data/review/c_testset.tsv \
//!         data/lexicon/mcbopomofo.tsv app/src/main/assets/rime/shared/fs_zhuyin.dict.yaml
//!
//! Each sentence is typed three ways: full zhuyin with tones (space for 平聲), without
//! tones, and initials only. The glyphs go through the same syllable rules as
//! ZhuyinComposer, so ㄉ then ㄧ becomes ㄉㄧ as on the keyboard. The first candidate
//! is compared with the Han characters of the sentence. Prints sentence accuracy and
//! character accuracy (one minus edit distance over length) for each way, and with
//! `--misses N`, up to N wrong sentences per way. `--bigram <file>` compiles bigrams
//! from corpus_tools count in; `--lambda 0,0.3` runs once per bigram weight.
use std::process::ExitCode;

#[derive(Clone, Copy, PartialEq, PartialOrd)]
enum Class {
    Initial,
    Medial,
    Final,
}

fn class(ch: char) -> Option<Class> {
    match ch {
        'ㄅ'..='ㄙ' => Some(Class::Initial),
        'ㄧ' | 'ㄨ' | 'ㄩ' => Some(Class::Medial),
        'ㄚ'..='ㄦ' => Some(Class::Final),
        _ => None,
    }
}

fn is_tone(ch: char) -> bool {
    matches!(ch, 'ˊ' | 'ˇ' | 'ˋ' | '˙')
}

/// ZhuyinPhonology.medialFits.
fn medial_fits(initial: Option<char>, medial: char) -> bool {
    match initial {
        None => true,
        Some('ㄅ' | 'ㄆ' | 'ㄇ') => medial != 'ㄩ',
        Some('ㄈ') => medial == 'ㄨ',
        Some('ㄉ' | 'ㄊ' | 'ㄋ' | 'ㄌ') => true,
        Some('ㄍ' | 'ㄎ' | 'ㄏ') => medial == 'ㄨ',
        Some('ㄐ' | 'ㄑ' | 'ㄒ') => medial == 'ㄧ' || medial == 'ㄩ',
        Some('ㄓ' | 'ㄔ' | 'ㄕ' | 'ㄖ' | 'ㄗ' | 'ㄘ' | 'ㄙ') => medial == 'ㄨ',
        Some(_) => true,
    }
}

/// One syllable being composed: body glyphs and tone (None until chosen, "" for 平聲).
struct Part {
    body: String,
    tone: Option<&'static str>,
}

/// ZhuyinComposer.append and markLevelTone over a typed key sequence, where a space is 平聲.
/// Returns the query codes ZhuyinComposer.queries gives.
fn compose(keys: &str) -> Vec<String> {
    let mut parts: Vec<Part> = Vec::new();
    for ch in keys.chars() {
        if ch == ' ' {
            if let Some(last) = parts.last_mut() {
                last.tone = Some("");
            }
            continue;
        }
        if is_tone(ch) {
            if let Some(last) = parts.last_mut() {
                last.tone = Some(match ch {
                    'ˊ' => "ˊ",
                    'ˇ' => "ˇ",
                    'ˋ' => "ˋ",
                    _ => "˙",
                });
            }
            continue;
        }
        let Some(kind) = class(ch) else {
            continue;
        };
        let extends = parts
            .last()
            .is_some_and(|last| last.tone.is_none() && can_append(&last.body, ch, kind));
        if extends {
            parts.last_mut().unwrap().body.push(ch);
        } else {
            parts.push(Part {
                body: ch.to_string(),
                tone: None,
            });
        }
    }
    parts
        .into_iter()
        .map(|part| match part.tone {
            Some(mark) => format!("{}{mark}", part.body),
            None => format!("{}*", part.body),
        })
        .collect()
}

/// ZhuyinComposer.canAppendBody.
fn can_append(body: &str, ch: char, kind: Class) -> bool {
    let mut initial = None;
    let mut seen = Vec::new();
    for glyph in body.chars() {
        let Some(found) = class(glyph) else {
            return false;
        };
        if found == Class::Initial {
            initial = Some(glyph);
        }
        seen.push(found);
    }
    let Some(&last) = seen.last() else {
        return true;
    };
    if kind == Class::Medial && !medial_fits(initial, ch) {
        return false;
    }
    !seen.contains(&kind) && kind > last
}

fn han(text: &str) -> Vec<char> {
    text.chars()
        .filter(|ch| matches!(*ch as u32, 0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF))
        .collect()
}

fn edit_distance(a: &[char], b: &[char]) -> usize {
    let mut row: Vec<usize> = (0..=b.len()).collect();
    for (i, x) in a.iter().enumerate() {
        let mut previous = row[0];
        row[0] = i + 1;
        for (j, y) in b.iter().enumerate() {
            let current = row[j + 1];
            row[j + 1] = if x == y {
                previous
            } else {
                1 + previous.min(row[j]).min(row[j + 1])
            };
            previous = current;
        }
    }
    row[b.len()]
}

fn main() -> ExitCode {
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    let mut option = |name: &str| -> Option<String> {
        let at = args.iter().position(|arg| arg == name)?;
        let value = args.get(at + 1).cloned();
        args.drain(at..(at + 2).min(args.len()));
        value
    };
    let misses: usize = option("--misses").and_then(|n| n.parse().ok()).unwrap_or(0);
    let bigram = option("--bigram");
    let lambdas: Vec<f32> = option("--lambda")
        .map(|list| {
            list.split(',')
                .filter_map(|value| value.parse().ok())
                .collect()
        })
        .unwrap_or_default();
    let [tests, table, dict] = args.as_slice() else {
        eprintln!("usage: evaluate <c_testset.tsv> <phrase table> <rime dictionary> [--misses N]");
        return ExitCode::FAILURE;
    };
    let read = |path: &str| {
        std::fs::read_to_string(path).unwrap_or_else(|error| panic!("{path}: {error}"))
    };
    let bigrams = bigram.map(|path| read(&path)).unwrap_or_default();
    let compiled = fs_lexicon::compile(&read(table), &read(dict), &bigrams);
    let mut lexicon = fs_lexicon::open(compiled, "").expect("a freshly compiled lexicon opens");
    let rows: Vec<Vec<String>> = read(tests)
        .lines()
        .skip(1)
        .map(|line| line.split('\t').map(String::from).collect())
        .filter(|cells: &Vec<String>| cells.len() >= 6)
        .collect();

    // Column 3 is the full reading, syllables separated by a space.
    let ways: [(&str, fn(&str) -> String); 3] = [
        ("完整注音", |reading| {
            // A level-tone syllable is followed by a space, as the keyboard types 平聲.
            reading
                .split(' ')
                .map(|s| {
                    if s.ends_with(is_tone) {
                        s.to_string()
                    } else {
                        format!("{s} ")
                    }
                })
                .collect()
        }),
        ("不打聲調", |reading| {
            reading
                .split(' ')
                .map(|s| s.trim_end_matches(is_tone))
                .collect()
        }),
        ("只打聲母", |reading| {
            reading
                .split(' ')
                .filter_map(|s| s.chars().next())
                .collect()
        }),
    ];
    let runs: Vec<Option<f32>> = if lambdas.is_empty() {
        vec![None]
    } else {
        lambdas.into_iter().map(Some).collect()
    };
    for lambda in runs {
        if let Some(lambda) = lambda {
            lexicon.set_bigram_weight(lambda);
            println!("bigram λ = {lambda}");
        }
        for (name, typing) in ways {
            let (mut right, mut chars, mut errors) = (0, 0, 0);
            let mut wrong = Vec::new();
            for cells in &rows {
                let expected = han(&cells[1]);
                let code = compose(&typing(&cells[3])).join(" ");
                let got: String = lexicon
                    .candidates(&code)
                    .first()
                    .map(|hit| hit.text.to_string())
                    .unwrap_or_default();
                let got = han(&got);
                let distance = edit_distance(&expected, &got);
                chars += expected.len();
                errors += distance.min(expected.len());
                if distance == 0 {
                    right += 1;
                } else if wrong.len() < misses {
                    wrong.push(format!(
                        "    {} → {}",
                        cells[1],
                        got.iter().collect::<String>()
                    ));
                }
            }
            println!(
                "{name}: sentences {right}/{} ({:.1}%), characters {:.1}%",
                rows.len(),
                100.0 * right as f64 / rows.len() as f64,
                100.0 * (chars - errors) as f64 / chars as f64,
            );
            for line in wrong {
                println!("{line}");
            }
        }
    }
    ExitCode::SUCCESS
}

#[cfg(test)]
mod tests {
    use super::compose;

    #[test]
    fn composing_follows_the_keyboard() {
        assert_eq!(compose("ㄐㄧㄣ ㄊㄧㄢ "), vec!["ㄐㄧㄣ", "ㄊㄧㄢ"]);
        assert_eq!(compose("ㄨㄛˇㄇㄣ˙"), vec!["ㄨㄛˇ", "ㄇㄣ˙"]);
        assert_eq!(compose("ㄐㄧㄣㄊㄧㄢ"), vec!["ㄐㄧㄣ*", "ㄊㄧㄢ*"]);
        assert_eq!(
            compose("ㄨㄇㄐㄊㄑㄎㄉㄧ"),
            vec!["ㄨ*", "ㄇ*", "ㄐ*", "ㄊ*", "ㄑ*", "ㄎ*", "ㄉㄧ*"]
        );
        assert_eq!(compose("ㄓㄨ"), vec!["ㄓㄨ*"]);
        assert_eq!(compose("ㄍㄧ"), vec!["ㄍ*", "ㄧ*"]);
    }
}
