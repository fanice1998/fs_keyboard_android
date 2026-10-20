#!/usr/bin/env bash
# Download the text corpora for the bigram model into data/corpus, which git ignores.
#
# Leipzig Corpora Collection (https://wortschatz.uni-leipzig.de), downloads under CC BY.
#   zho-trad_newscrawl_2011_1M  Traditional Chinese news, the main source
#   zho_wikipedia_2018_1M       Chinese Wikipedia, mixed scripts
#   zho-cn_web_2015_1M          Simplified web text, converted and down-weighted
#   zho_news_2020_300K          Simplified news, converted and down-weighted
# Tatoeba (https://tatoeba.org), CC BY 2.0 FR: Mandarin sentences for the test set.
# OpenCC dictionaries (https://github.com/BYVoid/OpenCC), Apache-2.0.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/data/corpus"
mkdir -p "$OUT"

LEIPZIG=(
  zho-trad_newscrawl_2011_1M
  zho_wikipedia_2018_1M
  zho-cn_web_2015_1M
  zho_news_2020_300K
)

for name in "${LEIPZIG[@]}"; do
  if [[ -d "$OUT/$name" ]]; then
    echo "have $name"
    continue
  fi
  echo "fetch $name"
  curl -fL --retry 3 -o "$OUT/$name.tar.gz.partial" \
    "https://downloads.wortschatz-leipzig.de/corpora/$name.tar.gz"
  mv "$OUT/$name.tar.gz.partial" "$OUT/$name.tar.gz"
  tar -xzf "$OUT/$name.tar.gz" -C "$OUT"
  rm "$OUT/$name.tar.gz"
done

if [[ ! -f "$OUT/tatoeba/cmn_sentences.tsv" ]]; then
  echo "fetch tatoeba cmn"
  mkdir -p "$OUT/tatoeba"
  curl -fL --retry 3 -o "$OUT/tatoeba/cmn_sentences.tsv.bz2" \
    "https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences.tsv.bz2"
  bunzip2 -f "$OUT/tatoeba/cmn_sentences.tsv.bz2"
fi

# OpenCC 1.4.2 (Apache-2.0), the version tools/ci-engine.sh builds: which simplified
# characters have more than one Traditional form, for the conversion review list.
if [[ ! -f "$OUT/opencc/STCharacters.txt" ]]; then
  echo "fetch opencc STCharacters"
  mkdir -p "$OUT/opencc"
  curl -fL --retry 3 -o "$OUT/opencc/STCharacters.txt" \
    "https://raw.githubusercontent.com/BYVoid/OpenCC/ver.1.4.2/data/dictionary/STCharacters.txt"
fi

echo "corpora in $OUT"
ls -la "$OUT"
