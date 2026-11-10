# 拆控制層 Implementation Plan

> **For agentic workers:** 任務用 checkbox。不要 commit。行為與拆分前的注音、英文、刪除、空白一致。先搬呼叫，不改決定。

**Goal:** `CandidateController` 不再同時擁有輸出欄寫入、組字、候選呈現、詞庫查詢。四個接口可以單獨替換。

**Architecture:** 先抽出 `EditorSession`，它是唯一呼叫 `InputConnection` 寫入與游標讀取的型別。組字與候選仍留在控制器，但透過 `EditorSession` 上屏。接著把注音組字與候選呈現各自收成一個型別。詞庫查詢這一期只留接口，實作仍是現有的千秋與 `lexicon.bin`。

**Tech Stack:** Kotlin、JUnit。

**Spec:** `docs/superpowers/plans/2026-11-08-rebuild-outline.md` 期 B。

## Global Constraints

- 介面名稱用 `EditorSession`、`Composer`、`CandidatePresenter`、`Lexicon`。
- 不改下載詞庫、不改 ABI、不改刪除鍵在行首的契約（那是期 C）、不改延遲、不改外觀。
- 注音仍用 `ZhuyinEngine` / `ChiaKeySession`。下一個詞仍讀 `lexicon.bin`。
- 既有單元測試要通過。可以改測試裡的建構方式，不改它斷言的行為。
- 沒有要求就不要 commit。

## Review Focus

- 組字與候選的檔案裡不再直接呼叫 `commitText`、`setComposingText`、`finishComposingText`、`deleteSurroundingText`、`sendKeyEvent`、`setSelection`。只有 `EditorSession` 呼叫它們。

---

### Task 1: 抽出 EditorSession

**Files:**
- Create: `app/src/main/java/com/fskeyboard/ime/session/EditorSession.kt`
- Modify: `app/src/main/java/com/fskeyboard/ime/candidate/CandidateController.kt`
- Test: 既有 `./gradlew :app:testDebugUnitTest`

**Interfaces:**
- Produces:
  - `internal class EditorSession(private val connection: () -> InputConnection?)`
  - `fun commitText(text: String)`
  - `fun setComposing(text: String)`
  - `fun finishComposing()`
  - `fun deleteBefore(n: Int)`
  - `fun selectedText(): CharSequence?`
  - `fun textBefore(n: Int): CharSequence?`
  - `fun extractedText(maxChars: Int): ExtractedText?`
  - `fun setSelection(start: Int, end: Int)`
  - `fun sendDownUp(keyCode: Int, meta: Int)`

- [x] **Step 1:** 建立 `EditorSession`，把控制器裡對 `InputConnection` 的寫入與游標讀取搬進去。控制器持有一個 `EditorSession`。
- [x] **Step 2:** 控制器不再直接呼叫那些 `InputConnection` 方法。讀取編輯器資訊用的 `editorInfo()` 可以留在控制器。
- [x] **Step 3:** 跑單元測試，失敗就修到通過。

### Task 2: 抽出注音 Composer

**Files:**
- Create: `app/src/main/java/com/fskeyboard/ime/compose/ZhuyinComposer.kt`（若與 `keyboard/ZhuyinComposer.kt` 撞名，用 `compose/ZhuyinCompositionSession.kt`，並讓它實作 `Composer`）
- Modify: `CandidateController.kt`

**Interfaces:**
- Produces: `internal interface Composer { fun onKey(action: KeyAction): Boolean }`
- `Zhuyin` 的實作擁有 `BopomofoChain`、`ChiaKeyFrame`、`holdingSentence`、`chosenPrefix`。按鍵不是注音時回傳 false，控制器繼續處理。
- 英文組字這一期留在控制器。

- [x] **Step 1:** 搬移注音組字狀態與 `onBopomofo`、`showFrame`、`showHeld`、`acceptPartial`。上屏呼叫 `EditorSession`。
- [x] **Step 2:** 單元測試通過。

### Task 3: 抽出 CandidatePresenter 與 Lexicon 接口

**Files:**
- Create: `app/src/main/java/com/fskeyboard/ime/candidate/CandidatePresenter.kt`
- Create: `app/src/main/java/com/fskeyboard/ime/lexicon/Lexicon.kt`

**Interfaces:**
- `CandidatePresenter` 接收一列 `Candidate` 與游標前文字，套用偏好與 `CandidatePipeline`，回呼 UI。控制器不再自己呼叫 `pipeline.presentAsync`。
- `Lexicon` 這一期是 `fun following(before: String): List<String>` 與 `fun partialCandidates(readings: List<SyllableReading>): List<Candidate>`。實作分別包現有的 `WordContinuations` 與 `PartialLexicon`。

- [x] **Step 1:** 搬移 `publish`、`applyRerank`、偏好套用到 `CandidatePresenter`。
- [x] **Step 2:** 控制器透過 `Lexicon` 取下一個詞與未標調候選，不直接開 `lexicon.bin`。
- [x] **Step 3:** 單元測試通過。
