# 輸出欄契約 Implementation Plan

> **For agentic workers:** 任務用 checkbox。不要 commit。

**Goal:** 行首的換行在 `deleteSurroundingText` 刪不掉時，改送 `KEYCODE_DEL`。兩種都沒改變文字時，不再對同一段游標前文字呼叫 `deleteSurroundingText`。連按計時也停止。

**Architecture:** 決定放在 `EditorSession.deleteOne()`。控制器在沒有組字、沒有選取時呼叫它。`KeyboardView` 看到 `backspaceProgress()` 為 false 就停掉連按。

**Tech Stack:** Kotlin、JUnit。

**Spec:** `docs/superpowers/plans/2026-11-08-rebuild-outline.md` 期 C。

## Global Constraints

- 刪除順序仍是組字、選取、然後編輯器一個單位。
- 讀不到游標前文字的欄位維持只呼叫一次 `deleteSurroundingText`，避免誤送第二次刪除。
- 不改詞庫、不改外觀。
- 沒有要求就不要 commit。

## Review Focus

- 一般字元被 `deleteSurroundingText` 刪掉時，不再送 `KEYCODE_DEL`。
- 選取仍用 `commitText("")` 清掉。既有 `BackspaceSelectionTest`。

---

### Task 1: deleteOne

- [x] **Step 1:** `EditorSession.deleteOne()` 實作契約。測試在 `EditorDeleteTest`。
- [x] **Step 2:** 控制器改呼叫 `deleteOne()`。`KeyboardView` 在退格沒有進展時停止連按。
- [x] **Step 3:** `EditorDeleteTest` 與 `BackspaceSelectionTest` 通過。
