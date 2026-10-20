//! The compiled lexicon: one byte buffer read in place, with no parsing at load.
//!
//! Built at build time by [compile] from the phrase table and the Rime dictionary,
//! shipped as `assets/lexicon/lexicon.bin`, and opened with [Store::open]. Every
//! number is little-endian. After the header come, in this order:
//!
//! ```text
//! entries   20 bytes each: text offset u32, reading index u32, tones u32, score f32,
//!           text length u16, padding u16
//! readings  16 bytes each, sorted by key: key offset u32, key length u32,
//!           first id u32, id count u32 — the entries with that toneless reading
//! initials  16 bytes each, sorted by key, the same shape — the entries whose
//!           syllables start with those glyphs
//! open       8 bytes each: offset u32, length u32 — syllable bodies that a longer
//!           syllable starts with
//! ids        u32 entry ids; each run is best score first
//! strings    UTF-8 bytes; every offset above is into this block
//! ```
//!
//! [Store::open] checks every range once, so reads after it cannot go out of bounds.
use std::collections::{HashMap, HashSet};

const MAGIC: &[u8; 4] = b"FSLX";
const VERSION: u32 = 1;
const HEADER: usize = 40;
const ENTRY: usize = 20;
const KEY: usize = 16;
const SPAN: usize = 8;

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
}

/// Lays out [rows] in the format above.
pub fn compile(rows: &[Row]) -> Vec<u8> {
    let mut strings: Vec<u8> = Vec::new();
    let intern = |text: &str, strings: &mut Vec<u8>| -> (u32, u32) {
        let at = (strings.len() as u32, text.len() as u32);
        strings.extend_from_slice(text.as_bytes());
        at
    };

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
        reading_table.push((offset, length, ids.len() as u32, members.len() as u32));
        ids.extend_from_slice(members);
    }
    let mut initial_table = Vec::with_capacity(initials.len());
    for (key, members) in initials.iter_mut() {
        order(members);
        let (offset, length) = intern(key, &mut strings);
        initial_table.push((offset, length, ids.len() as u32, members.len() as u32));
        ids.extend_from_slice(members);
    }
    let open_table: Vec<(u32, u32)> = open.iter().map(|body| intern(body, &mut strings)).collect();
    let entry_table: Vec<(u32, u32)> = rows.iter().map(|row| intern(&row.text, &mut strings)).collect();

    let mut out = Vec::new();
    out.extend_from_slice(MAGIC);
    for value in [
        VERSION,
        rows.len() as u32,
        reading_table.len() as u32,
        initial_table.len() as u32,
        open_table.len() as u32,
        ids.len() as u32,
        strings.len() as u32,
    ] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.resize(HEADER, 0);
    for (row, ((offset, length), reading)) in rows.iter().zip(entry_table.iter().zip(&reading_of)) {
        out.extend_from_slice(&offset.to_le_bytes());
        out.extend_from_slice(&reading.to_le_bytes());
        out.extend_from_slice(&row.tones.to_le_bytes());
        out.extend_from_slice(&row.score.to_le_bytes());
        out.extend_from_slice(&(*length as u16).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
    }
    for (offset, length, first, count) in reading_table.iter().chain(&initial_table) {
        for value in [offset, length, first, count] {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
    for (offset, length) in &open_table {
        out.extend_from_slice(&offset.to_le_bytes());
        out.extend_from_slice(&length.to_le_bytes());
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
    ids: usize,
    entries_at: usize,
    readings_at: usize,
    initials_at: usize,
    open_at: usize,
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
        let (entries, readings, initials, open, ids, strings) =
            (field(1), field(2), field(3), field(4), field(5), field(6));
        let entries_at = HEADER;
        let readings_at = entries_at.checked_add(entries.checked_mul(ENTRY).ok_or("too big")?).ok_or("too big")?;
        let initials_at = readings_at + readings.checked_mul(KEY).ok_or("too big")?;
        let open_at = initials_at + initials.checked_mul(KEY).ok_or("too big")?;
        let ids_at = open_at + open.checked_mul(SPAN).ok_or("too big")?;
        let strings_at = ids_at + ids.checked_mul(4).ok_or("too big")?;
        if strings_at.checked_add(strings) != Some(data.len()) {
            return Err("lexicon size does not match its header");
        }
        let store = Store {
            data,
            entries,
            readings,
            initials,
            open,
            ids,
            entries_at,
            readings_at,
            initials_at,
            open_at,
            ids_at,
            strings_at,
        };
        store.check(strings)?;
        Ok(store)
    }

    fn check(&self, strings: usize) -> Result<(), &'static str> {
        let text_ok = |offset: usize, length: usize| {
            offset.checked_add(length).is_some_and(|end| end <= strings)
                && std::str::from_utf8(&self.data[self.strings_at + offset..self.strings_at + offset + length]).is_ok()
        };
        for id in 0..self.entries {
            let at = self.entries_at + id * ENTRY;
            let length = u16::from_le_bytes(self.data[at + 16..at + 18].try_into().unwrap()) as usize;
            if !text_ok(self.u32_at(at), length) || self.u32_at(at + 4) >= self.readings {
                return Err("bad entry");
            }
        }
        for (table, count) in [(self.readings_at, self.readings), (self.initials_at, self.initials)] {
            for index in 0..count {
                let at = table + index * KEY;
                let (first, members) = (self.u32_at(at + 8), self.u32_at(at + 12));
                if !text_ok(self.u32_at(at), self.u32_at(at + 4))
                    || first.checked_add(members).map_or(true, |end| end > self.ids)
                {
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
        if (0..self.ids).any(|index| self.u32_at(self.ids_at + 4 * index) >= self.entries) {
            return Err("bad id");
        }
        Ok(())
    }

    pub fn len(&self) -> usize {
        self.entries
    }

    pub fn entry(&self, id: u32) -> EntryRef<'_> {
        let at = self.entries_at + id as usize * ENTRY;
        let length = u16::from_le_bytes(self.data[at + 16..at + 18].try_into().unwrap()) as usize;
        let reading = self.readings_at + self.u32_at(at + 4) * KEY;
        EntryRef {
            text: self.text(self.u32_at(at), length),
            reading: self.text(self.u32_at(reading), self.u32_at(reading + 4)),
            tones: self.u32_at(at + 8) as u32,
            score: f32::from_le_bytes(self.data[at + 12..at + 16].try_into().unwrap()),
        }
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

    fn members(&self, table: usize, count: usize, key: &str) -> impl Iterator<Item = u32> + '_ {
        let found = self.find(table, count, key);
        let (first, length) = found.map_or((0, 0), |at| (self.u32_at(at + 8), self.u32_at(at + 12)));
        (first..first + length).map(move |index| self.u32_at(self.ids_at + 4 * index) as u32)
    }

    /// Binary search over a sorted key table; the record's position.
    fn find(&self, table: usize, count: usize, key: &str) -> Option<usize> {
        let (mut low, mut high) = (0, count);
        while low < high {
            let middle = (low + high) / 2;
            let at = table + middle * KEY;
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

    fn bytes(&self, offset: usize, length: usize) -> &[u8] {
        let start = self.strings_at + offset;
        &self.data[start..start + length]
    }

    /// Only for ranges that [Store::check] read: an entry's text, a key, an open body.
    fn text(&self, offset: usize, length: usize) -> &str {
        // SAFETY: open() runs check(), which confirms every text, key and open-body
        // range is inside the string block and valid UTF-8, and `data` is never changed
        // after that. Every caller passes one of those checked ranges.
        unsafe { std::str::from_utf8_unchecked(self.bytes(offset, length)) }
    }
}
