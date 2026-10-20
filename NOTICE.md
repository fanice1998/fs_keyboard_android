FsKeyboard
Copyright 2026 the FsKeyboard authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Third-party boundaries
----------------------

This repository is an original Android input-method skeleton for Taiwan
zhuyin. It does not contain source from GPL input-method front ends
(including Trime and fcitx5-android) and it does not contain rime-bopomofo,
rime-luna-pinyin, or rime-prelude, which are LGPL-3.0. The keyboard positions
follow the standard Taiwan bopomofo layout. The schema algebra is an original
one-to-one map from those glyphs to ASCII keys.

librime is BSD-3-Clause. The app links it as a separate native library and
calls only the public C API declared in rime_api.h. That header and the
built libraries are supplied locally under third_party/librime and are not
vendored here.

OpenCC is Apache-2.0. app/src/main/assets/rime/shared/opencc/t2tw.json follows
the OpenCC "t2tw" profile (Traditional Chinese, OpenCC standard, to Taiwan
standard). s2tw.json is the OpenCC "s2tw" profile and is unused unless a
simplified lexicon replaces the sample. The binary dictionaries these configs
name are not included; install them from an OpenCC distribution with
tools/install-shared-data.sh.

fs_zhuyin.dict.yaml has two parts. The phrases and the original sample rows
were written for this project and are not a dictionary dump. Single-character
readings in CNS 11643 planes 1 and 2 are derived from the Ministry of Digital
Affairs full repertoire (數位發展部，CNS11643 中文標準交換碼全字庫，
https://www.cns11643.gov.tw). The phonetic table was joined to that release's
Unicode mapping (https://data.gov.tw/dataset/5961) under the Open Government
Data License, version 1.0. This file is a modification of that data: CNS codes
were replaced with Unicode characters, the neutral-tone mark was moved to the
end of each syllable, planes other than 1 and 2 were left out, rows already in
the project sample were not repeated, and each imported row was given a weight.
The Ministry does not endorse this product.

Imported weights use Unicode Unihan kGradeLevel when present, then whether
kUnihanCore2020 includes Taiwan (Unicode 18.0.0). Copyright © 1991-2026
Unicode, Inc. Unicode data is used under the Unicode Terms of Use,
https://www.unicode.org/copyright.html. The data files are provided "AS IS",
without warranty.

ONNX Runtime for Android (com.microsoft.onnxruntime:onnxruntime-android) is
MIT. The neural reranker calls its public Java API. No model file is vendored.
