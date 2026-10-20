# FsKeyboard

Android 台灣注音輸入法骨架。Kotlin `InputMethodService` 負責鍵盤與候選列，透過自寫 JNI 呼叫 librime 的公開 C API。字形對齊由方案裡的 OpenCC `t2tw` filter 完成，用戶詞用 librime 內建的 userdb。

授權是 Apache-2.0。這裡沒有同文、fcitx5-android 或其他 GPL 前端的程式碼，也沒有放入 LGPL 的注音方案、朙月拼音或 rime-prelude。鍵盤對照與拼寫代數是依標準注音鍵位自寫的。詞庫是專案自寫的小樣本。見 `NOTICE.md`。

## 資料流

```
注音鍵（顯示注音，送出 ASCII 鍵位）
  -> FsKeyboardService
  -> CandidateController
  -> RimeEngine（單一背景執行緒）
  -> RimeNative / rime_jni.cpp
  -> librime process_key 或 select_candidate
  -> EngineSnapshot（每個候選帶著原本的 engineIndex）
  -> CandidatePipeline
       -> CandidateReranker
       -> OutputScript（只轉換 reranker 新增的詞）
  -> 候選列
```

點選或按空白時：

- `engineIndex >= 0` 且來源是 Rime：呼叫 `select_candidate`。librime 會把這個詞寫進用戶詞庫。
- `engineIndex < 0`：當成 reranker 產生的詞，直接 `commitText`。Rime 不會學到它。

空白鍵接受的是畫面上的第一個候選，不是 Rime 排序後的第一個。這樣 reranker 把詞提前之後，上屏和學習的是使用者看到的那一個。

## 專案結構

```
app/src/main/java/com/fskeyboard/ime/
  FsKeyboardService.kt          輸入法生命週期、建立鍵盤畫面
  keyboard/                     標準注音、英文、符號，三種版面
  candidate/                    候選列、點選、上屏
  engine/                       InputEngine、RimeEngine、資料安裝
  bridge/                       JNI 宣告與回傳物件
  rerank/                       候選重排。OnnxCandidateRanker、字元特徵或字串、ONNX 載入
  settings/                   系統鍵盤設定頁，開關神經重排
  script/                       只給「非 Rime 候選」用的字形轉換
app/src/main/cpp/rime_jni.cpp   librime C API 的薄封裝
app/src/main/assets/rime/shared 注音方案、示範詞庫、OpenCC t2tw / s2tw 設定
third_party/librime/            本機放入的 header 與 .so（不進版控）
tools/install-shared-data.sh    複製 OpenCC 字典
```

`InputEngine` 把 librime 藏在介面後面。之後若要換引擎，`CandidateController` 不用改。

`ZhuyinLayout` 是注音鍵位的唯一來源。鍵盤顯示注音符號，`KeyAction.Bopomofo` 把對應的 ASCII 送給 Rime。方案裡的 `algebra` 與 `preedit_format` 必須和這張表一致。

Rime 已經用 `t2tw` 轉過的候選不要再轉一次。`CandidatePipeline` 只對 `SYNTHETIC` 候選呼叫 `OutputScript`。預設是原樣返回；若 reranker 會吐出尚未對齊臺灣標準的新詞，再換上真正的轉換器。

## 鍵盤

注音版面用標準注音鍵位，四排字母鍵加一排功能鍵：

```
ㄅ ㄉ ˇ ˋ ㄓ ˊ ˙ ㄚ ㄞ ㄢ ㄦ
ㄆ ㄊ ㄍ ㄐ ㄔ ㄗ ㄧ ㄛ ㄟ ㄣ
ㄇ ㄋ ㄎ ㄑ ㄕ ㄘ ㄨ ㄜ ㄠ ㄤ
ㄈ ㄌ ㄏ ㄒ ㄖ ㄙ ㄩ ㄝ ㄡ ㄥ 刪
符 英 ， 空格 。 換行
```

librime 的 speller 只吃字母表裡的 ASCII，不吃注音 Unicode。所以畫面上的 `ㄅ` 送出的是 `1`，`ㄥ` 送出的是 `/`。組字區再由 `preedit_format` 顯示回注音。聲調鍵 `ˊ ˇ ˋ ˙` 用功能鍵的顏色。

`，` `。` 是 `KeyAction.Punctuation`，直接上屏，不送進 Rime。半形逗號、句號、斜線已經是 `ㄝ` `ㄡ` `ㄥ`。有候選時，標點會先接受畫面上的第一個候選再上屏；只有組字、沒有候選時，先清掉組字再上屏。

底部可切到英文或符號。英文版面的「注」回到注音。候選列右側的「臺 / 通」切換方案選項 `zh_tw`：`臺` 是 OpenCC `t2tw`（臺灣標準），`通` 是關掉 filter 後的詞庫原文。候選詞有內容說明，自繪按鍵還沒有無障礙節點。

librime 還沒載入時，注音鍵會把注音符號直接上屏，候選列會說明原因。

## 神經重排

`FsKeyboardService` 啟動時建立 `OnnxCandidateRanker`，呼叫 `load()` 一次，再放進 `CandidatePipeline`。載入在 `fskeyboard-rerank` 執行緒進行：讀 assets 的 `rerank/model.onnx`（`files/rerank/model.onnx` 已有內容就用那一份），建立 ONNX session。沒有模型檔不會讓輸入法崩潰，候選維持 Rime 順序。

librime 先吐出最多 40 個候選。模組只拿前 9 個字串，加上游標前最近 20 字，做一次推論，回傳新的順序。後面的候選保持原順序。`engineIndex` 留在原來的候選物件上。相同文字的兩筆候選不會被當成同一筆。

按鍵執行緒只等 librime。候選列先畫 Rime 順序，推論在 `fskeyboard-rerank` 進行。50ms 內回來，而且使用者還沒按下一鍵，才改候選列。超過、模型丟出例外、分數數量不合、或上一筆還在跑，就留著已經顯示的順序，按鍵不會多等這 50ms。

logcat 標籤 `FsKeyboard`，過濾 `latency`：

```sh
adb logcat -s FsKeyboard
```

`rime` 是這次 JNI 裡 librime 的耗時（不含在佇列裡等待）。`infer` 是 `score()` 本身。`elapsed` 是從送出推論到結果回來，含排隊。`fallback=none` 才會改順序。`fallback=stale` 表示下一鍵已經發生。模型沒載入時只會看到 `rime=` 那一行。

模型檔先留空。規格在 `app/src/main/assets/rerank/MODEL.txt`。

兩種輸入都實作 `RerankEncoder`，在 `FsKeyboardService` 替換：

- `ContextCandidateEncoder`：字元級特徵，`features` `[1, N, 64]` 與 `mask` `[1, N]`。目前預設是這個。
- `DirectStringEncoder`：直接送字串，`context` `[1]` 與 `candidates` `[N]`。

系統設定 → 鍵盤 → FsKeyboard 注音 → 設定，可以關掉「神經重排」。關掉之後模型不會被呼叫。這個 App 沒有桌面圖示。

換模型的方式：

- 字元特徵、輸出仍是 `scores`：換 `model.onnx`。
- 模型吃字串：把 encoder 換成 `DirectStringEncoder()`。
- 輸入名稱不同：做一個新的 `RerankEncoder`，傳給 `OnnxRerankModel`。
- 不用 ONNX：做一個新的 `RerankModel`，傳給 `OnnxCandidateRanker`。
- 窗口要大於 9，或延遲預算不同：另寫一個 `CandidateReranker`。`ScoreBoostReranker` 是不跑模型的對照實作。

契約在 `CandidateReranker`：

- 可以重排、刪除，或在後面加上新詞。
- Rime 候選的 `engineIndex` 和文字都要保留。改了文字卻留著索引，用戶詞庫會學到另一個詞。
- 新詞用負的 `engineIndex`。上屏時不會呼叫 `select_candidate`。
- 目前從 librime 取回的是前 40 筆（`RimeEngine` 的 `candidateLimit`），不是 Rime 的單頁。神經重排只動這 40 筆裡的前 5–9 個。少於 2 個候選不會跑模型。

## 降低重排延遲

先看 `latency` 的 `rime` 和 `infer` 哪一個大，再動對應的那一側。

- 候選數量：`rime` 高時，把 `RimeEngine` 的 `DEFAULT_CANDIDATE_LIMIT` 從 40 調低。候選列會少掉後面的詞。重排窗口預設 9，若 `infer` 隨 N 變大，在 `FsKeyboardService` 傳 `NeuralRerankConfig(windowSize = 5)`。
- 模型量化：`infer` 經常超過 50ms 時，把權重做成 INT8，輸入輸出名稱維持 `MODEL.txt` 的契約。Session 已固定一條 CPU 執行緒、`ALL_OPT`、循序執行。這種 9 列的小模型不要開 NNAPI，啟動時間通常比計算還長。
- 非同步：鍵盤已經不等推論。50ms 後的結果直接丟棄。不要在按鍵執行緒呼叫 `rerank()`；那條同步 API 最多阻塞一個預算，只留給測試。
- 同一個 session 一次只跑一筆。上一筆超過 50ms 時，接下來的按鍵會立刻 `BUSY`，並維持 Rime 順序。

## JNI

`RimeNative` 是 Kotlin `object`，所以 native 方法是 instance method。實作在 `app/src/main/cpp/rime_jni.cpp`。所有呼叫都由 `RimeEngine` 排在同一個執行緒，JNI 本身不加鎖。

| Kotlin | librime |
| --- | --- |
| `startup(sharedDir, userDir, logDir)` | `setup`、`initialize`、`start_maintenance(false)`、`join_maintenance_thread` |
| `createSession` / `destroySession` | `create_session` / `destroy_session` |
| `selectSchema` | `select_schema` |
| `processKey(session, keycode, mask, limit)` | `process_key`，再讀 commit 與候選 |
| `selectCandidate(session, index, limit)` | `select_candidate`（全域索引，不是目前頁） |
| `snapshot` | 只讀 commit 與 context |
| `clearComposition` | `clear_composition` |
| `setOption` / `getOption` | 同名 API |
| `lastError` / `version` | 部署錯誤字串、`get_version` |

`processKey` 的 keycode 用 X11 keysym。注音鍵送的是標準鍵位上的 ASCII。`RimeKey` 定義了空白 `0x20`、退格 `0xff08`、換行 `0xff0d`。

回傳的 `RimeResponse` 建構子簽名是 JNI 契約的一部分：

```
(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[II)V
```

依序是 `consumed`、`commit`、`preedit`、`preview`、候選文字、註解、全域 `indices`、Rime 目前高亮的全域索引。`indices[i]` 要原樣放進 `Candidate.engineIndex`。

候選用 `candidate_list_begin` / `next` / `end` 讀取，最多 `limit` 筆，上限 200。

## 載入 librime 與方案

預設 `rime.enabled=false`，不編譯 native code，方便先看 UI。

GitHub Actions 會在 runner 上執行 `tools/ci-engine.sh`，交叉編譯 librime 1.17.0（`arm64-v8a`、`x86_64`），並用 OpenCC 1.4.2 產生六個 `.ocd2`，再用 `-Prime.enabled=true` 打進 debug APK。這些檔不進 git，`gradle.properties` 維持 `rime.enabled=false`。神經重排的 `model.onnx` 沒有包進去。

要在自己的機器上接引擎，照下面做。

### 1. 編譯 librime

用與這個 App 相同的 Android ABI（`arm64-v8a`、`x86_64`）與 `minSdk 26` 編譯 [librime](https://github.com/rime/librime)（BSD-3-Clause）。用戶詞需要 LevelDB userdb 模組，字形轉換需要它連結的 OpenCC。依 librime 自己的依賴說明準備 Boost、glog、yaml-cpp、marisa、LevelDB、OpenCC，再用 NDK toolchain 產出 `librime.so`。

確認兩件事：

```sh
nm -D librime.so | grep rime_get_api
readelf -d librime.so | grep NEEDED
```

`NEEDED` 列出的每一個 `.so` 都要跟 `librime.so` 放在一起。

### 2. 放到 third_party

```
third_party/librime/include/rime_api.h
third_party/librime/jniLibs/arm64-v8a/librime.so
third_party/librime/jniLibs/arm64-v8a/<NEEDED 的其他 .so>
third_party/librime/jniLibs/x86_64/...
```

`rime_api.h` 必須和 `.so` 是同一個 librime 版本。然後在 `gradle.properties` 設：

```
rime.enabled=true
```

CMake 會編譯 `libfskeyboard.so` 並連結 `librime.so`。Gradle 會把 `jniLibs` 打進 APK。`RimeNative.load()` 先載入 `librime`，再載入 `fskeyboard`。

### 3. 放入 OpenCC 字典

`t2tw.json` 與 `s2tw.json` 已經在 assets 裡。字典本體不在版控中。從與 librime 相同版本的 OpenCC 資料目錄複製：

```sh
tools/install-shared-data.sh /path/to/opencc/share/opencc
```

預設方案用 `t2tw`，需要這三個檔，並且跟 json 放在同一個 `opencc/` 目錄：

- `CJK_Compatibility_Ideographs.ocd2`
- `TWVariantsPhrases.ocd2`
- `TWVariants.ocd2`

`s2tw` 另外還需要：

- `STPhrases.ocd2`
- `STPhrases_GeneratedFromRegionalPhrases.ocd2`
- `STCharacters.ocd2`

安裝腳本會一次複製這六個檔。字典缺失時，方案仍可部署，但 `t2tw` 不會生效，候選維持詞庫原文。

### 4. 方案怎麼進到 librime

共享資料在 `app/src/main/assets/rime/shared/`：

- `default.yaml`：`schema_list` 指向 `fs_zhuyin`
- `fs_zhuyin.schema.yaml`：注音、`zh_tw` 預設開啟、`simplifier.opencc_config: t2tw.json`、`enable_user_dict: true`
- `fs_zhuyin.dict.yaml`：單字是全字庫注音（CNS 第 1、2 字面），詞組是專案自寫的。文字是臺灣繁體，編碼是注音，聲調在音節最後，輕聲也是後綴
- `data/lexicon/mcbopomofo.tsv`（不在 assets 裡）：Rust 詞庫的主要詞組表，約 17 萬筆，由 `tools/import-mcbopomofo.py` 從小麥注音（MIT）轉出。`fs_zhuyin.dict.yaml` 只補它沒有的讀音，排在最後。`tools/build-lexicon.sh` 把兩者和 `data/lexicon/bigram.tsv` 編成 `assets/lexicon/lexicon.bin`，App 啟動時直接讀進記憶體，不用解析
- `data/lexicon/bigram.tsv`：詞與詞相鄰的機率，從 Leipzig 繁體新聞語料（CC BY）統計，只留出現 5 次以上的組合。整句選字和候選排序會看前一個詞，包括游標前剛送出的字
- `assets/lexicon/english.tsv`：英文詞頻，從 Leipzig `eng_news_2024_300K`（CC BY）的詞表統計。少於 5 次的詞不收，兩個字母的詞要至少 100 次，最多 4 萬詞。`data/lexicon/english_extra.txt` 補上資訊用語，沒有或不夠常見的詞抬到第 5000 名的次數。英文鍵盤打字時用前綴對這份表，空白鍵送出第一個候選。`tools/build-english.sh` 可重算
- `opencc/t2tw.json`：預設。通用繁體對到臺灣標準
- `opencc/s2tw.json`：詞庫若改成簡體，把方案的 `opencc_config` 換成這個檔

拼寫代數把每個注音符號對到標準鍵位，例如 `ㄅ` → `1`、`ㄥ` → `/`。全部對完之後才做 `derive/[3467]//`，所以聲調鍵 `ˇ ˋ ˊ ˙` 可以不打。`preedit_format` 是反向對照，組字區才會顯示注音。

第一次啟動，或 `VERSION` 和上次不同時，`RimeDataInstaller` 把這棵目錄複製到：

```
files/rime/shared     唯讀共享資料
files/rime/user       用戶資料，更新 assets 時不會刪
files/rime/user/build 編譯後的 prism
```

改了 assets 就要把 `VERSION` 加 1，已安裝的 App 才會重拷共享資料。用戶詞在 `files/rime/user/fs_zhuyin.userdb`，不要去清那個目錄。

個人設定放在用戶目錄，例如 `fs_zhuyin.custom.yaml`：

```yaml
patch:
  menu/page_size: 6
```

JNI `startup` 把 `shared_data_dir` 指到 shared、`user_data_dir` 指到 user，`app_name` 是 `rime.fskeyboard`。接著做增量部署（`start_maintenance(false)`），成功後 `create_session` 並 `select_schema("fs_zhuyin")`。部署失敗時候選列會顯示 `lastError()`，詳細原因在 logcat 的 `FsKeyboard`。

`zh_tw` 的 `reset: 1` 讓新會話預設套用 t2tw。使用者點「臺 / 通」會呼叫 `set_option`，下一次讀取 context 就是另一種字形。

### 5. 建置與啟用

需要 JDK 17 或以上、Android SDK（此專案 `compileSdk 36`）。用 Android Studio 開啟目錄，或：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

安裝後到系統設定啟用「FsKeyboard 注音」，再在鍵盤清單裡選它。同一個設定頁可以打開「FsKeyboard 設定」。這個 App 沒有桌面圖示。

## 用戶詞

方案裡 `translator.enable_user_dict: true`。選詞必須走 `select_candidate`，librime 才會更新 LevelDB 用戶詞庫。只把文字 `commitText` 上去不會學習。

librime 若沒有編進 userdb 模組，這個開關不會生效。部署 log 裡若出現 userdb 相關錯誤，就是 `.so` 少了 LevelDB。

## 測試

單元測試鎖住這些契約，不需要裝置，也不需要 librime：

- 注音鍵位與 `ZhuyinLayout` 一致，聲調鍵角色是 `TONE`
- `，` `。` 不送出半形逗號與句號
- 方案代數與鍵位雙向對得起來，`derive` 在所有 `xform` 之後
- 方案預設 `t2tw` 與 `enable_user_dict`
- 詞庫是 tab 分隔的注音，聲調在音節最後
- JNI 方法名稱與 `RimeNative` 一致
- rerank 之後 `engineIndex` 還在
- 神經重排只動前 9 個與最近 20 字；超過 50ms、模型失敗、載入失敗或開關關掉時維持原順序，而且非同步路徑不會等滿預算
- 合成候選才會再做字形轉換
- 空白鍵接受畫面上的第一個候選

```sh
./gradlew :app:testDebugUnitTest
```

示範詞庫只夠部署與看候選，不是完整注音詞典。
