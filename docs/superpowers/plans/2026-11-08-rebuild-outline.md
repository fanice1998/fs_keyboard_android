# FsKeyboard 重整計畫大綱

> **For agentic workers:** 這是分期大綱，不是某一期的實作步驟。每一期開始時才寫該期的 `docs/superpowers/plans/YYYY-MM-DD-<phase>.md`，用 checkbox 追蹤。實作使用 subagent，一次只領一個未勾選的小任務。進度以 `docs/superpowers/plans/2026-11-08-rebuild-status.md` 為準。上下文中斷後從該檔的第一個未完成項繼續，不要重做已勾選的項。

**Goal:** 私人使用的注音鍵盤只留千秋這一條引擎，詞庫隨 APK 帶走，控制層與輸出欄契約拆開，之後才縮短延遲並加上可替換的外觀。

**Architecture:** 先刪掉沒有接上的引擎，再把 `CandidateController` 拆成輸出欄、組字、候選、詞庫四個接口。詞庫對內只有一種列格式，千秋與以後的來源都先在本機轉成它。外觀是鍵盤讀取的一組樣式，不放進組字邏輯。

**Tech Stack:** Kotlin、ChiaKeyCore（`libfs_chiakey.so`）、JUnit、Gradle、GitHub Actions。APK 只含 `arm64-v8a`。

**Spec:** 本檔就是目標與流程。各期的介面與測試寫在該期計畫，不在這裡預寫。

## Global Constraints

- 這份 APK 只給自己裝。千秋詞庫打進包內，不另做上架用的詞庫切割。
- 原生引擎已經在 APK 裡。要納入的是 `ChiaKeySource.db`，以及拿掉開啟鍵盤後的下載與同意流程。
- 詞庫檔不進 git。本機路徑維持 `data/chiakey/ChiaKeySource.db`，打包時複製進 APK。
- 只建 `arm64-v8a`。`app/build.gradle.kts` 的 `abiFilters`、`.github/workflows/android.yml`、`.github/workflows/release.yml`、`tools/build-lexicon.sh` 裡的 `x86_64` 一起拿掉。`tools/ci-engine.sh` 在 Rime 刪除後若不再被 workflow 呼叫，一併刪除或改成只服務仍留著的建置。
- 未使用的引擎是 `RimeEngine`、`rime_jni.cpp`、`RustZhuyinLexicon`，以及 `CandidateController` 裡 `chiakey = false` 的分支。`lexicon.bin` 的下一個詞在新詞庫格式接上之前還要能用，不在刪引擎那一期一起拆掉。
- 延遲優化排在控制層、輸出欄契約、詞庫格式都完成之後，而且先量測再改。
- 外觀排在基本輸入行為的退出條件都通過之後。
- 不做的事：抄同文、fcitx5-android、Unexpected Keyboard 的程式或鍵位表；把教育部禁止改作的辭典寫進詞庫。
- 提交訊息維持現有句式：句子、開頭大寫、結尾句號。沒有要求就不要 commit。
- 每一小任務結束要更新狀態檔，並用 codegraph 核對剛改的符號，把結果寫進狀態檔的程式地圖。

## 目標

1. 開啟鍵盤即可注音，不出現下載詞庫的步驟。
2. 控制層不再是一支同時處理組字、上屏、英文、剪貼簿、游標與詞庫的類別。
3. 倉庫裡不留沒有呼叫路徑的 Rime 與 Rust 注音查詢。
4. 詞庫對鍵盤只有一種列格式。另一個引擎的資料在本機轉成這個格式再查。
5. 使用者自訂詞與內建詞用同一種列，自訂詞的讀取、學習與備份只走這一處。
6. 輸出欄的狀態有書面契約。在行首按刪除，且前面是換行時，該行可以被刪掉，刪除鍵不會停在沒有變化的重複上。Notion 這一類把 `deleteSurroundingText` 對換行當成無事發生的編輯器，必須改走能刪掉換行的路徑。
7. APK 與 CI 只產 `arm64-v8a`。
8. 整理完成後，有一輪以量測為依據的延遲縮短。
9. 外觀可以換：圖片、按鍵邊框、按鍵動畫、按鍵浮標。組字與詞庫不依賴某一套顏色或圖。

## 非目標

- 這一輪不重寫千秋的選字演算法。
- 這一輪不把英文詞庫、符號頻率、郵件網域併進中文列格式。它們維持現有檔案，直到詞庫那一期的計畫明確把它們拉進來。
- 延遲那一期開始之前，不為了手感去改按鍵執行緒。
- 外觀那一期開始之前，不改按鍵的視覺結構。修輸出欄時可以動刪除與游標，不動繪製。

## 現況（寫計畫時已核對）

- `FsKeyboardService` 建立 `ChiaKeySession`，`chiakey = true`，詞庫物件是 `IdleZhuyinLexicon`。注音不呼叫 `RustZhuyinLexicon` 或 `RimeEngine`。
- 沒有 `ChiaKeySource.db` 時，注音會走 `CandidateController` 的下載同意片。下載在 `FsKeyboardService.downloadLexicon()`。
- `CandidateController.kt` 約 1946 行。沒有組字時的刪除是 `InputConnection.deleteSurroundingText(1, 0)`，不是 `KEYCODE_DEL`。
- 下一個詞仍讀 `assets/lexicon/lexicon.bin`（`CandidateController` 的 `LEXICON_BIN`）。
- ABI 現在是 `arm64-v8a` 與 `x86_64`：`app/build.gradle.kts`，兩個 workflow 的 Rust target 都含 `x86_64-linux-android`。

## 分期與依賴

```
E aarch64          A 移除舊引擎
      \                  \
       \                  B 拆控制層
        \                / \
         \              C   D
          \         輸出欄  詞庫格式與自訂詞
           \            \ /
            \            F 延遲（先量測）
             \          /
              \        G 外觀
               \______/
```

E 與 A 可以同時做。B 等 A 完成。C 與 D 等 B 的接口落地後可以同時做。F 等 C 與 D。G 等 F 的量測計畫寫完並做完其中的行為修正；G 不依賴每一項延遲優化都做完，只依賴基本輸入的退出條件。

| 期 | 名稱 | 完成時必須為真 | 並行 |
| --- | --- | --- | --- |
| A | 移除舊引擎 | 注音只有 `ZhuyinEngine`。`RimeEngine`、`rime_jni.cpp`、`RustZhuyinLexicon`、`chiakey` 布林與 `IdleZhuyinLexicon` 的生產路徑不在。下一個詞仍出得來。單元測試通過。 | 可與 E 同時 |
| E | 只建 aarch64 | debug 與 release workflow、Gradle `abiFilters`、仍會被呼叫的原生腳本都只留 `arm64-v8a`。CI 綠。 | 可與 A 同時 |
| B | 拆控制層 | `CandidateController` 不再同時擁有輸出欄寫入、組字、候選呈現、詞庫查詢。四個接口有測試能單獨替換。行為與拆分前的注音、英文、刪除、空白一致。 | 等 A |
| C | 輸出欄契約 | 契約寫進該期計畫。行首刪除在「`deleteSurroundingText` 刪不掉換行、`KEYCODE_DEL` 刪得掉」的假編輯器上會刪掉該行，而且文字沒變時不會無限重複同一種無效呼叫。 | 等 B，可與 D 同時 |
| D | 詞庫格式與自訂詞 | APK 內含本機的 `ChiaKeySource.db`，無下載流程。對內列格式有版本號。千秋資料由一個轉換器寫入。自訂中文詞用同一種列。換一份來源只新增轉換器。 | 等 B，可與 C 同時 |
| F | 延遲 | 先有按鍵路徑的量測記錄，再改有數字支持的熱點。沒有量測的項目不改。 | 等 C、D |
| G | 外觀 | 樣式是資料：圖片、邊框、動畫、浮標可換。`KeyboardView` 從樣式讀，組字接口不出現顏色與圖。至少兩套樣式能切換並在重開後保持。 | 等基本輸入的退出條件 |

## 各期要鎖定的介面

這些名字是大綱的決定。各期計畫沿用，不要另起一套。

**B 產生，C 與 D 消費：**

- `EditorSession`：唯一呼叫 `InputConnection` 的寫入與游標讀取。組字與候選不自己 `commitText`、`setComposingText`、`deleteSurroundingText`、`sendKeyEvent`。
- `Composer`：一種組字。注音實作與英文實作分開。鍵盤把按鍵交給目前的 `Composer`。
- `CandidatePresenter`：把候選變成列上的狀態，含偏好與重排代次。不決定按鍵含義。
- `Lexicon`：依讀音與上文取列。B 期間的實作可以仍是現在的千秋加 `lexicon.bin`。D 把它換成對內格式。

**D 產生：**

- 列格式至少有：格式版本、讀音、文字、權重、來源（內建或自訂）。轉換器讀外部檔，寫這個格式，不把外部欄位漏進鍵盤。
- 自訂詞的學習寫入同一儲存，不再為中文短语另開一套只給舊 Rust 路徑用的學習。

**G 產生：**

- `KeyboardTheme`：按鍵底圖、邊框、按下動畫、浮標的資料。現有 `KeyPreviewPopup` 收進浮標設定，不另做第二個浮標系統。

## 輸出欄契約（C 的驗收，先寫在這裡以免各期猜）

鍵盤必須分得清三種狀態：自己的組字、編輯器的選取、已上屏文字裡的游標。刪除的順序是組字、然後選取、然後編輯器裡的一個單位。

沒有組字、沒有選取時，刪除要作用在游標前的那個單位。那個單位是換行時，結果必須是兩行合成一行。若 `deleteSurroundingText` 之後游標前的文字沒變，改送一次 `KEYCODE_DEL` 再讀游標。兩種都沒有改變文字時，這一擊結束，連按計時不再對同一個位置重複無效的 `deleteSurroundingText`。

驗收用假的 `InputConnection`：游標在一行的開頭，前一個字元是換行，`deleteSurroundingText(1, 0)` 不改文字，`KEYCODE_DEL` 會刪掉換行。

## 執行協議

狀態檔是 `docs/superpowers/plans/2026-11-08-rebuild-status.md`。它記錄每一期是 `pending`、`planning`、`active`、`blocked` 或 `done`，以及該期計畫的路徑。

一期之內：

1. 把該期狀態改成 `planning`，寫出該期計畫。計畫裡每個任務小到一次 subagent 能做完並跑完它自己的測試。任務之間用 Interfaces 寫明函式名稱與型別。
2. 狀態改成 `active`。能並行的任務在該期計畫裡標 `parallel`。沒標的任務依序做。
3. 每個 subagent 只收到一個任務、該期的 Global Constraints、以及它依賴的 Interfaces。它不讀整份重整大綱以外的其他期。
4. 任務做完：勾選該期計畫的 checkbox，在狀態檔加一行（改了哪些檔、跑了什麼、結果），用 codegraph 查出這一任務新增或刪除的符號，更新狀態檔的程式地圖。然後才領下一個未勾選且依賴已滿足的任務。
5. 一期的任務都勾完，狀態改成 `done`。下一期才能從 `pending` 進入 `planning`。E 與 A 互不阻塞。C 與 D 在 B 為 `done` 之後互不阻塞。

上下文不夠或中斷時，不從記憶恢復。下一次讀狀態檔，找到第一個不是 `done` 的期，打開它的計畫，做第一個未勾選的任務。

codegraph 的索引會跟著檔案變更更新。任務結束時仍要主動查一次剛改的符號，因為程式地圖寫的是「誰呼叫它、它取代了誰」，不是索引自己會寫進狀態檔的句子。

## Review Focus

這些情況各期計畫都要有對應測試，不能只寫在大綱：

- 沒有組字時按刪除，一般字元仍刪一個，選取仍被清掉。放在 C。
- 行首、前一個字元是換行、`deleteSurroundingText` 無效時，改送刪除鍵並停在有變化的結果。放在 C。
- 詞庫檔不在本機時，打包失敗要指出缺的是 `ChiaKeySource.db`，而不是開機後再下載。放在 D。
- 注音按鍵路徑不參考 `RimeEngine` 或 `RustZhuyinLexicon`。放在 A。
- 切換外觀不改變候選順序與上屏文字。放在 G。
