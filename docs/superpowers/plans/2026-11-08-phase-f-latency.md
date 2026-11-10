# 延遲 Implementation Plan

> **For agentic workers:** 任務用 checkbox。不要 commit。

**Goal:** 寫下按鍵路徑上已經存在的量測。沒有裝置上記下的數字之前，不改按鍵執行路徑。

**Architecture:** 量測留在現有的 `FrameLatencyProbe`、`CandidatePresenter` 日誌與 `RerankDecider` 預算。這一期只盤點，不新增探針，也不為了手感改查詞或繪製。

**Tech Stack:** 既有 Kotlin 日誌。不新增依賴。

**Spec:** `docs/superpowers/plans/2026-11-08-rebuild-outline.md` 期 F。

## Global Constraints

- 沒有量測數字的項目不改。
- 不改組字、詞庫、輸出欄契約。
- 沒有要求就不要 commit。

## 已存在的量測

- `KeyboardView.onTyped` 把觸控時間交給 `FsKeyboardService` 的 `FrameLatencyProbe.keyHandled`。第一個畫完該鍵的幀寫進 `KeyLatency.stats`。每 50 筆打一行 `key latency n=… p50=… p95=… p99=… max=…`。設定頁讀同一個緩衝。
- `CandidatePresenter` 在注音查詞 `decodeMillis >= 5` 時打 `latency lookup=`。重排回來時打 `lookup`、`infer`、`elapsed`、`fallback`。
- `RerankDecider` 在 `elapsedMillis` 超過預算時回 `TOO_SLOW`，候選順序維持原列。

這一期沒有採到裝置上的數字，所以沒有熱點可改。

## Review Focus

- 按鍵路徑的程式與這一期開始前相同。

---

### Task 1: 盤點，不改熱點

- [x] **Step 1:** 記錄上面三個量測點。
- [x] **Step 2:** 不修改 `KeyboardView` 的觸控、`CandidatePresenter` 的查詞，或 `RerankDecider`。
