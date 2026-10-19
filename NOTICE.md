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

The sample lexicon in fs_zhuyin.dict.yaml was written for this project. It is
not a dictionary dump. Entries are Taiwan Traditional, and codes are zhuyin
with the tone mark at the end of each syllable.

ONNX Runtime for Android (com.microsoft.onnxruntime:onnxruntime-android) is
MIT. The neural reranker calls its public Java API. No model file is vendored.
