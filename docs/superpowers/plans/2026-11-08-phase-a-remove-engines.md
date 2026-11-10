# 移除舊引擎 Implementation Plan

> **For agentic workers:** 任務用 checkbox。不要 commit。不要改 `abiFilters`、workflow 的 Rust target、`tools/build-lexicon.sh`。那些屬於期 E。

**Goal:** 注音只剩 `ZhuyinEngine`。沒有呼叫路徑的 Rime 與 Rust 注音查詢刪掉。`lexicon.bin` 的下一個詞仍在。

**Architecture:** 生產路徑已經是 `ChiaKeySession`。這一期刪掉旗標與死程式，不重寫組字。`CandidateController` 不再接收 `chiakey` 與 `ZhuyinLexicon`。原先 `chiakey == true` 的分支留下，`false` 的分支刪除。

**Tech Stack:** Kotlin、CMake、JUnit。

**Spec:** `docs/superpowers/plans/2026-11-08-rebuild-outline.md` 期 A。

## Global Constraints

- 保留 `engine/fs_lexicon`、`tools/build-lexicon.sh` 產出的 `lexicon.bin`，以及 `CandidateController` 讀 `assets/lexicon/lexicon.bin` 的下一個詞。
- 保留 ChiaKey：`ChiaKeySession`、`ChiaKeyNative`、`chiakey_jni.cpp`、`third_party/chiakey`、下載詞庫流程。下載改由期 D 移除。
- 保留 `app/src/main/assets/rime/shared/`。`fs_zhuyin.dict.yaml` 仍是 `lexicon.bin` 的輸入。
- 不改按鍵延遲、不拆控制層、不改外觀。
- 游標滑動、英文、剪貼簿、數字鍵、電腦鍵的現有行為保持。這些測試要通過。
- 沒有要求就不要 commit。
- 不要提交 `third_party/chiakey` 的工作樹變更。

## Review Focus

- 注音按鍵路徑的原始碼不參考 `RimeEngine` 或 `RustZhuyinLexicon`。測試放在 Task 1。
- 沒有組字時的下一個詞仍讀 `lexicon.bin`。不要刪 `loadWordContinuations`。

---

### Task 1: 刪除 Rime 與 Rust 注音查詢的程式

**Files:**
- Delete: `app/src/main/cpp/rime_jni.cpp`
- Delete: `app/src/main/java/com/fskeyboard/ime/bridge/RimeNative.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/bridge/RimeResponse.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/RimeEngine.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/RimeDataInstaller.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/RimeKey.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/RimeSchema.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/RimeSnapshots.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/InputEngine.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/RustZhuyinLexicon.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/RustLexiconNative.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/IdleZhuyinLexicon.kt`
- Delete: `app/src/main/java/com/fskeyboard/ime/engine/ZhuyinLexicon.kt`（控制器不再使用之後）
- Delete: `app/src/test/java/com/fskeyboard/ime/engine/RimeResponseMappingTest.kt`
- Modify: `app/src/main/cpp/CMakeLists.txt`（刪掉 `RIME_ENABLED` 整段）
- Modify: `app/build.gradle.kts`（只刪 `rimeEnabled` 與傳給 CMake 的 `-DRIME_*`。`abiFilters` 維持期 E 的結果）
- Modify: `gradle.properties`（刪 `rime.enabled`）
- Modify: `app/proguard-rules.pro`（刪 Rime 與 `RustLexiconNative` 的 keep。保留 `ChiaKeyNative`）
- Modify: `app/src/test/java/com/fskeyboard/ime/SchemaContractTest.kt`（刪 `jniMethodsMatchTheKotlinDeclarations`。改斷言 `rime_jni.cpp`、`RimeEngine.kt`、`RustZhuyinLexicon.kt` 不存在）
- Modify: `tools/ci-engine.sh` 刪除整個檔。workflow 沒有呼叫它。
- Modify: `README.md` 與 `NOTICE.md` 裡把 librime 寫成目前輸入路徑的句子。改成注音走 ChiaKey，librime 不再連結。不要重寫整份文件。
- Modify: `third_party/librime/README.md` 註明這個目錄不再被建置使用。

**Interfaces:**
- 生產注音引擎仍是 `ZhuyinEngine`，實作為 `ChiaKeySession`。

- [x] **Step 1:** 加上「這些檔案不存在」的測試，跑一次確認它失敗。
- [x] **Step 2:** 刪除上面的檔案與 CMake、Gradle、ProGuard 的 Rime 接線。
- [x] **Step 3:** 全專案不再引用已刪符號。`Candidate.kt` 註解裡的 `InputEngine` 改成現在的選詞路徑，不要留死連結。

### Task 2: 控制器只走千秋分支

**Files:**
- Modify: `app/src/main/java/com/fskeyboard/ime/candidate/CandidateController.kt`
- Modify: `app/src/main/java/com/fskeyboard/ime/FsKeyboardService.kt`
- Modify: 所有建構 `CandidateController` 的測試。目前是 `ChiaKeyTypingTest.kt`、`ClipboardCandidateTest.kt`、`WordSuggestionTest.kt`、`NumberKeyboardTest.kt`、`BackspaceSelectionTest.kt`、`ComputerKeyDispatchTest.kt`。

**Interfaces:**
- Consumes: `ZhuyinEngine`。預設可以仍是 `UnavailableZhuyinEngine`。
- Produces: `CandidateController` 建構子不再有 `lexicon: ZhuyinLexicon` 與 `chiakey: Boolean`。

- [x] **Step 1:** 刪掉 `chiakey` 參數。每個 `if (chiakey)` 留下當時為 true 的分支，刪掉 else。
- [x] **Step 2:** 刪掉只為舊分支存在的 `lookup`、`refresh`、`convertAll`、`finishComposition`，以及 `lexicon.start`、`lexicon.close`、`lexicon.learn`。`loadWordContinuations` 與 `LEXICON_BIN` 留著。
- [x] **Step 3:** `ZhuyinComposition` 若刪完舊分支還被游標滑動用到，就留著，不要為了這期重寫滑動。
- [x] **Step 4:** `FsKeyboardService` 不再傳 `IdleZhuyinLexicon` 與 `chiakey = true`。
- [x] **Step 5:** 測試的假 `ZhuyinLexicon` 一併刪除。只覆蓋舊 Rust 組字、而且千秋測試已覆蓋的案例可以刪。英文、剪貼簿、退格選取、數字鍵、電腦鍵的測試要留著並通過。

### Task 3: 單元測試

- [x] **Step 1:** 跑 `./gradlew :app:testDebugUnitTest`。失敗就修到通過。不要為了通過測試去改回舊引擎。
