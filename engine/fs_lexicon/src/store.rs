//! The compiled lexicon: one byte buffer read in place, with no parsing at load.
//!
//! Built at build time by [compile] from the phrase table, the Rime dictionary and the
//! bigram counts, shipped as `assets/lexicon/lexicon.bin`, and opened with [Store::open].
//! Every number is little-endian. After the header come, in this order:
//!
//! ```text
//! entries   20 bytes each: word u32, reading u32, tones u32, score f32, padding u32
//! readings  16 bytes each, sorted by key: key offset u32, key length u32,
//!           first id u32, id count u32 — the entries with that toneless reading
//! initials  16 bytes each, sorted by key, the same shape — the entries whose
//!           syllables start with those glyphs
//! open       8 bytes each: offset u32, length u32 — syllable bodies that a longer
//!           syllable starts with
//! words     16 bytes each, sorted by text: offset u32, length u32, first pair u32,
//!           pair count u32 — every entry text, and <s> for the sentence start
//! pairs      8 bytes each: next word u32, log10 P(next|word) f32 — each word's run
//!           sorted by next word
//! ids        u32 entry ids; each run is best score first
//! strings    UTF-8 bytes; every offset above is into this block
//! ```
//!
//! [Store::open] checks every range once, so reads after it cannot go out of bounds.
use std::collections::{HashMap, HashSet};

const MAGIC: &[u8; 4] = b"FSLX";
const VERSION: u32 = 2;
const HEADER: usize = 48;
const ENTRY: usize = 20;
const KEY: usize = 16;
const SPAN: usize = 8;
const WORD: usize = 16;
const PAIR: usize = 8;

/// The left word of the first pair in a sentence.
pub const START: &str = "<s>";

/// One entry before compiling: toneless reading, packed tones, text and score.
pub struct Row {
    pub key: String,
    pub tones: u32,
    pub text: String,
    pub score: f32,
}

/// An entry as stored.
#[derive(Clone, Copy, Debug)]
pub struct EntryRef<'a> {
    pub text: &'a str,
    pub reading: &'a str,
    pub tones: u32,
    pub score: f32,
    /// Its text in the word table, for bigrams. None for a learned text the table lacks.
    pub word: Option<u32>,
}

/// Lays out [rows] and [bigrams], given as (word, next word, log10 probability), in the
/// format above. A pair whose words are not entry texts (or <s> on the left) is dropped.
pub fn compile(rows: &[Row], bigrams: &[(String, String, f32)]) -> Vec<u8> {
    let mut strings: Vec<u8> = Vec::new();
    let intern = |text: &str, strings: &mut Vec<u8>| -> (u32, u32) {
        let at = (strings.len() as u32, text.len() as u32);
        strings.extend_from_slice(text.as_bytes());
        at
    };

    let mut texts: Vec<&str> = rows.iter().map(|row| row.text.as_str()).chain([START]).collect();
    texts.sort_unstable();
    texts.dedup();
    let word_of: HashMap<&str, u32> = texts.iter().enumerate().map(|(id, text)| (*text, id as u32)).collect();
    let mut runs: Vec<Vec<(u32, f32)>> = vec![Vec::new(); texts.len()];
    for (left, right, p) in bigrams {
        if let (Some(&left), Some(&right)) = (word_of.get(left.as_str()), word_of.get(right.as_str())) {
            runs[left as usize].push((right, *p));
        }
    }
    for run in &mut runs {
        run.sort_by_key(|(right, _)| *right);
        run.dedup_by_key(|(right, _)| *right);
    }

    let order = |ids: &mut Vec<u32>| ids.sort_by(|a, b| rows[*b as usize].score.total_cmp(&rows[*a as usize].score));
    let mut by_reading: HashMap<&str, Vec<u32>> = HashMap::new();
    let mut by_initials: HashMap<String, Vec<u32>> = HashMap::new();
    let mut open: HashSet<&str> = HashSet::new();
    for (id, row) in rows.iter().enumerate() {
        by_reading.entry(row.key.as_str()).or_default().push(id as u32);
        by_initials.entry(initials_of(&row.key)).or_default().push(id as u32);
        for syllable in row.key.split(' ') {
            for (index, _) in syllable.char_indices().skip(1) {
                open.insert(&syllable[..index]);
            }
        }
    }
    let mut readings: Vec<(&str, Vec<u32>)> = by_reading.into_iter().collect();
    readings.sort_by(|a, b| a.0.cmp(b.0));
    let mut initials: Vec<(String, Vec<u32>)> = by_initials.into_iter().collect();
    initials.sort_by(|a, b| a.0.cmp(&b.0));
    let mut open: Vec<&str> = open.into_iter().collect();
    open.sort();

    let mut reading_of = vec![0u32; rows.len()];
    let mut ids: Vec<u32> = Vec::new();
    let mut reading_table = Vec::with_capacity(readings.len());
    for (index, (key, members)) in readings.iter_mut().enumerate() {
        order(members);
        for &id in members.iter() {
            reading_of[id as usize] = index as u32;
        }
        let (offset, length) = intern(key, &mut strings);
        reading_table.push([offset, length, ids.len() as u32, members.len() as u32]);
        ids.extend_from_slice(members);
    }
    let mut initial_table = Vec::with_capacity(initials.len());
    for (key, members) in initials.iter_mut() {
        order(members);
        let (offset, length) = intern(key, &mut strings);
        initial_table.push([offset, length, ids.len() as u32, members.len() as u32]);
        ids.extend_from_slice(members);
    }
    let open_table: Vec<(u32, u32)> = open.iter().map(|body| intern(body, &mut strings)).collect();
    let mut pairs: Vec<(u32, f32)> = Vec::new();
    let mut word_table = Vec::with_capacity(texts.len());
    for (text, run) in texts.iter().zip(&runs) {
        let (offset, length) = intern(text, &mut strings);
        word_table.push([offset, length, pairs.len() as u32, run.len() as u32]);
        pairs.extend_from_slice(run);
    }

    let mut out = Vec::new();
    out.extend_from_slice(MAGIC);
    for value in [
        VERSION,
        rows.len() as u32,
        reading_table.len() as u32,
        initial_table.len() as u32,
        open_table.len() as u32,
        word_table.len() as u32,
        pairs.len() as u32,
        ids.len() as u32,
        strings.len() as u32,
    ] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.resize(HEADER, 0);
    for (row, reading) in rows.iter().zip(&reading_of) {
        out.extend_from_slice(&word_of[row.text.as_str()].to_le_bytes());
        out.extend_from_slice(&reading.to_le_bytes());
        out.extend_from_slice(&row.tones.to_le_bytes());
        out.extend_from_slice(&row.score.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
    }
    for record in reading_table.iter().chain(&initial_table) {
        for value in record {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
    for (offset, length) in &open_table {
        out.extend_from_slice(&offset.to_le_bytes());
        out.extend_from_slice(&length.to_le_bytes());
    }
    for record in &word_table {
        for value in record {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
    for (right, p) in &pairs {
        out.extend_from_slice(&right.to_le_bytes());
        out.extend_from_slice(&p.to_le_bytes());
    }
    for id in &ids {
        out.extend_from_slice(&id.to_le_bytes());
    }
    out.extend_from_slice(&strings);
    out
}

/// The first glyph of each syllable, joined by a space.
pub fn initials_of(key: &str) -> String {
    key.split(' ')
        .filter_map(|syllable| syllable.chars().next())
        .map(String::from)
        .collect::<Vec<_>>()
        .join(" ")
}

/// A compiled lexicon, read in place.
pub struct Store {
    data: Vec<u8>,
    entries: usize,
    readings: usize,
    initials: usize,
    open: usize,
    words: usize,
    pairs: usize,
    ids: usize,
    entries_at: usize,
    readings_at: usize,
    initials_at: usize,
    open_at: usize,
    words_at: usize,
    pairs_at: usize,
    ids_at: usize,
    strings_at: usize,
}

impl Store {
    /// Checks the header and every range in [data], so later reads stay in bounds.
    pub fn open(data: Vec<u8>) -> Result<Store, &'static str> {
        if data.len() < HEADER || &data[..4] != MAGIC {
            return Err("not a lexicon");
        }
        let field = |index: usize| u32::from_le_bytes(data[4 + 4 * index..8 + 4 * index].try_into().unwrap()) as usize;
        if field(0) != VERSION as usize {
            return Err("unknown lexicon version");
        }
        let counts = [field(1), field(2), field(3), field(4), field(5), field(6), field(7)];
        let sizes = [ENTRY, KEY, KEY, SPAN, WORD, PAIR, 4];
        let mut at = [0usize; 8];
        at[0] = HEADER;
        for index in 0..7 {
            let size = counts[index].checked_mul(sizes[index]).ok_or("too big")?;
            at[index + 1] = at[index].checked_add(size).ok_or("too big")?;
        }
        let strings = field(8);
        if at[7].checked_add(strings) != Some(data.len()) {
            return Err("lexicon size does not match its header");
        }
        let store = Store {
            data,
            entries: counts[0],
            readings: counts[1],
            initials: counts[2],
            open: counts[3],
            words: counts[4],
            pairs: counts[5],
            ids: counts[6],
            entries_at: at[0],
            readings_at: at[1],
            initials_at: at[2],
            open_at: at[3],
            words_at: at[4],
            pairs_at: at[5],
            ids_at: at[6],
            strings_at: at[7],
        };
        store.check(strings)?;
        Ok(store)
    }

    fn check(&self, strings: usize) -> Result<(), &'static str> {
        let text_ok = |offset: usize, length: usize| {
            offset.checked_add(length).is_some_and(|end| end <= strings)
                && std::str::from_utf8(&self.data[self.strings_at + offset..self.strings_at + offset + length]).is_ok()
        };
        let run_ok = |first: usize, count: usize, limit: usize| first.checked_add(count).is_some_and(|end| end <= limit);
        for id in 0..self.entries {
            let at = self.entries_at + id * ENTRY;
            if self.u32_at(at) >= self.words || self.u32_at(at + 4) >= self.readings {
                return Err("bad entry");
            }
        }
        for (table, count) in [(self.readings_at, self.readings), (self.initials_at, self.initials)] {
            for index in 0..count {
                let at = table + index * KEY;
                if !text_ok(self.u32_at(at), self.u32_at(at + 4)) || !run_ok(self.u32_at(at + 8), self.u32_at(at + 12), self.ids) {
                    return Err("bad key");
                }
            }
        }
        for index in 0..self.open {
            let at = self.open_at + index * SPAN;
            if !text_ok(self.u32_at(at), self.u32_at(at + 4)) {
                return Err("bad open syllable");
            }
        }
        for index in 0..self.words {
            let at = self.words_at + index * WORD;
            if !text_ok(self.u32_at(at), self.u32_at(at + 4)) || !run_ok(self.u32_at(at + 8), self.u32_at(at + 12), self.pairs) {
                return Err("bad word");
            }
        }
        if (0..self.pairs).any(|index| self.u32_at(self.pairs_at + index * PAIR) >= self.words) {
            return Err("bad pair");
        }
        if (0..self.ids).any(|index| self.u32_at(self.ids_at + 4 * index) >= self.entries) {
            return Err("bad id");
        }
        Ok(())
    }

    pub fn len(&self) -> usize {
        self.entries
    }

    pub fn pair_count(&self) -> usize {
        self.pairs
    }

    pub fn entry(&self, id: u32) -> EntryRef<'_> {
        let at = self.entries_at + id as usize * ENTRY;
        let word = self.u32_at(at);
        let reading = self.readings_at + self.u32_at(at + 4) * KEY;
        EntryRef {
            text: self.word_text(word),
            reading: self.text(self.u32_at(reading), self.u32_at(reading + 4)),
            tones: self.u32_at(at + 8) as u32,
            score: self.f32_at(at + 12),
            word: Some(word as u32),
        }
    }

    /// The word table id of [text], if any entry has that text.
    pub fn word(&self, text: &str) -> Option<u32> {
        self.find(self.words_at, self.words, WORD, text).map(|at| ((at - self.words_at) / WORD) as u32)
    }

    /// log10 P(next|word), when the pair was seen often enough to be kept.
    pub fn bigram(&self, word: u32, next: u32) -> Option<f32> {
        let at = self.words_at + word as usize * WORD;
        let (mut low, mut high) = (self.u32_at(at + 8), self.u32_at(at + 8) + self.u32_at(at + 12));
        while low < high {
            let middle = (low + high) / 2;
            let pair = self.pairs_at + middle * PAIR;
            match (self.u32_at(pair) as u32).cmp(&next) {
                std::cmp::Ordering::Less => low = middle + 1,
                std::cmp::Ordering::Greater => high = middle,
                std::cmp::Ordering::Equal => return Some(self.f32_at(pair + 4)),
            }
        }
        None
    }

    /// Entries with toneless reading [key], best score first.
    pub fn by_reading(&self, key: &str) -> impl Iterator<Item = u32> + '_ {
        self.members(self.readings_at, self.readings, key)
    }

    /// Entries whose syllables start with the glyphs in [key], best score first.
    pub fn by_initials(&self, key: &str) -> impl Iterator<Item = u32> + '_ {
        self.members(self.initials_at, self.initials, key)
    }

    /// Syllable bodies that a longer syllable starts with.
    pub fn open_bodies(&self) -> impl Iterator<Item = &str> + '_ {
        (0..self.open).map(move |index| {
            let at = self.open_at + index * SPAN;
            self.text(self.u32_at(at), self.u32_at(at + 4))
        })
    }

    fn word_text(&self, word: usize) -> &str {
        let at = self.words_at + word * WORD;
        self.text(self.u32_at(at), self.u32_at(at + 4))
    }

    fn members(&self, table: usize, count: usize, key: &str) -> impl Iterator<Item = u32> + '_ {
        let found = self.find(table, count, KEY, key);
        let (first, length) = found.map_or((0, 0), |at| (self.u32_at(at + 8), self.u32_at(at + 12)));
        (first..first + length).map(move |index| self.u32_at(self.ids_at + 4 * index) as u32)
    }

    /// Binary search over a table sorted by the text its records start with; the record's position.
    fn find(&self, table: usize, count: usize, size: usize, key: &str) -> Option<usize> {
        let (mut low, mut high) = (0, count);
        while low < high {
            let middle = (low + high) / 2;
            let at = table + middle * size;
            match self.bytes(self.u32_at(at), self.u32_at(at + 4)).cmp(key.as_bytes()) {
                std::cmp::Ordering::Less => low = middle + 1,
                std::cmp::Ordering::Greater => high = middle,
                std::cmp::Ordering::Equal => return Some(at),
            }
        }
        None
    }

    fn u32_at(&self, at: usize) -> usize {
        u32::from_le_bytes(self.data[at..at + 4].try_into().unwrap()) as usize
    }

    fn f32_at(&self, at: usize) -> f32 {
        f32::from_le_bytes(self.data[at..at + 4].try_into().unwrap())
    }

    fn bytes(&self, offset: usize, length: usize) -> &[u8] {
        let start = self.strings_at + offset;
        &self.data[start..start + length]
    }

    /// Only for ranges that [Store::check] read: a key, an open body, a word.
    fn text(&self, offset: usize, length: usize) -> &str {
        // SAFETY: open() runs check(), which confirms every key, open-body and word
        // range is inside the string block and valid UTF-8, and `data` is never changed
        // after that. Every caller passes one of those checked ranges.
        unsafe { std::str::from_utf8_unchecked(self.bytes(offset, length)) }
    }
}
