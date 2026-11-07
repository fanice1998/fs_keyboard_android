# 空白鍵只標平聲 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Space during zhuyin marks 平聲 and leaves the reading selectable, instead of forcing the engine's first prediction into the composition.

**Architecture:** An open zhuyin syllable stays on `BopomofoChain`. Space calls `markLevel()` and never `handleAscii(' ')`. Chosen Hanzi is a prefix of that same composition; the engine is not asked to insert it. `PartialLexicon` supplies the tone-filtered candidates.

**Tech Stack:** Kotlin, JUnit, `CandidateController`, `BopomofoChain`, `ChiaKeyTypingTest`.

**Spec:** `docs/superpowers/specs/2026-11-07-zhuyin-space-level-tone-design.md`

## Global Constraints

- 組字中不得把 ASCII 空白送進 `ZhuyinEngine.handleAscii`。
- `ZhuyinTone.LEVEL` 沒有聲調符號。詞庫比對：未標調可比對任何聲調；平聲只可比對平聲。
- 預覽填色 `0xB32C2C2E`，文字 `0xFFFFFFFF`。
- 不修改 `docs/superpowers/specs/2026-11-07-partial-zhuyin-design.md`。
- 不提交 `data/chiakey/`、`tools/telegram_inbox.py`，也不提交 `third_party/chiakey` 的髒 submodule。使用者沒有要求提交時，不要 commit。

## Review Focus

- 沒有組字時按空白，欄位多一個空格，預覽仍是空的。測試放在 Task 1。
- 已標 `ˇ` 的音再按空白，聲調符號還在，引擎的 ascii 紀錄沒有空白。測試放在 Task 1。
- `ㄋㄧˇㄏㄠˇ` 仍先送進引擎成為 `su3cl3`，預覽是 `你好`。既有 `aToneStillGoesToTheEngineBeforeTheNextSyllable` 在 Task 3 重跑。
- 點「你才」之後預覽是 `你才ㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ`，再按空白不會把它改序或送進引擎。測試放在 Task 2。
- 五音節整句按 Enter，欄位仍是 `今天好開心`，且沒有送出鍵碼 13。既有 `aFiveSyllableSentenceCommitsInTypedOrder` 的整句斷言在 Task 3 保持。
- 點了「天」而注音已經清空時按空白，欄位是 `天 `。測試放在 Task 2。
- 預覽只剩已點的「天」時按句號，欄位是 `天。`。測試放在 Task 2。

---

### Task 1: Space marks 平聲 and does not select

**Files:**
- Modify: `app/src/main/java/com/fskeyboard/ime/candidate/CandidateController.kt` (`onSpace`)
- Modify: `app/src/test/java/com/fskeyboard/ime/candidate/ChiaKeyTypingTest.kt`

**Interfaces:**
- Consumes: `BopomofoChain.markLevel(): Boolean`（已有聲調時回傳 false）、`PartialLexicon.candidates(List<SyllableReading>)`
- Produces: 組字中的 `onSpace` 不呼叫 `handleAscii(' ')`。單音節從引擎拉回 chain 後 `holdingSentence`，預覽等於 `chain.text`。

- [ ] **Step 1: Write the failing test**

在 `ChiaKeyTypingTest` 新增 `spaceMarksLevelToneWithoutSelecting`。詞庫含 `天/ㄊㄧㄢ` 與 `甜/ㄊㄧㄢˊ`。`onStartInput` 後打 `ㄊㄧㄢ`：

- 預覽是 `ㄊㄧㄢ`，候選同時有「天」和「甜」
- `onKey(Space)` 之後欄位是 `""`，預覽仍是 `ㄊㄧㄢ`，候選有「天」、沒有「甜」
- `engine.asciiLog` 不含空白字元
- 再打 `ㄚ`，預覽是 `ㄊㄧㄢㄚ`，欄位仍是 `""`

同檔把 `spaceIsSentToTheEngine` 改成 `spaceOnATonedSyllableIsNotSentToTheEngine`：打 `ㄋㄧˇ` 再按空白，`asciiLog` 是 `su3`，預覽仍以 `ㄋㄧˇ` 結尾。

另加 `spaceWithNoCompositionInsertsASpace`：沒打注音就按空白，欄位是 `" "`，預覽是 `""`。

- [ ] **Step 2: Run the new tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.fskeyboard.ime.candidate.ChiaKeyTypingTest.spaceMarksLevelToneWithoutSelecting --tests com.fskeyboard.ime.candidate.ChiaKeyTypingTest.spaceOnATonedSyllableIsNotSentToTheEngine --tests com.fskeyboard.ime.candidate.ChiaKeyTypingTest.spaceWithNoCompositionInsertsASpace --offline --rerun-tasks`

Expected: `spaceMarksLevelToneWithoutSelecting` fails because the preview or the engine log shows a selected character or a trailing space.

- [ ] **Step 3: Change `onSpace`**

千秋注音且目前有組字時：若音還在引擎裡，用退格把 `mirrored` 個鍵退出引擎，把 chain 留住並設 `holdingSentence`，再 `markLevel()`，然後走持有注音的預覽。不要呼叫 `handleAscii(' ')`。已經有聲調或 `pendingTone` 時同樣不要送空白。沒有組字時維持輸入一個空格。

- [ ] **Step 4: Re-run the three tests**

Expected: PASS

### Task 2: Chosen Hanzi stays in front of the following bopomofo

**Files:**
- Modify: `app/src/main/java/com/fskeyboard/ime/candidate/CandidateController.kt`（組字前綴、`acceptPartial`、`showHeld`、`onEnter`）
- Modify: `app/src/test/java/com/fskeyboard/ime/candidate/ChiaKeyTypingTest.kt`

**Interfaces:**
- Consumes: Task 1 的持有 chain。`Candidate.span` 是這個候選覆蓋的音節數。
- Produces: 組字預覽 = 已點漢字 + `chain.text`。點候選只接上前綴並 `dropFront`。chain 變空時不寫入欄位。Enter 在 chain 為空時才 `commit` 前綴；chain 還有音時只吃第一個有 span 的候選。

- [ ] **Step 1: Write the failing tests**

`choosingTianThenAhStaysInTypedOrder`，沿用 Task 1 的詞庫再加 `啊/ㄚ`：

- `ㄊㄧㄢ`、空白、`ㄚ`，點「天」：欄位 `""`，預覽 `天ㄚ`
- 點「啊」：預覽 `天啊`，欄位仍 `""`
- Enter：欄位 `天啊`，預覽 `""`

`choosingTianBeforeTheNextSyllableKeepsItInFront`：`ㄊㄧㄢ`、空白、點「天」：預覽 `天`，欄位 `""`。再打 `ㄚ`：預覽 `天ㄚ`。

`aChosenPrefixStaysAheadOfTheTail`：打 `ㄋㄧㄘㄞㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ`，點「你才」：欄位 `""`，預覽 `你才ㄏㄨㄟㄗㄠㄊㄧㄢㄑㄧㄢˇ`，候選有「會」。再按空白：預覽不變，引擎 ascii 沒有空白。

`spaceAfterAFinishedChoiceCommitsItAndInsertsASpace`：`ㄊㄧㄢ`、空白、點「天」之後預覽是 `天`、欄位是 `""`。再按空白：欄位是 `天 `，預覽是 `""`。

`punctuationCommitsAFinishedChoice`：同一狀態下改按 `。`，欄位是 `天。`，預覽是 `""`。

- [ ] **Step 2: Run those tests and confirm they fail**

Expected: 點「天」之後預覽不是 `天ㄚ`，或欄位已寫入。

- [ ] **Step 3: Keep a chosen prefix on the composition**

點選把 `candidate.text` 接到前綴，`dropFront(span)`，預覽用前綴加剩餘注音。不要把這次點選送進 `ZhuyinEngine.select`。chain 還有音時不要 `commitText`。Enter 的規則依 Interfaces。

既有「點滿就立刻寫入欄位」的斷言要改成：點滿以後預覽是該漢字、欄位仍空，Enter 才寫入。含 `aTonedSingleOffersTheUnigramWhenTheEngineListsNothing` 與五音節測試裡點「今天」的那一段。

- [ ] **Step 4: Re-run Task 2 tests and `ChiaKeyTypingTest`**

Run: `./gradlew :app:testDebugUnitTest --tests com.fskeyboard.ime.candidate.ChiaKeyTypingTest --offline --rerun-tasks`

Expected: PASS

### Task 3: Regression slice

**Files:**
- Test: `app/src/test/java/com/fskeyboard/ime/candidate/ChiaKeyTypingTest.kt`

**Interfaces:**
- Consumes: Task 1 與 Task 2 的行為。
- Produces: 沒有新的生產介面。

- [ ] **Step 1: Re-run the surrounding cases**

Run: `./gradlew :app:testDebugUnitTest --tests com.fskeyboard.ime.candidate.ChiaKeyTypingTest --offline --rerun-tasks`

這些斷言必須仍成立：

- `aToneStillGoesToTheEngineBeforeTheNextSyllable`：`su3cl3`，預覽 `你好`
- `su3cl3ShowsNiHaoAndEnterCommitsIt`：Enter 後欄位 `你好`
- `aFiveSyllableSentenceCommitsInTypedOrder` 的整句：Enter 後欄位 `今天好開心`，沒有鍵碼 13
- `spaceWithNoCompositionInsertsASpace`：欄位 `" "`

Expected: PASS。若前綴或單字點選的舊斷言還要求「一點就寫入欄位」，以 Task 2 的新斷言為準，不要改回舊行為。
