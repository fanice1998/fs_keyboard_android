# 外觀 Implementation Plan

> **For agentic workers:** 任務用 checkbox。不要 commit。

**Goal:** `KeyboardTheme` 是外觀資料。紙色與墨色能切換，重開後保持。鍵盤按鍵從樣式讀顏色、圓角與邊框。浮標的停留與顏色也從樣式讀。組字與詞庫不讀樣式。

**Architecture:** 樣式只進 `KeyboardView` 與 `KeyPreviewPopup`。`pressMs` 為 0 時浮標沿用 `KeyPreview.LINGER_MS`（50ms）。紙色的色票與改動前的 `KeyboardPalette` 相同。

**Tech Stack:** Kotlin、JUnit。色票用整數，單元測試不呼叫 `Color.parseColor`。

**Spec:** `docs/superpowers/plans/2026-11-08-rebuild-outline.md` 期 G。

## Global Constraints

- 切換外觀不改變候選順序與上屏文字。
- 組字與詞庫的原始碼不出現 `KeyboardTheme`。
- 沒有要求就不要 commit。

## 這一期沒有做的

- `imagePath` 預設空字串。鍵盤不解碼點陣圖。
- `pressMs` 只改浮標鬆手後停留多久，沒有按鍵面的縮放或變色動畫。
- 候選列、語音畫面與權重選單仍用 `KeyboardPalette.of(night)`，不跟墨色走。紙色按鍵的色票與先前相同。

## Review Focus

- `KeyboardThemeTest`：未知 id 回到紙色；墨色有邊框且 `pressMs > 0`；控制器與注音組字不讀樣式。
- `ImeSettingsContractTest`：設定頁有「墨色按鍵」，服務在建立鍵盤時套用 `keyboardTheme()`。

---

### Task 1: 兩套樣式

- [x] **Step 1:** `KeyboardTheme.paper()` 與 `ink()`。`of` 只在 id 為 `ink` 時回墨色。偏好鍵 `keyboard_theme`。
- [x] **Step 2:** 設定頁開關「墨色按鍵」。服務與設定頁預覽把樣式交給 `KeyboardView`。
- [x] **Step 3:** `KeyboardView` 用樣式的色票、圓角與邊框繪製按鍵。邊框寬度為 0 時不畫。

### Task 2: 浮標收進樣式

- [x] **Step 1:** `pressMs > 0` 時浮標停留改用該毫秒數，否則用 `KeyPreview.LINGER_MS`。
- [x] **Step 2:** 顯示浮標前把 `theme.palette` 交給 `KeyPreviewPopup`。未設定時仍用 `KeyboardPalette.of`。

### Task 3: 測試

- [x] **Step 1:** `KeyboardThemeTest` 與 `ImeSettingsContractTest` 通過。
- [x] **Step 2:** `:app:testDebugUnitTest --offline` 全數通過。
