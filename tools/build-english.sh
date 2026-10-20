#!/usr/bin/env bash
# Download the Leipzig English news sample and write assets/lexicon/english.tsv.
# The corpus is CC BY and stays in data/corpus, which git ignores.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME=eng_news_2024_300K
OUT="$ROOT/data/corpus"
mkdir -p "$OUT"

if [[ ! -d "$OUT/$NAME" ]]; then
  echo "fetch $NAME"
  curl -fL --retry 3 -o "$OUT/$NAME.tar.gz.partial" \
    "https://downloads.wortschatz-leipzig.de/corpora/$NAME.tar.gz"
  mv "$OUT/$NAME.tar.gz.partial" "$OUT/$NAME.tar.gz"
  tar -xzf "$OUT/$NAME.tar.gz" -C "$OUT"
  rm "$OUT/$NAME.tar.gz"
fi

python3 "$ROOT/tools/build-english.py"
