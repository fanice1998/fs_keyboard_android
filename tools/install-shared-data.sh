#!/bin/sh
# Copy OpenCC dictionaries for t2tw (default) and s2tw (optional).
# t2tw needs CJK_Compatibility_Ideographs, TWVariantsPhrases, TWVariants.
# s2tw also needs the three ST* dictionaries below.
# Usage: tools/install-shared-data.sh /path/to/opencc/share/opencc
set -eu

src=${1:?path to OpenCC share/opencc}
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
dest="$root/app/src/main/assets/rime/shared/opencc"
mkdir -p "$dest"

for name in \
  CJK_Compatibility_Ideographs.ocd2 \
  STPhrases.ocd2 \
  STPhrases_GeneratedFromRegionalPhrases.ocd2 \
  STCharacters.ocd2 \
  TWVariantsPhrases.ocd2 \
  TWVariants.ocd2
do
  cp "$src/$name" "$dest/$name"
done

echo "Copied OpenCC dictionaries into $dest"
echo "Bump app/src/main/assets/rime/shared/VERSION if the app was already installed."
