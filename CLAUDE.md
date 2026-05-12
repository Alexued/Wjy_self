# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AIAssistant (AI伴学) is an Android app for Chinese civil service exam (公务员考试) study. It overlays a floating ball on screen; tapping it captures a screenshot, runs OCR, sends the question to an AI model (OpenAI-compatible API, default DeepSeek), and displays structured answer analysis in a floating result card. Supports 7 行测 question types including vision-based 图形推理.

## Build Commands

```bash
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK (minified with ProGuard)
./gradlew compileDebugKotlin     # Compile only (fastest feedback)
./gradlew installDebug           # Build and install to connected device
```

## Architecture

**Single-Activity + Fragments + Foreground Service**

- `MainActivity` hosts 4 fragments via BottomNavigationView: Home (settings/toggles), AiModel (model CRUD), OcrModel (OCR config), PromptManage (per-type prompt editor)
- `ScreenCaptureService` is the core foreground service managing MediaProjection, floating overlays, and the AI pipeline. It's split across 6 files using Kotlin extension functions (prefixed with `+`):
  - `ScreenCaptureService.kt` — service lifecycle, MediaProjection, AI orchestration
  - `+Ball.kt` — floating ball (main + silent-search) drag/click/progress
  - `+Capture.kt` — ImageReader screenshot pipeline, area selection overlay
  - `+Result.kt` — result card lifecycle, JSON parsing, HTML formatting, export
  - `+Renderers_Huasheng.kt` — teacher-specific JSON-to-View renderers for all 7 types
  - `+Notify.kt` — notification channel management
- `AreaSelectionOverlay.kt` — drag-resizable screenshot area overlay (not a service extension)
- `ResizeContainer.kt` / `ZoomableLayout.kt` — reusable pinch-zoom + resize container views

**AI/OCR Pipeline:**
- `OpenAIApiService` — non-streaming OpenAI-compatible HTTP client (text + vision), with connection pre-warming and request cancellation
- `MultiPassAnalyzer` — multi-round analysis orchestrator with 4 strategies:
  - `single` — 1 round with 1 model
  - `standard` — R1+R2 parallel, R3 arbitrates
  - `self_check` — R1 analyzes, R2 self-checks, R3 summarizes
  - `custom_r2` — R1 + custom-prompt R2
- `CloudOcrClient` — cloud OCR (layout parsing + text recognition modes), cancelable
- Local OCR via PaddleOCR v5 (NCNN, on-device), requires OpenMP env vars set in `App.kt`

**Question Bank:**
- `QuestionBank` / `QuestionBankDb` — SQLite FTS5 full-text search with Chinese bigram tokenization + BM25 scoring + LCS similarity verification
- Bank data in `assets/bank/` — 6 JSON files in 2 categories: 判断 (定义判断, 类比推理, 逻辑判断) and 言语 (语句表达, 逻辑填空, 阅读理解)

**Configuration:**
- `AppPreferences` — all SharedPreferences access (API config, OCR mode, capture mode, etc.)
- `TeacherManager` / `TeacherConfig` — "teacher" profiles define prompts per question type, loaded from `assets/teachers/` JSON files and importable from device storage
- `ModelManager` / `AiModelConfig` — AI model CRUD, persisted as JSON in SharedPreferences
- `StrategyManager` / `StrategyScheme` — analysis strategy schemes (model assignments, thinking overrides)

**7 Question Types (QuestionType.kt):**
| Enum | Display Name | Vision? |
|---|---|---|
| `PIAN_DUAN_YUE_DU` | 片段阅读 | No |
| `LUO_JI_TIAN_KONG` | 逻辑填空 | No |
| `YU_JU_BIAO_DA` | 语句表达 | No |
| `TU_XING_TUI_LI` | 图形推理 | **Yes** |
| `DING_YI_PAN_DUAN` | 定义判断 | No |
| `LEI_BI_TUI_LI` | 类比推理 | No |
| `LUO_JI_PAN_DUAN` | 逻辑判断 | No |

`usesVision=true` types send image-based analysis (screenshot directly to AI) instead of OCR text.

## Key Technical Details

- **Package:** `com.example.aiassistant`, SDK minSdk 26, targetSdk/compileSdk 36, Kotlin 2.0.21, AGP 8.11.2
- **Permissions:** `SYSTEM_ALERT_WINDOW` (float), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` (screen capture), `INTERNET`
- **MediaProjection:** requires `FOREGROUND_SERVICE_MEDIA_PROJECTION` foreground service type (Android 14+)
- **App.kt** sets `KMP_AFFINITY=none`, `OMP_NUM_THREADS=1`, etc. at earliest point to prevent PaddleOCR NCNN crashes on Android 16
- **ProGuard** is enabled for release builds; keeps OkHttp, org.json, PaddleOCR JNI, and `AreaSelectionOverlay` model class
- **Version catalog** in `gradle/libs.versions.toml`; additional JitPack repository in `settings.gradle.kts`
- Teacher renderers are per-teacher (currently only "huasheng" for 花生); adding a new teacher requires: (1) a JSON profile in `assets/teachers/`, (2) a `ScreenCaptureService+Renderers_{name}.kt` with JSON-to-View renderers for all 7 types
- `qa_md/` and `question_bank/` at project root are data preparation scripts (Python), not part of the Android build
- `extract_bank.py` at root processes question bank data
- Tests are placeholder-only (`ExampleUnitTest`, `ExampleInstrumentedTest`)

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
