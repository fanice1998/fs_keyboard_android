# 詞庫格式與自訂詞 Implementation Plan

> **For agentic workers:** 任務用 checkbox。不要 commit。不要改 `EditorSession.deleteOne`。

**Goal:** 個人打包把 `data/chiakey/ChiaKeySource.db` 放進 APK，開啟鍵盤不再下載。對內列格式是 `fslexicon 1`。千秋 unigram 由一個轉換器寫入。自訂中文詞用同一種列。

**Architecture:** 引擎仍開啟 `files/chiakey/ChiaKeySource.db`。第一次啟動若 APK 裡有這份檔，就複製過去。列格式給鍵盤自己的查詢與自訂詞，不取代千秋 session 讀的那份資料庫。下一個詞仍讀 `lexicon.bin`，因為一列沒有左上下文。

**Tech Stack:** Kotlin、Gradle、JUnit。

**Spec:** `docs/superpowers/plans/2026-11-08-rebuild-outline.md` 期 D。

## Global Constraints

- 詞庫檔不進 git。來源路徑是 `data/chiakey/ChiaKeySource.db`。
- 預設打包要求這個檔。缺少時 Gradle 失敗，訊息含 `Missing data/chiakey/ChiaKeySource.db`。
- `-PbundleChiaKeyLexicon=false` 時不複製、不失敗。`.github/workflows/android.yml` 與 `release.yml` 的 Gradle 指令加上這個參數。CI 不能取得這份非商業詞庫。
- 沒有下載、沒有同意片。沒有打進 APK 時，不出現下載詞。
- 列格式是 UTF-8。第一行剛好是 `fslexicon 1`。之後每行 `reading\ttext\tweight\tsource`。`source` 只准 `builtin` 或 `user`。欄位不含 Tab 或換行。`weight` 是十進位數字。
- 自訂列存在 `files/lexicon/fslexicon.txt`。轉換出的 builtin 列可以另存，但讀取時與 user 列是同一種。
- 換來源只新增轉換器，不改列格式。
- 不改 ABI、不改刪除契約、不改外觀。
- 沒有要求就不要 commit。不要提交 `data/chiakey/` 或 `third_party/chiakey`。

## Review Focus

- 單元測試不需要那份 80MB 資料庫。
- 一般字元的刪除測試仍通過。

---

### Task 1: 打包資料庫並拿掉下載

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/android.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `FsKeyboardService.kt` 與詞庫提示的呼叫處

- [x] **Step 1:** 打包任務把資料庫複製到產生的 assets 目錄 `chiakey/ChiaKeySource.db`。缺檔且未設 `-PbundleChiaKeyLexicon=false` 時拋出含 `Missing data/chiakey/ChiaKeySource.db` 的錯誤。
- [x] **Step 2:** 啟動時若 assets 有這份檔且 `files/chiakey/ChiaKeySource.db` 還沒有，整檔複製再 `open()`。不要刪 `writable/`。
- [x] **Step 3:** 生產路徑不再呼叫下載，也不再顯示「詞庫尚未下載」或同意片。

### Task 2: fslexicon 1 與千秋轉換器

**Files:**
- Create: `app/src/main/java/com/fskeyboard/ime/lexicon/FsLexicon.kt`
- Create: `app/src/main/java/com/fskeyboard/ime/lexicon/ChiaKeyLexiconConverter.kt`
- Test: `app/src/test/java/com/fskeyboard/ime/lexicon/FsLexiconTest.kt`

**Interfaces:**
- `FsLexicon` 讀寫上述文字格式。`rows(): List<Row>`，`addUser(reading, text, weight)`，`replaceBuiltin(rows)`。
- `data class Row(val reading: String, val text: String, val weight: Double, val source: String)`
- 轉換器讀取千秋 unigram 的方式與 `ChiaKeyUnigramScan` 相同，寫成 `source = builtin`。測試用一小段假資料，不開那份 80MB 檔。

- [x] **Step 1:** 寫失敗測試：版本行、user 列排在查詢結果裡、壞行被跳過。
- [x] **Step 2:** 實作讀寫與轉換器。
- [x] **Step 3:** 使用者組出的中文詞寫入 user 列。既有 `CandidatePreference` 的升降權重可以留著。

### Task 3: 測試

- [x] **Step 1:** `JAVA_HOME=/tmp/temurin21/jdk-21.0.13+9/Contents/Home ./gradlew :app:testDebugUnitTest --offline` 通過。
