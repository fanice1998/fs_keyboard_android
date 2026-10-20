//! Offline corpus tools for the bigram model. Not packaged into the app.
//!
//! Both commands read sentences on stdin, one per line, and split runs of Han
//! characters into words by the highest summed log10 probability in the phrase
//! table, the way the keyboard converts. Any other run (punctuation, Latin, digits,
//! spaces) becomes a boundary token of `|` repeated once per character, so the
//! tokens line up with the sentence and a bigram never crosses them.
//!
//! `segment <table>`  writes the tokens separated by a space.
//! `annotate <table>` writes each word as `word/reading`, syllables joined by `-`;
//!                    a character the table lacks reads `?`.
//! `count <minimum>`  reads `segment` output and writes `w1<TAB>w2<TAB>log10 P(w2|w1)`
//!                    for each pair of adjacent words seen at least <minimum> times.
//!                    Each run between boundaries starts after `<s>`. P is the pair's
//!                    count over every pair that starts with w1, pruned ones included.
use std::collections::HashMap;
use std::io::{self, BufRead, BufWriter, Write};
use std::process::ExitCode;

/// Longest word, in characters, the split looks for.
const MAX_WORD: usize = 8;
/// Score of a character the phrase table does not have.
const UNKNOWN_SCORE: f32 = -12.0;

/// Best score of each text in the phrase table, and the reading that score belongs to.
type Words = HashMap<Box<str>, (f32, Box<str>)>;

#[derive(Debug, PartialEq)]
enum Token<'a> {
    Word(&'a str),
    /// A run of non-Han characters, by its length in characters.
    Boundary(usize),
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let command = args.get(1).map(String::as_str);
    let result = match (command, args.get(2)) {
        (Some("segment"), Some(table)) if args.len() == 3 => run(table, false),
        (Some("annotate"), Some(table)) if args.len() == 3 => run(table, true),
        (Some("count"), Some(minimum)) if args.len() == 3 => match minimum.parse() {
            Ok(minimum) => count(minimum),
            Err(_) => Err(io::Error::new(io::ErrorKind::InvalidInput, "minimum must be a number")),
        },
        _ => {
            eprintln!("usage: corpus_tools segment|annotate <mcbopomofo.tsv> < sentences");
            eprintln!("       corpus_tools count <minimum> < segmented");
            return ExitCode::FAILURE;
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("corpus_tools: {error}");
            ExitCode::FAILURE
        }
    }
}

fn run(table: &str, readings: bool) -> io::Result<()> {
    let words = load_words(&std::fs::read_to_string(table)?);
    let mut reader = io::stdin().lock();
    let mut out = BufWriter::new(io::stdout().lock());
    let mut line = String::new();
    loop {
        line.clear();
        if reader.read_line(&mut line)? == 0 {
            break;
        }
        let tokens = split_sentence(line.trim_end_matches(['\n', '\r']), &words);
        let rendered: Vec<String> = tokens
            .iter()
            .map(|token| match token {
                Token::Boundary(length) => "|".repeat(*length),
                Token::Word(word) if readings => {
                    let reading = words.get(*word).map_or("?", |(_, reading)| reading);
                    format!("{word}/{reading}")
                }
                Token::Word(word) => word.to_string(),
            })
            .collect();
        out.write_all(rendered.join(" ").as_bytes())?;
        out.write_all(b"\n")?;
    }
    out.flush()
}

/// The sentence start, as the left word of a run's first pair.
const START: &str = "<s>";

fn count(minimum: u32) -> io::Result<()> {
    let lines = io::stdin().lock().lines().collect::<io::Result<Vec<String>>>()?;
    let mut out = BufWriter::new(io::stdout().lock());
    for (left, right, probability) in bigrams(lines.iter().map(String::as_str), minimum) {
        writeln!(out, "{left}\t{right}\t{:.4}", probability.log10())?;
    }
    out.flush()
}

/// (w1, w2, P(w2|w1)) for each adjacent pair seen at least [minimum] times, sorted.
fn bigrams<'a>(lines: impl Iterator<Item = &'a str>, minimum: u32) -> Vec<(String, String, f64)> {
    let mut ids: HashMap<&str, u32> = HashMap::new();
    let mut names: Vec<&str> = Vec::new();
    let mut intern = |word: &'a str| -> u32 {
        *ids.entry(word).or_insert_with(|| {
            names.push(word);
            (names.len() - 1) as u32
        })
    };
    let start = intern(START);
    let mut pairs: HashMap<(u32, u32), u32> = HashMap::new();
    let mut lefts: HashMap<u32, u64> = HashMap::new();
    for line in lines {
        let mut previous = start;
        for token in line.split_whitespace() {
            if token.starts_with('|') {
                previous = start;
                continue;
            }
            let word = intern(token);
            *pairs.entry((previous, word)).or_default() += 1;
            *lefts.entry(previous).or_default() += 1;
            previous = word;
        }
    }
    let mut kept: Vec<(String, String, f64)> = pairs
        .into_iter()
        .filter(|(_, n)| *n >= minimum)
        .map(|((left, right), n)| {
            let probability = n as f64 / lefts[&left] as f64;
            (names[left as usize].to_string(), names[right as usize].to_string(), probability)
        })
        .collect();
    kept.sort_by(|a, b| (&a.0, &a.1).cmp(&(&b.0, &b.1)));
    kept
}

fn load_words(table: &str) -> Words {
    let mut words: Words = HashMap::new();
    for line in table.lines() {
        if line.starts_with('#') {
            continue;
        }
        let mut parts = line.split('\t');
        let (Some(reading), Some(text), Some(score)) = (parts.next(), parts.next(), parts.next()) else {
            continue;
        };
        let Ok(score) = score.parse::<f32>() else {
            continue;
        };
        if text.is_empty() || !text.chars().all(is_han) {
            continue;
        }
        let reading: Box<str> = reading.replace(' ', "-").into();
        match words.get_mut(text) {
            Some(best) if best.0 >= score => {}
            Some(best) => *best = (score, reading),
            None => {
                words.insert(text.into(), (score, reading));
            }
        }
    }
    words
}

fn is_han(ch: char) -> bool {
    matches!(ch as u32, 0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF | 0x20000..=0x3134F)
}

/// Words of each Han run in [sentence], with a boundary for every other run.
fn split_sentence<'a>(sentence: &'a str, words: &Words) -> Vec<Token<'a>> {
    let mut out = Vec::new();
    let mut han_start: Option<usize> = None;
    let mut other = 0;
    for (index, ch) in sentence.char_indices() {
        if is_han(ch) {
            if other > 0 {
                out.push(Token::Boundary(other));
                other = 0;
            }
            han_start.get_or_insert(index);
        } else {
            if let Some(start) = han_start.take() {
                split_run(&sentence[start..index], words, &mut out);
            }
            other += 1;
        }
    }
    if let Some(start) = han_start {
        split_run(&sentence[start..], words, &mut out);
    }
    if other > 0 {
        out.push(Token::Boundary(other));
    }
    out
}

/// Splits a run of Han characters by the highest summed word score.
fn split_run<'a>(run: &'a str, words: &Words, out: &mut Vec<Token<'a>>) {
    let bounds: Vec<usize> = run.char_indices().map(|(index, _)| index).chain([run.len()]).collect();
    let n = bounds.len() - 1;
    let mut score = vec![f32::NEG_INFINITY; n + 1];
    let mut from = vec![0usize; n + 1];
    score[0] = 0.0;
    for end in 1..=n {
        for length in 1..=end.min(MAX_WORD) {
            let start = end - length;
            let word = &run[bounds[start]..bounds[end]];
            let value = match words.get(word) {
                Some((value, _)) => *value,
                None if length == 1 => UNKNOWN_SCORE,
                None => continue,
            };
            if score[start] + value > score[end] {
                score[end] = score[start] + value;
                from[end] = start;
            }
        }
    }
    let mut cuts = Vec::new();
    let mut end = n;
    while end > 0 {
        cuts.push((from[end], end));
        end = from[end];
    }
    for (start, end) in cuts.into_iter().rev() {
        out.push(Token::Word(&run[bounds[start]..bounds[end]]));
    }
}

#[cfg(test)]
mod tests {
    use super::{bigrams, load_words, split_sentence, Token};

    const TABLE: &str = "\
# comment
ㄐㄧㄣ ㄊㄧㄢ\t今天\t-3.2
ㄐㄧㄣ\t今\t-3.3
ㄊㄧㄢ\t天\t-3.0
ㄊㄧㄢ ㄑㄧˋ\t天氣\t-3.5
ㄑㄧˋ\t氣\t-3.4
ㄏㄣˇ\t很\t-2.5
ㄏㄠˇ\t好\t-2.4
ㄏㄠˋ\t好\t-4.0
";

    fn words_of(sentence: &str) -> Vec<String> {
        let words = load_words(TABLE);
        split_sentence(sentence, &words)
            .iter()
            .map(|token| match token {
                Token::Word(word) => word.to_string(),
                Token::Boundary(length) => "|".repeat(*length),
            })
            .collect()
    }

    #[test]
    fn han_runs_split_into_words_and_other_runs_keep_their_length() {
        assert_eq!(words_of("今天天氣很好"), vec!["今天", "天氣", "很", "好"]);
        assert_eq!(words_of("今天，iPhone 天氣很好！"), vec!["今天", "||||||||", "天氣", "很", "好", "|"]);
        assert_eq!(words_of("「今天」"), vec!["|", "今天", "|"]);
        assert_eq!(words_of("hello"), vec!["|||||"]);
    }

    #[test]
    fn bigrams_restart_after_a_boundary_and_keep_pruned_pairs_in_the_total() {
        let lines = ["今天 天氣 很 好 | 今天 天氣", "今天 很 好"];
        let found = bigrams(lines.into_iter(), 1);
        let p = |left: &str, right: &str| {
            found.iter().find(|(a, b, _)| a == left && b == right).map(|(_, _, p)| *p)
        };
        assert_eq!(p("<s>", "今天"), Some(1.0));
        assert!((p("今天", "天氣").unwrap() - 2.0 / 3.0).abs() < 1e-9);
        assert_eq!(p("天氣", "今天"), None);
        let pruned = bigrams(lines.into_iter(), 2);
        assert!((pruned.iter().find(|(a, b, _)| a == "今天" && b == "天氣").unwrap().2 - 2.0 / 3.0).abs() < 1e-9);
        assert!(pruned.iter().all(|(a, b, _)| !(a == "今天" && b == "很")));
    }

    #[test]
    fn an_unknown_character_stays_on_its_own() {
        assert_eq!(words_of("今天鑫"), vec!["今天", "鑫"]);
    }

    #[test]
    fn a_word_keeps_its_best_scoring_reading() {
        let words = load_words(TABLE);
        assert_eq!(&*words.get("好").unwrap().1, "ㄏㄠˇ");
        assert_eq!(&*words.get("今天").unwrap().1, "ㄐㄧㄣ-ㄊㄧㄢ");
    }
}
