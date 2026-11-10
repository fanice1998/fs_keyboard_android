# FsKeyboard 重整狀態

大綱：`docs/superpowers/plans/2026-11-08-rebuild-outline.md`

中斷後從第一個不是 `done` 的期繼續。該期若已有計畫檔，做第一個未勾選的任務。

| 期 | 狀態 | 計畫 | 備註 |
| --- | --- | --- | --- |
| A 移除舊引擎 | done | `docs/superpowers/plans/2026-11-08-phase-a-remove-engines.md` | 310 測試通過。librime jniLibs 不再打進 APK |
| E 只建 aarch64 | done | `docs/superpowers/plans/2026-11-08-phase-e-arm64.md` | APK 與 Rust 目標只留 arm64 |
| B 拆控制層 | done | `docs/superpowers/plans/2026-11-08-phase-b-split-controller.md` | 310 測試通過 |
| C 輸出欄契約 | done | `docs/superpowers/plans/2026-11-08-phase-c-editor.md` | `EditorDeleteTest` 與 `BackspaceSelectionTest` 通過 |
| D 詞庫格式與自訂詞 | done | `docs/superpowers/plans/2026-11-08-phase-d-lexicon.md` | 310 測試通過。啟動不跑轉換器 |
| F 延遲 | done | `docs/superpowers/plans/2026-11-08-phase-f-latency.md` | 只盤點既有量測。沒有裝置數字，未改按鍵路徑 |
| G 外觀 | done | `docs/superpowers/plans/2026-11-08-phase-g-theme.md` | 紙色／墨色可切換。底圖與按鍵動畫未做 |

## 紀錄

- E Task 1：`app/build.gradle.kts` 的 `abiFilters`、`android.yml`、`release.yml`、`tools/build-lexicon.sh`。未跑 Gradle。搜尋確認 Android 目標不再含 `x86_64`；`Linux-x86_64` 與 `Darwin-x86_64` 是建置機，留著。
- A：刪除 Rime 與 Rust 注音查詢，控制器只留千秋分支。`JAVA_HOME=/tmp/temurin21/jdk-21.0.13+9/Contents/Home ./gradlew :app:testDebugUnitTest --offline` 為 310 測試、0 失敗。組字還開著但對不上既有退格分支時直接返回，不刪欄位文字。這沿用「有組字就不刪已上屏文字」。
- A 補：拿掉 `sourceSets` 裡的 `third_party/librime/jniLibs`。CMake 已不連結 librime，那些 `.so` 不該再進 APK。這一行沒有重跑單元測試。
- B Task 1–3：抽出 `EditorSession`、`ZhuyinCompositionSession`（`Composer`）、`CandidatePresenter`、`Lexicon`/`AssetLexicon`。未 commit。`JAVA_HOME=/tmp/temurin21/jdk-21.0.13+9/Contents/Home ./gradlew :app:testDebugUnitTest --offline` 為 310 測試、0 失敗。英文組字仍在控制器。
- C：`EditorSession.deleteOne()`。換行若 `deleteSurroundingText` 沒改文字，改送 `KEYCODE_DEL`。兩種都沒改時，同一段游標前文字不再呼叫 `deleteSurroundingText`，連按停止。讀不到游標前文字時仍只做一次 surrounding delete。`EditorDeleteTest` 與 `BackspaceSelectionTest` 通過。
- D：打包把 `data/chiakey/ChiaKeySource.db` 複製到 `app/build/generated/chiakeyAssets/chiakey/ChiaKeySource.db`。缺檔時任務失敗，訊息含 `Missing data/chiakey/ChiaKeySource.db`。`-PbundleChiaKeyLexicon=false` 不複製，`mergeDebugAssets` 仍成功。啟動若 assets 有檔且 `files/chiakey/ChiaKeySource.db` 還沒有，整檔複製再 `open()`，不刪 `writable/`。拿掉下載與「詞庫尚未下載」同意片。`fslexicon 1` 在 `files/lexicon/fslexicon.txt`。組出的中文詞寫 `user`。`ChiaKeyLexiconConverter` 走 `ChiaKeyUnigramScan`，啟動時不呼叫。下一個詞仍讀 `lexicon.bin`。`JAVA_HOME=/tmp/temurin21/jdk-21.0.13+9/Contents/Home ./gradlew :app:testDebugUnitTest --offline` 為 310 測試、0 失敗。未 commit。
- D 補：下載移除後，`AndroidManifest.xml` 不再宣告 `INTERNET`。`RECORD_AUDIO` 留著。
- F：盤點 `FrameLatencyProbe.keyHandled`、`CandidatePresenter` 的 lookup／infer 日誌、`RerankDecider` 的超時退回。沒有裝置上記下的延遲數字，所以按鍵路徑沒有改。
- G：`KeyboardTheme` 紙色與墨色。偏好 `keyboard_theme`。設定頁「墨色按鍵」。`KeyboardView` 讀色票、圓角、邊框。`pressMs` 只改浮標停留；墨色浮標改讀該樣式的顏色。`imagePath` 不解碼。候選列仍用 `KeyboardPalette.of`。色票改為整數，單元測試不再呼叫 `Color.parseColor`。`JAVA_HOME=/tmp/temurin21/jdk-21.0.13+9/Contents/Home ./gradlew :app:testDebugUnitTest --offline` 為 313 測試、0 失敗。未 commit。
- 2026-11-10：程式地圖按當時的原始碼重寫。按鍵改由 `KeyState` 分派，英文組字在 `EnglishCompositionSession`。未 commit。

## 程式地圖

核對於 2026-11-10 的工作樹。codegraph 用「Controller」搜尋時會帶出 `third_party/chiakey` 的 OSX `CVApplicationController`，那不是這個鍵盤。

- `defaultConfig.ndk.abiFilters` 在 `app/build.gradle.kts`，只有 `arm64-v8a`。CMake 仍由 AGP 依這個過濾器編 `libfs_chiakey.so`。
- `tools/build-lexicon.sh` 把 `libfs_lexicon.so` 複製到 `app/src/main/jniLibs/arm64-v8a/`，並產出 `app/src/main/assets/lexicon/lexicon.bin`。
- `CopyChiaKeyLexiconTask`（`app/build.gradle.kts`）在 `bundleChiaKeyLexicon` 不為 false 時，由 `variant.sources.assets.addGeneratedSourceDirectory` 接到 debug／release 的 assets。產出在 `build/generated/chiakeyAssets/`，不進 `app/src/main/assets`。
- `ChiaKeySession` 實作 `ZhuyinEngine`。`ChiaKeyRuntime.session` 在行程裡只留一個。`FsKeyboardService.onCreate` 取出它，傳進 `CandidateController`。`onDestroy` 只 `ChiaKeyRuntime.detach` 詞庫監聽，不關閉這個 session。
- `startChiaKey` 在 `fskeyboard-chiakey-open` 上先 `BundledChiaKeyLexicon.install`，再 `session.open()`。目的檔已存在就返回，不碰 `writable/`。索引在 `fskeyboard-chiakey-index`：先 `ChiaKeyUnigramScan.read` 的 `syllables = 1..1`，經 `ChiaKeyRuntime.offer(done = false)` 發布；再 `syllables = 1..4`，`done = true`。
- `UnavailableZhuyinEngine` 是控制器建構式的預設引擎。`ScriptedEngine` 只在 `ChiaKeyTypingTest`。
- `AssetLexicon.ensureContinuations` 開 `assets/lexicon/lexicon.bin`，交給 `WordContinuations.open`。`CandidateController.start` 經 `loadWordContinuations` 呼叫它。控制器不自己開這個檔。`Lexicon.following` 是下一個詞。`partialCandidates` 讀 `PartialLexicon`。
- `publishPartialLexicon` 換掉這份 `PartialLexicon`。模式是注音時再呼叫 `zhuyin.afterPartialLexicon()`。
- `FsLexicon` 讀寫 `files/lexicon/fslexicon.txt`，第一行 `fslexicon 1`。方法是 `rows`、`addUser`、`removeUser`、`replaceBuiltin`。`ZhuyinHost.rememberPhrase` 先 `CandidatePreference.remember`，再 `rememberUserRow` 的 `addUser`。升降權重仍是 `CandidatePreference`。
- `ChiaKeyLexiconConverter.convert` 呼叫 `ChiaKeyUnigramScan.read`，再 `replaceBuiltin`。生產啟動不呼叫它，只有 `FsLexiconTest` 呼叫。下一個詞仍是 `lexicon.bin`。
- `CandidatePresenter.publish` 在注音組字且 `pipeline.willScore` 時呼叫 `CandidatePipeline.presentAsync`。控制器不呼叫 `presentAsync`。`apply` / `noteShown` 在 presenter。`SLOW_LOOKUP_MS` 是 5。注音組字經 `ZhuyinHost` 呼叫 `promote` / `remember`。
- `EditorSession` 是唯一呼叫 `InputConnection` 的 `commitText`、`setComposingText`、`finishComposingText`、`deleteSurroundingText`、`sendKeyEvent`、`setSelection` 的型別。對外方法是 `commitText`、`setComposing`、`finishComposing`、`clearUnderline`、`deleteOne`、`sendDownUp`、`setSelection`。剪貼簿與控制器都呼叫這個 session。
- `deleteOne()` 是沒有組字、沒有選取時的退格。`deliverBackspace` 在狀態回 `Continue` 時經 `deleteFieldBeforeCaret` 呼叫它。`KeyboardView` 的連按讀 `CandidateController.backspaceProgress()`，那個值是 `deliverBackspace` 寫下的 `backspaceMoved`。
- `CandidateController` 是路由。公開建構式與 `onKey` 簽名沒變，由 `FsKeyboardService` 建立。四個 `KeyState` 在建構式裡直接建立：`ZhuyinStateController`、`LatinStateController`、`SymbolStateController`（`SYMBOLS` 與 `SYMBOLS_MORE` 共用）、`NumberStateController`。`latin_computer_keys` 與 `zhuyin_computer_layout` 不是狀態，只改變 `KeyboardLayouts` 送出的 `KeyAction`。
- `KeyFate` 只有 `Consumed` 與 `Continue`。`onKey` 先把 `KeyAction.Computer` 交給 `onComputer`。標點走 `deliverPunctuation`，退格走 `deliverBackspace`，兩者都在 `state.handle` 之前。空白、字元、注音先 `hideClipboard`，再 `stateFor(mode).handle`。`Continue` 才進 `deliverUnconsumed`。
- 電腦鍵上的符號也走 `deliverPunctuation`。修飾鍵、方向鍵與 Fn 留在路由。`fnFunctionKeyCode` 在 `ComputerKeys.kt`，由 `sendModified` 套用。一次性 Shift 在 `KeyboardView.dispatch` 裡消耗，進不到 `onKey`。`KeyState` 沒有 `sendDownUp`，也沒有 `InputConnection`。
- `EnglishCompositionSession` 由路由持有，拉丁與符號控制器共用。它不讀 `KeyboardMode`。呼叫端傳 `allowCompose`：拉丁是 `EnglishInputPolicy.shouldCompose`，符號頁是 `base == LATIN` 且同一道閘。`englishWorker` 的執行緒名是 `fskeyboard-english`。
- `ZhuyinCompositionSession` 實作 `Composer`。`keyboard/ZhuyinComposer.kt` 仍是音節緩衝。`onKey` 只吃注音鍵。空白、退格、Enter 由 `ZhuyinStateController` 轉呼叫同一個 session。標點不到這個控制器。
- `host.mode() != ZHUYIN` 的檢查在 `ZhuyinCompositionSession` 的 `onKey`、`onSpace`、`onBackspace`、`onEnter`。
- `modeLeave` 是 `setMode` 的離開表。`NUMBER` 與切到同一個模式時，`setMode` 在呼叫前返回；函式對這兩個輸入仍定義為雙方 `KEEP`。拉丁與符號頁互切是英文 `KEEP`。`SYMBOLS` 與 `SYMBOLS_MORE` 互切，以及離開注音，是 `COMMIT_TYPED`。沒有 `DROP`。套用順序是英文、`clearModifiers`、注音 `finish`、`onLetterMode`、`base`、`mode`、清空候選。
- `typingEnglish` 仍是 `mode == LATIN`。`editorInfo()` 留在控制器。`beginCursor` 先讀英文的 `caretTarget()`（長度是詞的長度），沒有英文詞再讀注音。注音游標跨度用路由組出的 `compositionLength()`。
- `KeyboardTheme.of` 只在 id 為 `ink` 時回墨色，其餘回紙色。`KeyboardPreferences.keyboardTheme` 讀 `keyboard_theme`。`FsKeyboardService` 在建立鍵盤與 `onStartInputView` 套用。`KeyboardView.onDraw` 呼叫 `theme.palette`。`showKeyPreview` 把同一組色票交給 `KeyPreviewPopup.palette`。組字與 `CandidateController` 不讀 `KeyboardTheme`。
- 已不在主程式：`RimeEngine`、`rime_jni.cpp`、`RustZhuyinLexicon`、`IdleZhuyinLexicon`、`ZhuyinLexicon`、`InputEngine`、`LexiconClient`、`LexiconInstaller`、`LexiconDownload`、`MinimalJson`。
- codegraph 不索引 workflow 與 Gradle ABI。
