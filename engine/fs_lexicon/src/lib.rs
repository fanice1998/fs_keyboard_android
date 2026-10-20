mod store;

use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

pub use store::Row;
use store::{initials_of, EntryRef, Store, START};

const MAX_SPAN: usize = 8;
/// Tones are packed three bits per syllable, so longer readings are skipped.
const MAX_SYLLABLES: usize = 10;
const CANDIDATE_LIMIT: usize = 60;
/// Phrases are capped per length so single characters still reach the list.
const PHRASE_LIMIT: usize = 20;
/// Score for a row that only the supplementary dictionary has. Below every phrase-table row.
const SUPPLEMENT_SCORE: f32 = -9.0;
/// Score for a syllable that no word covers. It keeps its zhuyin in a conversion.
const UNKNOWN_SCORE: f32 = -20.0;
/// Subtracted, in log10, from a word found only by the first glyphs of an untoned syllable,
/// so a fully typed reading still ranks first.
const ABBREVIATION_PENALTY: f32 = 2.0;
/// Score of a phrase the user put together from chosen words. Above nearly every table row.
const USER_SCORE: f32 = -1.0;
/// A span with more initial+medial syllables than this is read only as typed.
const MAX_SPLITS: usize = 3;
/// With a bigram model, splits kept per position, each ending in a different word.
const BEAM: usize = 8;
/// With a bigram model, words tried for each span of syllables.
const WORDS_PER_SPAN: usize = 6;
/// λ in λ·P(word|previous) + (1 − λ)·P(word), tuned on data/review/c_testset.tsv.
const BIGRAM_WEIGHT: f32 = 0.3;
/// How many characters before the cursor are searched for the previous word.
const CONTEXT_CHARS: usize = 8;

static LEXICON: Mutex<Option<Lexicon>> = Mutex::new(None);

/// The compiled lexicon, plus the phrases learned since it was opened.
/// Learned entries take the ids after the compiled ones.
pub struct Lexicon {
    store: Store,
    learned: Vec<Learned>,
    learned_by_reading: HashMap<Box<str>, Vec<u32>>,
    learned_by_initials: HashMap<Box<str>, Vec<u32>>,
    /// Syllable bodies that a longer syllable starts with, such as ㄐ and ㄐㄧ.
    open: HashSet<Box<str>>,
    /// λ for the stored bigrams; 0 turns them off.
    bigram_weight: f32,
}

/// A word that can cover some syllables during conversion.
struct Word<'a> {
    text: Cow<'a, str>,
    /// Its id in the word table, for bigrams.
    id: Option<u32>,
    reading: String,
    /// The stored log10 probability.
    score: f32,
    /// What the input match costs, such as [ABBREVIATION_PENALTY].
    penalty: f32,
}

/// One split of the syllables before a position, and how it got there.
#[derive(Clone, Copy)]
struct State {
    score: f32,
    from: Option<Step>,
}

/// The last word of a split: its span, its index among the words for that span, and
/// the state it extends at the position where it starts.
#[derive(Clone, Copy)]
struct Step {
    span: usize,
    word: usize,
    state: usize,
}

struct Learned {
    text: Box<str>,
    reading: Box<str>,
    tones: u32,
    word: Option<u32>,
}

/// One candidate. [span] is how many typed syllables, from the front, it uses.
/// [reading] is the reading it was found under, tones marked and `*` for any tone.
#[derive(Clone, Debug, PartialEq)]
pub struct Hit<'a> {
    pub span: usize,
    pub text: Cow<'a, str>,
    pub reading: String,
}

/// One typed syllable: toneless body, wanted tone (None while it has none), and as typed.
struct Syllable {
    body: String,
    tone: Option<u8>,
    display: String,
}

/// One dictionary syllable a typed one is read as.
#[derive(Clone, Copy)]
struct Token<'q> {
    body: &'q str,
    tone: Option<u8>,
}

impl Lexicon {
    /// Candidates for [code]: the best conversion of the whole input first, when it
    /// takes more than one word, then the front of the input, longest phrase first.
    ///
    /// [code] is syllables joined by a space. A syllable ends with its tone mark,
    /// no mark is the level tone, and `*` means the tone is not chosen yet. A syllable
    /// without a tone also matches longer ones that start with it, so ㄐ* finds ㄐㄧㄣ.
    pub fn candidates(&self, code: &str) -> Vec<Hit<'_>> {
        self.candidates_after(code, "")
    }

    /// [Lexicon::candidates] for input typed right after [before], the text before the
    /// cursor. Its last word starts the conversion and orders the front words.
    pub fn candidates_after(&self, code: &str, before: &str) -> Vec<Hit<'_>> {
        let query = parse_code(code);
        let previous = self.context_word(before);
        let mut out = Vec::new();
        let sentence = if query.len() > 1 { self.best_path(&query, previous) } else { Vec::new() };
        if sentence.len() > 1 {
            out.push(Hit {
                span: query.len(),
                text: Cow::Owned(sentence.iter().map(|hit| hit.text.as_ref()).collect()),
                reading: sentence.iter().map(|hit| hit.reading.as_str()).collect::<Vec<_>>().join(" "),
            });
        }
        let sentence_text = out.first().map(|hit| hit.text.to_string());
        let mut seen: HashSet<&str> = HashSet::new();
        for span in (1..=query.len().min(MAX_SPAN)).rev() {
            let limit = if span > 1 { PHRASE_LIMIT } else { CANDIDATE_LIMIT };
            let mut taken = 0;
            for (id, _) in self.in_context(self.ranked_span(&query[..span], limit), previous) {
                if out.len() >= CANDIDATE_LIMIT || taken >= limit {
                    break;
                }
                let entry = self.entry(id);
                let text = entry.text;
                if sentence_text.as_deref() == Some(text) || !seen.insert(text) {
                    continue;
                }
                out.push(Hit { span, text: Cow::Borrowed(text), reading: reading_of(&entry) });
                taken += 1;
            }
        }
        out
    }

    /// The words of the best conversion of all of [code], front to back.
    pub fn convert(&self, code: &str) -> Vec<Hit<'_>> {
        self.best_path(&parse_code(code), self.store.word(START))
    }

    /// Sets λ for the stored bigrams, for tuning; 0 turns them off.
    pub fn set_bigram_weight(&mut self, lambda: f32) {
        self.bigram_weight = lambda.clamp(0.0, 1.0);
    }

    fn uses_bigrams(&self) -> bool {
        self.bigram_weight > 0.0 && self.store.pair_count() > 0
    }

    /// The word that [before] ends with: its longest suffix that is a word, or the sentence
    /// start when it is empty or ends with anything but a Han character.
    fn context_word(&self, before: &str) -> Option<u32> {
        let start = self.store.word(START);
        let tail: Vec<(usize, char)> = before.char_indices().rev().take(CONTEXT_CHARS).collect();
        if !tail.first().is_some_and(|(_, ch)| is_han(*ch)) {
            return start;
        }
        let han = tail.iter().take_while(|(_, ch)| is_han(*ch)).count();
        tail[..han].iter().rev().find_map(|(at, _)| self.store.word(&before[*at..])).or(start)
    }

    /// [ranked] entries reordered by how well each follows [previous], when bigrams are on.
    fn in_context(&self, mut ranked: Vec<(u32, f32)>, previous: Option<u32>) -> Vec<(u32, f32)> {
        if !self.uses_bigrams() {
            return ranked;
        }
        for (id, adjusted) in ranked.iter_mut() {
            let entry = self.entry(*id);
            *adjusted = self.transition(previous, entry.word, entry.score) - (entry.score - *adjusted);
        }
        ranked.sort_by(|a, b| b.1.total_cmp(&a.1));
        ranked
    }

    /// Remembers [text] under [reading], given the way [Lexicon::candidates] reports
    /// readings, at [USER_SCORE], above the same word already in the table.
    /// Returns false when it was already learned.
    pub fn learn(&mut self, reading: &str, text: &str) -> bool {
        let Some((key, tones)) = split_reading(reading) else {
            return false;
        };
        if text.is_empty() {
            return false;
        }
        let known = self.learned_by_reading.get(key.as_str()).is_some_and(|ids| {
            ids.iter().any(|&id| {
                let entry = self.entry(id);
                entry.text == text && (entry.tones == tones || tones_cover(tones, entry.tones))
            })
        });
        if known {
            return false;
        }
        let id = (self.store.len() + self.learned.len()) as u32;
        let word = self.store.word(text);
        self.learned.push(Learned { text: text.into(), reading: key.as_str().into(), tones, word });
        self.learned_by_reading.entry(key.as_str().into()).or_default().push(id);
        self.learned_by_initials.entry(initials_of(&key).into_boxed_str()).or_default().push(id);
        for syllable in key.split(' ') {
            for (index, _) in syllable.char_indices().skip(1) {
                self.open.insert(syllable[..index].into());
            }
        }
        true
    }

    /// Highest summed log probability over every way to split [query] into words.
    /// With bigrams each word is scored given the one before it, the first given
    /// [previous], and every position keeps the [BEAM] best splits that end in different
    /// words. Without them, a single best split per position, by word score alone.
    /// A syllable with no word of its own stays as typed, at [UNKNOWN_SCORE].
    fn best_path<'a>(&'a self, query: &[Syllable], previous: Option<u32>) -> Vec<Hit<'a>> {
        let n = query.len();
        let (beam, choices) = if self.uses_bigrams() { (BEAM, WORDS_PER_SPAN) } else { (1, 1) };
        // words[end][span - 1]: the words that can cover query[end - span..end].
        let mut words: Vec<Vec<Vec<Word<'a>>>> = vec![Vec::new()];
        let mut states: Vec<Vec<State>> = vec![vec![State { score: 0.0, from: None }]];
        for end in 1..=n {
            let here: Vec<Vec<Word<'a>>> = (1..=end.min(MAX_SPAN))
                .map(|span| self.words_for(&query[end - span..end], span, choices))
                .collect();
            let mut found: Vec<(State, &str)> = Vec::new();
            for (span_index, candidates) in here.iter().enumerate() {
                let start = end - span_index - 1;
                for (state_index, state) in states[start].iter().enumerate() {
                    let before = state.from.map_or(previous, |step| words[start][step.span - 1][step.word].id);
                    for (word_index, word) in candidates.iter().enumerate() {
                        let score = state.score + self.transition(before, word.id, word.score) - word.penalty;
                        let from = Some(Step { span: span_index + 1, word: word_index, state: state_index });
                        found.push((State { score, from }, word.text.as_ref()));
                    }
                }
            }
            found.sort_by(|a, b| b.0.score.total_cmp(&a.0.score));
            let mut seen = HashSet::new();
            let kept: Vec<State> = found
                .into_iter()
                .filter(|(_, text)| seen.insert(*text))
                .take(beam)
                .map(|(state, _)| state)
                .collect();
            words.push(here);
            states.push(kept);
        }
        let mut out = Vec::new();
        let (mut end, mut index) = (n, 0);
        while end > 0 {
            let Some(step) = states[end].get(index).and_then(|state| state.from) else {
                break;
            };
            let word = &words[end][step.span - 1][step.word];
            out.push(Hit { span: step.span, text: word.text.clone(), reading: word.reading.clone() });
            end -= step.span;
            index = step.state;
        }
        out.reverse();
        out
    }

    /// Up to [limit] words for exactly [query], or the syllable as typed when a single
    /// syllable has none.
    fn words_for<'a>(&'a self, query: &[Syllable], span: usize, limit: usize) -> Vec<Word<'a>> {
        let mut out: Vec<Word<'a>> = self
            .ranked_span(query, limit)
            .into_iter()
            .map(|(id, adjusted)| {
                let entry = self.entry(id);
                Word {
                    text: Cow::Borrowed(entry.text),
                    id: entry.word,
                    reading: reading_of(&entry),
                    score: entry.score,
                    penalty: entry.score - adjusted,
                }
            })
            .collect();
        if out.is_empty() && span == 1 {
            let typed = &query[0];
            let reading = if typed.tone.is_none() { format!("{}*", typed.body) } else { typed.display.clone() };
            out.push(Word { text: Cow::Owned(typed.display.clone()), id: None, reading, score: UNKNOWN_SCORE, penalty: 0.0 });
        }
        out
    }

    /// log10 of [word] after [previous]: the bigram probability mixed with the word's own
    /// [score] as λ·P(word|previous) + (1 − λ)·P(word). An unseen pair keeps the second part.
    fn transition(&self, previous: Option<u32>, word: Option<u32>, score: f32) -> f32 {
        if !self.uses_bigrams() {
            return score;
        }
        let pair = match (previous, word) {
            (Some(previous), Some(word)) => self.store.bigram(previous, word),
            _ => None,
        };
        let lambda = self.bigram_weight;
        (lambda * pair.map_or(0.0, |p| 10f32.powf(p)) + (1.0 - lambda) * 10f32.powf(score)).log10()
    }

    /// Entries for exactly the typed syllables [query], best adjusted score first,
    /// over every way to read them: as typed, and with an initial+medial syllable
    /// such as ㄉㄧ also read as the two abbreviated syllables ㄉ and ㄧ.
    fn ranked_span(&self, query: &[Syllable], limit: usize) -> Vec<(u32, f32)> {
        let mut found: Vec<(u32, f32)> = Vec::new();
        for tokens in readings(query) {
            found.extend(self.ranked(&tokens, limit));
        }
        found.sort_by(|left, right| right.1.total_cmp(&left.1));
        let mut seen = HashSet::new();
        found.retain(|(id, _)| seen.insert(*id));
        found.truncate(limit);
        found
    }

    /// Up to [limit] entries for [tokens], best adjusted score first. Exact readings
    /// score as stored; readings found only by an abbreviated syllable lose
    /// [ABBREVIATION_PENALTY].
    fn ranked(&self, tokens: &[Token<'_>], limit: usize) -> Vec<(u32, f32)> {
        let key = tokens.iter().map(|token| token.body).collect::<Vec<_>>().join(" ");
        let mut out = Vec::new();
        for id in self.by_reading(&key) {
            if out.len() >= limit {
                break;
            }
            let entry = self.entry(id);
            if tones_match(&entry, tokens) {
                out.push((id, entry.score));
            }
        }
        let abbreviated = tokens
            .iter()
            .any(|token| token.tone.is_none() && self.open.contains(token.body));
        if abbreviated {
            let mut taken = 0;
            for id in self.by_initials(&initials_of(&key)) {
                if taken >= limit {
                    break;
                }
                let entry = self.entry(id);
                if entry.reading == key || !starts_match(entry.reading, tokens) {
                    continue;
                }
                if tones_match(&entry, tokens) {
                    out.push((id, entry.score - ABBREVIATION_PENALTY));
                    taken += 1;
                }
            }
            out.sort_by(|left, right| right.1.total_cmp(&left.1));
            out.truncate(limit);
        }
        out
    }

    fn entry(&self, id: u32) -> EntryRef<'_> {
        match (id as usize).checked_sub(self.store.len()) {
            None => self.store.entry(id),
            Some(index) => {
                let learned = &self.learned[index];
                EntryRef {
                    text: &learned.text,
                    reading: &learned.reading,
                    tones: learned.tones,
                    score: USER_SCORE,
                    word: learned.word,
                }
            }
        }
    }

    /// Learned entries first: they all score [USER_SCORE], above every compiled one.
    fn by_reading<'a>(&'a self, key: &str) -> impl Iterator<Item = u32> + 'a {
        let learned = self.learned_by_reading.get(key).into_iter().flatten().copied();
        learned.chain(self.store.by_reading(key))
    }

    fn by_initials<'a>(&'a self, key: &str) -> impl Iterator<Item = u32> + 'a {
        let learned = self.learned_by_initials.get(key).into_iter().flatten().copied();
        learned.chain(self.store.by_initials(key))
    }

    /// `span<TAB>text<TAB>reading` lines, one per candidate.
    fn candidate_lines(&self, code: &str, before: &str) -> String {
        let mut out = String::new();
        for (index, hit) in self.candidates_after(code, before).iter().enumerate() {
            if index > 0 {
                out.push('\n');
            }
            out.push_str(&hit.span.to_string());
            out.push('\t');
            out.push_str(&hit.text);
            out.push('\t');
            out.push_str(&hit.reading);
        }
        out
    }

    pub fn len(&self) -> usize {
        self.store.len() + self.learned.len()
    }
}

/// Entries from the phrase table, then the Rime dictionary rows it lacks.
/// The table rows are `reading<TAB>text<TAB>log10 probability`, one per reading and text,
/// as tools/import-mcbopomofo.py writes them; `#` lines are comments.
/// Dictionary rows that the table already has keep the table score.
pub fn rows(table: &str, dict_yaml: &str) -> Vec<Row> {
    let mut out = Vec::new();
    let mut seen: HashSet<(String, u32, String)> = HashSet::new();
    for line in table.lines() {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut parts = line.split('\t');
        let (Some(reading), Some(text), Some(score)) = (parts.next(), parts.next(), parts.next()) else {
            continue;
        };
        let (Ok(score), Some((key, tones))) = (score.trim().parse::<f32>(), split_reading(reading)) else {
            continue;
        };
        if !text.is_empty() {
            seen.insert((key.clone(), tones, text.to_string()));
            out.push(Row { key, tones, text: text.to_string(), score });
        }
    }
    for (reading, text) in dict_rows(dict_yaml) {
        let Some((key, tones)) = split_reading(reading) else {
            continue;
        };
        if seen.insert((key.clone(), tones, text.to_string())) {
            out.push(Row { key, tones, text: text.to_string(), score: SUPPLEMENT_SCORE });
        }
    }
    out
}

/// The phrase table, Rime dictionary and bigrams in the compiled format
/// `assets/lexicon/lexicon.bin` holds. [bigrams] is `w1<TAB>w2<TAB>log10 P(w2|w1)` lines,
/// as corpus_tools count writes them; `#` lines are comments.
pub fn compile(table: &str, dict_yaml: &str, bigrams: &str) -> Vec<u8> {
    let pairs: Vec<(String, String, f32)> = bigrams
        .lines()
        .filter(|line| !line.starts_with('#'))
        .filter_map(|line| {
            let mut parts = line.split('\t');
            let (left, right, p) = (parts.next()?, parts.next()?, parts.next()?.parse().ok()?);
            Some((left.to_string(), right.to_string(), p))
        })
        .collect();
    store::compile(&rows(table, dict_yaml), &pairs)
}

/// Opens a compiled lexicon and learns the user's `reading<TAB>text` lines on top.
pub fn open(compiled: Vec<u8>, user: &str) -> Result<Lexicon, &'static str> {
    let store = Store::open(compiled)?;
    let open = store.open_bodies().map(Box::from).collect();
    let mut lexicon = Lexicon {
        store,
        learned: Vec::new(),
        learned_by_reading: HashMap::new(),
        learned_by_initials: HashMap::new(),
        open,
        bigram_weight: BIGRAM_WEIGHT,
    };
    for line in user.lines() {
        if let Some((reading, text)) = line.split_once('\t') {
            lexicon.learn(reading, text);
        }
    }
    Ok(lexicon)
}

/// Compiles and opens in one step, without bigrams, for tests and tools.
pub fn build(table: &str, dict_yaml: &str, user: &str) -> Lexicon {
    open(compile(table, dict_yaml, ""), user).expect("a freshly compiled lexicon opens")
}

fn is_han(ch: char) -> bool {
    matches!(ch as u32, 0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF | 0x20000..=0x3134F)
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

fn mark_of(tone: u32) -> &'static str {
    match tone {
        2 => "ˊ",
        3 => "ˇ",
        4 => "ˋ",
        5 => "˙",
        0 => "*",
        _ => "",
    }
}

fn is_initial(ch: char) -> bool {
    ('\u{3105}'..='\u{3119}').contains(&ch)
}

fn is_medial(ch: char) -> bool {
    ('\u{3127}'..='\u{3129}').contains(&ch)
}

fn parse_code(code: &str) -> Vec<Syllable> {
    code.split(' ').filter(|part| !part.is_empty()).map(parse_query).collect()
}

/// `ㄍㄨㄥˋ` is fourth tone, `ㄍㄨㄥ` level tone, `ㄍㄨㄥ*` any tone.
fn parse_query(syllable: &str) -> Syllable {
    if let Some(body) = syllable.strip_suffix('*') {
        return Syllable { body: body.to_string(), tone: None, display: body.to_string() };
    }
    let (body, tone) = split_tone(syllable);
    Syllable { body, tone, display: syllable.to_string() }
}

fn split_tone(syllable: &str) -> (String, Option<u8>) {
    if let Some(body) = syllable.strip_suffix('*') {
        return (body.to_string(), Some(0));
    }
    match syllable.chars().last().and_then(tone_of) {
        Some(tone) => {
            let body: String = syllable.chars().take(syllable.chars().count() - 1).collect();
            (body, Some(tone))
        }
        None => (syllable.to_string(), Some(1)),
    }
}

/// Every way to read [query] as dictionary syllables. An untoned initial+medial syllable
/// such as ㄉㄧ is read both as one syllable and as ㄉ and ㄧ, for initials-only typing.
fn readings(query: &[Syllable]) -> Vec<Vec<Token<'_>>> {
    let splits = query.iter().filter(|syllable| splittable(syllable)).count();
    let mut out: Vec<Vec<Token<'_>>> = vec![Vec::new()];
    for syllable in query {
        let whole = Token { body: syllable.body.as_str(), tone: syllable.tone };
        if splits > MAX_SPLITS || !splittable(syllable) {
            out.iter_mut().for_each(|tokens| tokens.push(whole));
            continue;
        }
        let cut = syllable.body.char_indices().nth(1).map_or(0, |(index, _)| index);
        let (head, tail) = syllable.body.split_at(cut);
        let mut doubled = Vec::with_capacity(out.len() * 2);
        for tokens in out {
            let mut split = tokens.clone();
            split.push(Token { body: head, tone: None });
            split.push(Token { body: tail, tone: None });
            let mut kept = tokens;
            kept.push(whole);
            doubled.push(kept);
            doubled.push(split);
        }
        out = doubled;
    }
    out.retain(|tokens| tokens.len() <= MAX_SYLLABLES);
    out
}

fn splittable(syllable: &Syllable) -> bool {
    let mut chars = syllable.body.chars();
    syllable.tone.is_none()
        && matches!((chars.next(), chars.next(), chars.next()), (Some(a), Some(b), None) if is_initial(a) && is_medial(b))
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

fn reading_of(entry: &EntryRef<'_>) -> String {
    entry
        .reading
        .split(' ')
        .enumerate()
        .map(|(index, body)| format!("{body}{}", mark_of((entry.tones >> (3 * index)) & 0b111)))
        .collect::<Vec<_>>()
        .join(" ")
}

/// Each stored syllable equals a toned token and starts with an untoned one.
fn starts_match(reading: &str, tokens: &[Token<'_>]) -> bool {
    let mut stored = reading.split(' ');
    tokens.iter().all(|token| match stored.next() {
        Some(body) if token.tone.is_some() => body == token.body,
        Some(body) => body.starts_with(token.body),
        None => false,
    }) && stored.next().is_none()
}

/// A stored tone of 0 matches any typed tone; a token without a tone matches any stored one.
/// 一 and 不 change tone before another syllable, and people type either tone, so for
/// those characters the base tone and the changed one match each other.
fn tones_match(entry: &EntryRef<'_>, tokens: &[Token<'_>]) -> bool {
    let mut characters = entry.text.chars();
    tokens.iter().enumerate().all(|(index, token)| {
        let character = characters.next();
        let stored = (entry.tones >> (3 * index)) & 0b111;
        let Some(typed) = token.tone.map(u32::from) else {
            return true;
        };
        stored == 0 || stored == typed || sandhi(character, stored, typed)
    })
}

/// 一 is ㄧ, ㄧˊ before a fourth tone, ㄧˋ before the others; 不 is ㄅㄨˋ, ㄅㄨˊ before a fourth tone.
fn sandhi(character: Option<char>, stored: u32, typed: u32) -> bool {
    match character {
        Some('一') => matches!(stored, 1 | 2 | 4) && matches!(typed, 1 | 2 | 4),
        Some('不') => matches!(stored, 2 | 4) && matches!(typed, 2 | 4),
        _ => false,
    }
}

/// Whether packed tones [wide], where 0 is any tone, include every tone in [narrow].
fn tones_cover(wide: u32, narrow: u32) -> bool {
    (0..MAX_SYLLABLES).all(|index| {
        let want = (wide >> (3 * index)) & 0b111;
        want == 0 || want == (narrow >> (3 * index)) & 0b111
    })
}

mod android {
    use super::{open, LEXICON};
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
    use jni::JNIEnv;

    fn utf8(env: &JNIEnv, bytes: &JByteArray) -> Option<String> {
        let raw = env.convert_byte_array(bytes).ok()?;
        String::from_utf8(raw).ok()
    }

    /// 0 on success, -1 when an argument cannot be read, -2 when the lexicon is malformed.
    #[no_mangle]
    pub extern "system" fn Java_com_fskeyboard_ime_engine_RustLexiconNative_nativeLoad<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        compiled: JByteArray<'local>,
        user: JByteArray<'local>,
    ) -> jint {
        let (Ok(compiled), Some(user)) = (env.convert_byte_array(&compiled), utf8(&env, &user)) else {
            return -1;
        };
        let Ok(lexicon) = open(compiled, &user) else {
            return -2;
        };
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
        before: JString<'local>,
    ) -> jstring {
        let (Ok(key), Ok(before)) = (env.get_string(&code), env.get_string(&before)) else {
            return std::ptr::null_mut();
        };
        let (key, before): (String, String) = (key.into(), before.into());
        let found = match LEXICON.lock() {
            Ok(guard) => guard.as_ref().map(|lexicon| lexicon.candidate_lines(&key, &before)),
            Err(poisoned) => poisoned
                .into_inner()
                .as_ref()
                .map(|lexicon| lexicon.candidate_lines(&key, &before)),
        }
        .unwrap_or_default();
        env.new_string(found)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_fskeyboard_ime_engine_RustLexiconNative_nativeLearn<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        reading: JString<'local>,
        text: JString<'local>,
    ) -> jboolean {
        let (Ok(reading), Ok(text)) = (env.get_string(&reading), env.get_string(&text)) else {
            return JNI_FALSE;
        };
        let (reading, text): (String, String) = (reading.into(), text.into());
        let learned = match LEXICON.lock() {
            Ok(mut guard) => guard.as_mut().is_some_and(|lexicon| lexicon.learn(&reading, &text)),
            Err(poisoned) => poisoned
                .into_inner()
                .as_mut()
                .is_some_and(|lexicon| lexicon.learn(&reading, &text)),
        };
        if learned {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{build, Hit, Lexicon, CANDIDATE_LIMIT};
    use std::path::Path;

    fn project() -> Lexicon {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        let table = std::fs::read_to_string(root.join("data/lexicon/mcbopomofo.tsv")).expect("mcbopomofo.tsv");
        let dict = std::fs::read_to_string(root.join("app/src/main/assets/rime/shared/fs_zhuyin.dict.yaml"))
            .expect("dict");
        build(&table, &dict, "")
    }

    fn texts(hits: &[Hit<'_>]) -> Vec<String> {
        hits.iter().map(|hit| hit.text.to_string()).collect()
    }

    fn pair(hit: &Hit<'_>) -> (usize, String) {
        (hit.span, hit.text.to_string())
    }

    fn sentence(lexicon: &Lexicon, code: &str) -> String {
        lexicon.convert(code).iter().map(|hit| hit.text.to_string()).collect()
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
        let lexicon = build(SAMPLE, "", "");
        let hits = lexicon.candidates("ㄍㄨㄥ* ㄎㄜ*");
        assert_eq!(pair(&hits[0]), (2, "功課".to_string()));
        assert_eq!(texts(&hits[1..]), vec!["公", "工", "貢"]);
        assert!(hits[1..].iter().all(|hit| hit.span == 1));
    }

    #[test]
    fn candidates_after_the_sentence_cover_the_front() {
        let lexicon = build(SAMPLE, "", "");
        let hits = lexicon.candidates("ㄍㄨㄥ ㄊㄧㄢ");
        assert_eq!(texts(&hits), vec!["公天", "公", "工"]);
        assert_eq!(hits[0].span, 2);
        assert_eq!(hits[0].reading, "ㄍㄨㄥ ㄊㄧㄢ");
        assert!(hits[1..].iter().all(|hit| hit.span == 1 && hit.text != "天"));
    }

    #[test]
    fn a_chosen_tone_narrows_and_an_open_one_predicts_every_tone() {
        let lexicon = build(SAMPLE, "", "");
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ")), vec!["公", "工"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥˋ")), vec!["貢"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ*")), vec!["公", "工", "貢"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ ㄎㄜˋ")), vec!["功課", "公", "工"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥˋ ㄎㄜˋ")), vec!["貢課", "貢"]);
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ* ㄎㄜˋ"))[0], "功課");
    }

    #[test]
    fn the_dictionary_adds_missing_rows_below_the_table() {
        let lexicon = build(SAMPLE, DICT, "");
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ")), vec!["公", "工", "攻"]);
        assert_eq!(lexicon.len(), 9);
    }

    #[test]
    fn the_best_split_prefers_phrases_and_keeps_unknown_zhuyin() {
        let lexicon = build(SAMPLE, "", "");
        assert_eq!(sentence(&lexicon, "ㄐㄧㄣ ㄊㄧㄢ ㄍㄨㄥ* ㄎㄜˋ"), "今天功課");
        assert_eq!(lexicon.convert("ㄐㄧㄣ ㄊㄧㄢ ㄍㄨㄥ* ㄎㄜˋ").len(), 2);
        assert_eq!(sentence(&lexicon, "ㄍㄨㄥ ㄅㄚ*"), "公ㄅㄚ");
        assert_eq!(sentence(&lexicon, "ㄍㄨㄥˇ"), "ㄍㄨㄥˇ");
        assert!(lexicon.convert("").is_empty());
    }

    #[test]
    fn a_single_word_conversion_is_not_repeated_at_the_top() {
        let lexicon = build(SAMPLE, "", "");
        let hits = lexicon.candidates("ㄐㄧㄣ ㄊㄧㄢ");
        assert_eq!(pair(&hits[0]), (2, "今天".to_string()));
        assert_eq!(hits.iter().filter(|hit| hit.text == "今天").count(), 1);
    }

    #[test]
    fn an_untoned_syllable_also_matches_longer_ones_below_full_readings() {
        let lexicon = build(SAMPLE, "", "");
        assert_eq!(texts(&lexicon.candidates("ㄐ* ㄊ*"))[0], "今天");
        assert_eq!(texts(&lexicon.candidates("ㄐㄧ* ㄊㄧ*"))[0], "今天");
        // A toned syllable is complete, so ㄍㄨ does not grow into ㄍㄨㄥ.
        assert!(lexicon.candidates("ㄍㄨˋ").is_empty());
        assert_eq!(texts(&lexicon.candidates("ㄍㄨ*")), vec!["公", "工", "貢"]);
    }

    #[test]
    fn an_initial_and_medial_also_read_as_two_abbreviated_syllables() {
        let yaml = "\
ㄉㄧㄢˋ ㄧㄥˇ\t電影\t-4.0
ㄉㄧˋ\t地\t-3.0
ㄉㄧㄢˋ\t電\t-3.2
";
        let lexicon = build(yaml, "", "");
        let hits = lexicon.candidates("ㄉㄧ*");
        // The syllable as typed wins; the two-syllable reading is still offered.
        assert_eq!(pair(&hits[0]), (1, "地".to_string()));
        assert!(hits.iter().any(|hit| hit.span == 1 && hit.text == "電影"));
    }

    #[test]
    fn learned_phrases_rank_first_and_persist_through_build() {
        let lexicon_text = "\
ㄓㄣ ㄉㄜ˙\t真的\t-3.0
ㄐㄧㄚ ㄉㄜ˙\t家的\t-4.0
ㄐㄧㄚˇ ㄉㄜ˙\t假的\t-4.5
ㄐㄧㄚ\t家\t-3.0
ㄐㄧㄚˇ\t假\t-3.5
ㄉㄜ˙\t的\t-1.5
";
        let mut lexicon = build(lexicon_text, "", "");
        assert_eq!(sentence(&lexicon, "ㄓㄣ* ㄉㄜ* ㄐㄧㄚ* ㄉㄜ*"), "真的家的");
        assert!(lexicon.learn("ㄓㄣ ㄉㄜ˙ ㄐㄧㄚˇ ㄉㄜ˙", "真的假的"));
        assert!(!lexicon.learn("ㄓㄣ ㄉㄜ˙ ㄐㄧㄚˇ ㄉㄜ˙", "真的假的"));
        assert_eq!(texts(&lexicon.candidates("ㄓㄣ* ㄉㄜ* ㄐㄧㄚ* ㄉㄜ*"))[0], "真的假的");
        assert_eq!(texts(&lexicon.candidates("ㄓ* ㄉ* ㄐ* ㄉ*"))[0], "真的假的");

        let rebuilt = build(lexicon_text, "", "ㄓㄣ ㄉㄜ˙ ㄐㄧㄚˇ ㄉㄜ˙\t真的假的\n");
        assert_eq!(texts(&rebuilt.candidates("ㄓㄣ* ㄉㄜ* ㄐㄧㄚ* ㄉㄜ*"))[0], "真的假的");
    }

    #[test]
    fn yi_and_bu_match_their_base_and_changed_tones() {
        let yaml = "\
ㄧˊ ㄍㄜ˙\t一個\t-3.0
ㄧ\t衣\t-3.5
ㄅㄨˊ ㄗㄞˋ ㄏㄨ\t不在乎\t-4.0
ㄅㄨˇ\t補\t-3.5
";
        let lexicon = build(yaml, "", "");
        assert_eq!(texts(&lexicon.candidates("ㄧ ㄍㄜ˙"))[0], "一個");
        assert_eq!(texts(&lexicon.candidates("ㄧˋ ㄍㄜ˙"))[0], "一個");
        assert_eq!(texts(&lexicon.candidates("ㄅㄨˋ ㄗㄞˋ ㄏㄨ"))[0], "不在乎");
        // Only 一 and 不 change tone: 衣 stays level, 補 stays third.
        assert!(lexicon.candidates("ㄧˊ").is_empty());
        assert!(lexicon.candidates("ㄅㄨˋ").is_empty());
    }

    #[test]
    fn a_bigram_picks_the_word_that_fits_the_one_before() {
        let table = "\
ㄌㄜ˙\t了\t-2.0
ㄅㄚ\t八\t-3.4
ㄅㄚ\t吧\t-3.8
ㄕㄨㄛ\t說\t-3.0
";
        assert_eq!(sentence(&build(table, "", ""), "ㄕㄨㄛ ㄌㄜ˙ ㄅㄚ"), "說了八");
        let bigrams = "# comment\n了\t吧\t-1.0\n了\t八\t-3.0\n不在\t吧\t-1.0\n";
        let mut lexicon = super::open(super::compile(table, "", bigrams), "").unwrap();
        assert_eq!(sentence(&lexicon, "ㄕㄨㄛ ㄌㄜ˙ ㄅㄚ"), "說了吧");
        // With nothing before it, 八 is still the more common word.
        assert_eq!(sentence(&lexicon, "ㄅㄚ"), "八");
        // The text before the cursor counts: its last word starts the sentence.
        assert_eq!(texts(&lexicon.candidates_after("ㄅㄚ", "我們說了"))[0], "吧");
        assert_eq!(texts(&lexicon.candidates_after("ㄅㄚ", "說了。"))[0], "八");
        lexicon.set_bigram_weight(0.0);
        assert_eq!(sentence(&lexicon, "ㄕㄨㄛ ㄌㄜ˙ ㄅㄚ"), "說了八");
    }

    #[test]
    fn learning_an_existing_row_raises_it() {
        let mut lexicon = build(SAMPLE, "", "");
        assert!(lexicon.learn("ㄍㄨㄥ", "工"));
        assert!(!lexicon.learn("ㄍㄨㄥ", "工"));
        assert_eq!(texts(&lexicon.candidates("ㄍㄨㄥ")), vec!["工", "公"]);
        assert_eq!(lexicon.len(), 9);
    }

    #[test]
    fn a_damaged_lexicon_is_refused_instead_of_read_out_of_bounds() {
        let compiled = super::compile(SAMPLE, "", "");
        assert!(super::open(compiled.clone(), "").is_ok());
        assert!(super::open(Vec::new(), "").is_err());
        assert!(super::open(compiled[..compiled.len() - 1].to_vec(), "").is_err());
        let mut magic = compiled.clone();
        magic[0] = b'X';
        assert!(super::open(magic, "").is_err());
        // The first id in the id block, pointed past the last entry.
        let mut id = compiled.clone();
        let field = |at: usize| u32::from_le_bytes(compiled[at..at + 4].try_into().unwrap()) as usize;
        // Header fields after the magic and version: entries, readings, initials, open, words, pairs.
        let ids_at = 48 + field(8) * 20 + (field(12) + field(16)) * 16 + field(20) * 8 + field(24) * 16 + field(28) * 8;
        id[ids_at..ids_at + 4].copy_from_slice(&999u32.to_le_bytes());
        assert!(super::open(id, "").is_err());
    }

    #[test]
    fn candidate_lines_carry_the_span_and_reading() {
        let lexicon = build(SAMPLE, "", "");
        assert_eq!(
            lexicon.candidate_lines("ㄐㄧㄣ ㄊㄧㄢ", ""),
            "2\t今天\tㄐㄧㄣ ㄊㄧㄢ\n1\t今\tㄐㄧㄣ"
        );
        assert_eq!(lexicon.candidate_lines("ㄅㄚ", ""), "");
    }

    #[test]
    fn the_project_lexicon_finds_common_phrases() {
        let lexicon = project();
        assert_eq!(lexicon.candidates("ㄍㄨㄥ")[0].text, "公");
        assert_eq!(pair(&lexicon.candidates("ㄋㄧˇ ㄏㄠˇ")[0]), (2, "你好".to_string()));
        let today = lexicon.candidates("ㄐㄧㄣ* ㄊㄧㄢ* ㄊㄧㄢ* ㄑㄧ*");
        assert_eq!(pair(&today[0]), (4, "今天天氣".to_string()));
        assert_eq!(today[1].span, 2);
        assert!(today.iter().any(|hit| hit.text == "今天"));
        assert!(today.len() <= CANDIDATE_LIMIT);
        assert!(today.iter().any(|hit| hit.span == 1 && hit.text == "今"));
    }

    #[test]
    fn the_project_lexicon_converts_whole_sentences() {
        let lexicon = project();
        assert_eq!(sentence(&lexicon, "ㄨㄛˇ ㄇㄣ˙ ㄐㄧㄣ ㄊㄧㄢ ㄑㄩˋ ㄎㄢˋ ㄉㄧㄢˋ ㄧㄥˇ"), "我們今天去看電影");
        assert_eq!(sentence(&lexicon, "ㄋㄧˇ ㄎㄢ* ㄎㄢ*"), "你看看");
    }

    #[test]
    fn the_project_lexicon_reads_initials_only_typing() {
        let lexicon = project();
        assert_eq!(sentence(&lexicon, "ㄨ* ㄇ* ㄐ* ㄊ*"), "我們今天");
        assert_eq!(sentence(&lexicon, "ㄅ* ㄓ* ㄉ*"), "不知道");
        let movie = lexicon.candidates("ㄎ* ㄉㄧ*");
        assert!(movie.iter().any(|hit| hit.span == 2 && hit.text == "看電影"));
    }
}
