#!/usr/bin/env python3
"""Convert the downloaded corpora to Taiwan Traditional Chinese and write the review lists.

Run after tools/fetch-corpus.sh, with a Python that has the `opencc` package:

    cargo build --release --manifest-path engine/corpus_tools/Cargo.toml
    python -I tools/corpus/prepare.py

Writes the converted and segmented text to data/corpus/work/ (not in git) and three
lists to data/review/ for a person to check, most frequent first:

  a_conversion.tsv  words holding a simplified character with more than one Traditional
                    form, as converted, so a wrong pick (发 → 發 or 髮) can be corrected;
                    a result the Traditional corpus uses often enough is taken as right
  b_mainland.tsv    words common in the simplified sources and rare in the Traditional
                    one, so a mainland term (視頻) can be replaced or dropped
  c_testset.tsv     Tatoeba sentences with zhuyin typed three ways: full, untoned, and
                    initials only; the answer key for measuring conversion accuracy

Fill in the last column (判定) of each list. Nothing here edits the corpora themselves.
"""
import collections
import pathlib
import random
import subprocess
import sys

import opencc

ROOT = pathlib.Path(__file__).resolve().parents[2]
CORPUS = ROOT / "data" / "corpus"
WORK = CORPUS / "work"
REVIEW = ROOT / "data" / "review"
TABLE = ROOT / "data" / "lexicon" / "mcbopomofo.tsv"
TOOL = ROOT / "engine" / "corpus_tools" / "target" / "release" / "corpus_tools"
ST_CHARACTERS = CORPUS / "opencc" / "STCharacters.txt"

# name, script, OpenCC config. Traditional text only needs Taiwan variants.
SOURCES = [
    ("zho-trad_newscrawl_2011_1M", "trad", "t2tw"),
    ("zho_wikipedia_2018_1M", "mixed", "s2twp"),
    ("zho-cn_web_2015_1M", "simp", "s2twp"),
    ("zho_news_2020_300K", "simp", "s2twp"),
]

CONVERSION_ROWS = 2000
MAINLAND_ROWS = 1000
TEST_ROWS = 500
MAINLAND_MIN_COUNT = 30
MAINLAND_RATIO = 20.0
# A converted word the Traditional corpus has this often is taken as correct.
TRUSTED_COUNT = 5
TONES = "ˊˇˋ˙"


def sentences(name):
    """Sentence text of a Leipzig corpus: `id<TAB>sentence` per line."""
    path = CORPUS / name / f"{name}-sentences.txt"
    with open(path, encoding="utf-8") as rows:
        for row in rows:
            parts = row.rstrip("\n").split("\t", 1)
            if len(parts) == 2 and parts[1]:
                yield parts[1].replace("\r", " ")


def convert(name, config):
    """Writes <name>.src.txt and <name>.tw.txt, aligned line by line."""
    src_path, tw_path = WORK / f"{name}.src.txt", WORK / f"{name}.tw.txt"
    if tw_path.exists():
        return src_path, tw_path
    converter = opencc.OpenCC(config)
    with open(src_path, "w", encoding="utf-8") as src, open(tw_path, "w", encoding="utf-8") as tw:
        for count, sentence in enumerate(sentences(name), 1):
            src.write(sentence + "\n")
            tw.write(converter.convert(sentence) + "\n")
            if count % 200_000 == 0:
                print(f"  {name}: {count} converted", flush=True)
    return src_path, tw_path


def segment(tw_path, command="segment"):
    out_path = tw_path.with_suffix(f".{command}.txt")
    if not out_path.exists():
        with open(tw_path, "rb") as source, open(out_path, "wb") as target:
            subprocess.run([str(TOOL), command, str(TABLE)], stdin=source, stdout=target, check=True)
    return out_path


def spans(tokens):
    """(start, end, word) of each word token; boundary tokens are runs of `|`."""
    position = 0
    for token in tokens:
        end = position + len(token)
        if not token.startswith("|"):
            yield position, end, token
        position = end


def ambiguous_characters():
    """Simplified characters that OpenCC maps to more than one Traditional character."""
    found = set()
    with open(ST_CHARACTERS, encoding="utf-8") as rows:
        for row in rows:
            parts = row.rstrip("\n").split("\t")
            if len(parts) == 2 and len(parts[1].split(" ")) > 1:
                found.add(parts[0])
    return found


def keep_short(examples, key, sentence, limit=2):
    """Keeps the [limit] shortest example sentences, which are the quickest to read."""
    found = examples[key]
    if sentence in found:
        return
    found.append(sentence)
    found.sort(key=len)
    del found[limit:]


def conversion_list(converted, ambiguous, trad_counts):
    counts = collections.Counter()
    examples = collections.defaultdict(list)
    for name, script, src_path, tw_path, words_path in converted:
        if script == "trad":
            continue
        with open(src_path, encoding="utf-8") as srcs, open(tw_path, encoding="utf-8") as tws, \
                open(words_path, encoding="utf-8") as words:
            for src, tw, line in zip(srcs, tws, words):
                src, tw = src.rstrip("\n"), tw.rstrip("\n")
                if len(src) != len(tw) or not any(ch in ambiguous for ch in src):
                    continue
                for start, end, word in spans(line.split()):
                    original = src[start:end]
                    if original == word or not any(ch in ambiguous for ch in original):
                        continue
                    if trad_counts.get(word, 0) >= TRUSTED_COUNT:
                        continue
                    key = (original, word)
                    counts[key] += 1
                    keep_short(examples, key, tw)
    rows = [("簡體", "轉換結果", "次數", "繁體來源次數", "例句1", "例句2", "判定")]
    for (original, word), count in counts.most_common(CONVERSION_ROWS):
        sample = examples[(original, word)] + ["", ""]
        rows.append((original, word, str(count), str(trad_counts.get(word, 0)), sample[0], sample[1], ""))
    return rows


def word_counts(paths):
    """Word counts, plus two adjacent single characters joined, since a mainland term
    the phrase table lacks is split into characters."""
    counts = collections.Counter()
    examples = collections.defaultdict(list)
    for words_path, tw_path in paths:
        with open(words_path, encoding="utf-8") as words, open(tw_path, encoding="utf-8") as tws:
            for line, tw in zip(words, tws):
                tokens = [token for token in line.split() if not token.startswith("|")]
                for index, token in enumerate(tokens):
                    counts[token] += 1
                    if len(token) == 1 and index + 1 < len(tokens) and len(tokens[index + 1]) == 1:
                        pair = token + tokens[index + 1]
                        counts[pair] += 1
                        if counts[pair] <= 50:
                            keep_short(examples, pair, tw.rstrip("\n"), limit=1)
                    if len(token) > 1 and counts[token] <= 50:
                        keep_short(examples, token, tw.rstrip("\n"), limit=1)
    return counts, examples


def mainland_list(converted, trad_counts):
    simp = [(w, t) for _, script, _, t, w in converted if script == "simp"]
    simp_counts, examples = word_counts(simp)
    trad_total = sum(trad_counts.values()) or 1
    simp_total = sum(simp_counts.values()) or 1
    found = []
    for word, count in simp_counts.items():
        if len(word) < 2 or count < MAINLAND_MIN_COUNT:
            continue
        simp_rate = count / simp_total
        trad_rate = trad_counts.get(word, 0) / trad_total
        if trad_rate == 0 or simp_rate / trad_rate >= MAINLAND_RATIO:
            found.append((count, word))
    found.sort(reverse=True)
    rows = [("詞", "簡體來源次數", "繁體來源次數", "例句", "判定")]
    for count, word in found[:MAINLAND_ROWS]:
        sample = examples.get(word) or [""]
        rows.append((word, str(count), str(trad_counts.get(word, 0)), sample[0], ""))
    return rows


def test_list():
    """Tatoeba sentences already in Traditional characters, 4 to 20 Han characters."""
    to_tw = opencc.OpenCC("s2twp")
    to_simp = opencc.OpenCC("t2s")
    picked = []
    with open(CORPUS / "tatoeba" / "cmn_sentences.tsv", encoding="utf-8") as rows:
        for row in rows:
            parts = row.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            sentence_id, _, sentence = parts
            han = sum(1 for ch in sentence if "一" <= ch <= "鿿")
            if not 4 <= han <= 20 or to_tw.convert(sentence) != sentence or to_simp.convert(sentence) == sentence:
                continue
            picked.append((sentence_id, sentence))
    random.Random(7).shuffle(picked)
    source = WORK / "tatoeba.tw.txt"
    source.write_text("".join(sentence + "\n" for _, sentence in picked), encoding="utf-8")
    annotated = segment(source, "annotate").read_text(encoding="utf-8").splitlines()
    rows = [("Tatoeba 編號", "句子", "斷詞", "注音", "不打聲調", "只打聲母", "判定")]
    for (sentence_id, sentence), line in zip(picked, annotated):
        if len(rows) > TEST_ROWS:
            break
        words, syllables = [], []
        for token in line.split():
            if token.startswith("|"):
                continue
            word, reading = token.rsplit("/", 1)
            words.append(word)
            parts = reading.split("-")
            if len(parts) == len(word):
                # People type 一 as ㄧ whatever tone it takes in speech.
                parts = ["ㄧ" if ch == "一" else part for ch, part in zip(word, parts)]
            syllables.extend(parts)
        if "?" in syllables:
            continue
        untoned = [s.rstrip(TONES) for s in syllables]
        initials = [s[0] for s in untoned]
        rows.append((sentence_id, sentence, " ".join(words), " ".join(syllables),
                     " ".join(untoned), " ".join(initials), ""))
    return rows


def write(name, rows, key_columns):
    """Writes a list. A row already decided keeps everything a reviewer wrote in it,
    including a corrected reading on the test set."""
    path = REVIEW / name
    decided = {}
    if path.exists():
        with open(path, encoding="utf-8") as old:
            for line in list(old)[1:]:
                cells = tuple(line.rstrip("\n").split("\t"))
                if len(cells) == len(rows[0]) and cells[-1]:
                    decided[tuple(cells[i] for i in key_columns)] = cells
    rows = [rows[0]] + [decided.get(tuple(row[i] for i in key_columns), row) for row in rows[1:]]
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        for row in rows:
            out.write("\t".join(cell.replace("\t", " ") for cell in row) + "\n")
    print(f"{path.relative_to(ROOT)}: {len(rows) - 1} rows")


def main():
    if not TOOL.exists():
        sys.exit("build engine/corpus_tools first: cargo build --release --manifest-path engine/corpus_tools/Cargo.toml")
    WORK.mkdir(parents=True, exist_ok=True)
    REVIEW.mkdir(parents=True, exist_ok=True)
    converted = []
    for name, script, config in SOURCES:
        print(f"{name}: convert with {config}", flush=True)
        src_path, tw_path = convert(name, config)
        print(f"{name}: segment", flush=True)
        converted.append((name, script, src_path, tw_path, segment(tw_path)))
    trad_counts, _ = word_counts([(w, t) for _, script, _, t, w in converted if script == "trad"])
    write("a_conversion.tsv", conversion_list(converted, ambiguous_characters(), trad_counts), (0, 1))
    write("b_mainland.tsv", mainland_list(converted, trad_counts), (0,))
    write("c_testset.tsv", test_list(), (0,))


if __name__ == "__main__":
    main()
