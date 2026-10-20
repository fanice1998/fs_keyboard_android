#!/usr/bin/env python3
"""Build assets/lexicon/english.tsv from the Leipzig English news word list.

The corpus is CC BY (https://wortschatz.uni-leipzig.de). The download stays in
data/corpus, which git ignores. The committed file is word frequencies only.
data/lexicon/english_extra.txt adds technical words and raises rare ones to
the count of the 5000th news word.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "data" / "corpus" / "eng_news_2024_300K"
EXTRA = ROOT / "data" / "lexicon" / "english_extra.txt"
OUT = ROOT / "app" / "src" / "main" / "assets" / "lexicon" / "english.tsv"

MIN_COUNT = 5
MIN_SHORT = 100
MAX_WORDS = 40_000
BOOST_RANK = 5_000
WORD = re.compile(r"[a-z]+(?:'[a-z]+)?")
EXTRA_WORD = re.compile(r"[a-z0-9]+(?:[-'][a-z0-9]+)*")
KEEP_SINGLE = {"a", "i"}


def word_list() -> Path:
    found = sorted(CORPUS.glob("*-words.txt"))
    if not found:
        sys.exit(f"missing words under {CORPUS}; run tools/build-english.sh")
    return found[0]


def news_counts(path: Path) -> Counter[str]:
    counts: Counter[str] = Counter()
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            word = parts[1].replace("\u2019", "'").replace("\u2018", "'").lower()
            if WORD.fullmatch(word) is None:
                continue
            if len(word) == 1 and word not in KEEP_SINGLE:
                continue
            if len(word) > 30:
                continue
            try:
                count = int(parts[2])
            except ValueError:
                continue
            if count > 0:
                counts[word] += count
    return counts


def extras() -> list[str]:
    words: list[str] = []
    seen: set[str] = set()
    for line in EXTRA.read_text(encoding="utf-8").splitlines():
        word = line.split("#", 1)[0].strip().lower()
        if not word or word in seen:
            continue
        if EXTRA_WORD.fullmatch(word) is None:
            sys.exit(f"not a word: {word}")
        seen.add(word)
        words.append(word)
    return words


def main() -> None:
    counts = news_counts(word_list())
    ranked = sorted(
        (
            (word, count)
            for word, count in counts.items()
            if count >= MIN_COUNT and (len(word) > 2 or word in KEEP_SINGLE or count >= MIN_SHORT)
        ),
        key=lambda item: (-item[1], item[0]),
    )[:MAX_WORDS]
    if not ranked:
        sys.exit("no words passed the count filter")
    floor = ranked[min(BOOST_RANK, len(ranked)) - 1][1]
    merged = dict(ranked)
    added = 0
    raised = 0
    for word in extras():
        current = merged.get(word, 0)
        if current == 0:
            merged[word] = floor
            added += 1
        elif current < floor:
            merged[word] = floor
            raised += 1
    ordered = sorted(merged.items(), key=lambda item: (-item[1], item[0]))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as handle:
        handle.write("# Leipzig eng_news_2024_300K (CC BY) word counts, plus english_extra.txt\n")
        for word, count in ordered:
            handle.write(f"{word}\t{count}\n")
    print(
        f"words={len(ordered)} news={len(ranked)} floor={floor} "
        f"added={added} raised={raised} -> {OUT}"
    )


if __name__ == "__main__":
    main()
