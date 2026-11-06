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

data/lexicon/mcbopomofo.tsv is converted by
tools/import-mcbopomofo.py from the data.txt that McBopomofo builds from its
Source/Data (https://github.com/openvanilla/McBopomofo, commit
be6564acad6c4d3265c34a2e1a872d80f9db6068). McBopomofo is MIT License,
Copyright (c) 2011-2026 Mengjuei Hsieh et al. Its multi-character phrases were
originally derived from tsi.src of libtabe, which is BSD licensed. The
conversion keeps the readings, phrases and log-probability scores, and drops
punctuation, symbols and date macros. Permission is hereby granted, free of
charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use, copy, modify,
merge, publish, distribute, sublicense, and/or sell copies of the Software, and
to permit persons to whom the Software is furnished to do so, subject to the
following conditions: The above copyright notice and this permission notice
shall be included in all copies or substantial portions of the Software. THE
SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

data/lexicon/bigram.tsv holds word-pair probabilities counted from the Leipzig
Corpora Collection corpus zho-trad_newscrawl_2011_1M (https://wortschatz.uni-leipzig.de),
which is licensed under CC BY. D. Goldhahn, T. Eckart and U. Quasthoff: Building
Large Monolingual Dictionaries at the Leipzig Corpora Collection: From 100 to 200
Languages. In: Proceedings of the 8th International Language Resources and
Evaluation (LREC'12), 2012. The text was split into words with mcbopomofo.tsv and
only pairs seen five times or more were kept; no sentence of the corpus is included.

app/src/main/assets/lexicon/english.tsv holds English word counts from the word
table of the Leipzig Corpora Collection corpus eng_news_2024_300K, also CC BY
(same citation). Words seen fewer than five times were dropped. Two-letter
words need at least 100 so a fragment such as "th" is not its own word. The
list keeps the 40,000 most frequent of what remains. No sentence of the corpus
is included.
data/lexicon/english_extra.txt adds technical words; a word missing from the news
counts, or rarer than the 5000th news word, is stored at that word's count.

The keyboard looks phrases up in mcbopomofo.tsv first. fs_zhuyin.dict.yaml adds
the readings that table lacks, ranked below every table row. Both are compiled
into app/src/main/assets/lexicon/lexicon.bin at build time.

ONNX Runtime for Android (com.microsoft.onnxruntime:onnxruntime-android) is
MIT. The neural reranker calls its public Java API. No model file is vendored.

ChiaKeyCore (https://github.com/chiakich/ChiaKey) is BSD-3-Clause. The app
links it as libfs_chiakey.so and calls only the public C API in
ChiaKeyCoreC.h. A small Android patch, third_party/patches/chiakey-android.patch,
lets the same sources build with the NDK. The copyright notice and the
conditions follow. The name Yahoo is not used to endorse FsKeyboard.

Copyright (c) 2012, Yahoo! Inc.  All rights reserved.
Copyright (c) 2026, Chiaki.C.  All rights reserved.

Redistribution and use of this software in source and binary forms,
with or without modification, are permitted provided that the following
conditions are met:

* Redistributions of source code must retain the above copyright notice,
  this list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
  documentation and/or other materials provided with the distribution.
* Neither the name of Yahoo! Inc. nor the names of its contributors may
  be used to endorse or promote products derived from this software
  without specific prior written permission of Yahoo! Inc.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

The zhuyin lexicon is not in this repository and not in the APK. After
the user agrees, the app downloads one published ChiaKeySource.db from
chiakich/ChiaKey-Lexicon and installs that file whole. That release
already contains the layers ChiaKey folded in, including libchewing
data (LGPL-2.1-or-later), rime-essay (LGPL-3.0), Ministry of Education
reading supplements, and Chiaki bigram tables under CC BY-NC 4.0. Those
terms travel with the downloaded file. The database is not a
commercial license, and typed text is not uploaded. Learning stays in
the app-private SmartMandarinUserData.db, which a later lexicon
download does not delete.
