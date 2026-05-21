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

- `MainActivity` hosts fragments via BottomNavigationView: Home (settings/toggles), AiModel (model CRUD), KnowledgeCard (flashcards), Plan (study planner), Pomodoro (focus timer)
- `ScreenCaptureService` is the core foreground service managing MediaProjection, floating overlays, and the AI pipeline. It's split across 6 files using Kotlin extension functions (prefixed with `+`):
  - `ScreenCaptureService.kt` — service lifecycle, MediaProjection, AI orchestration
  - `+Ball.kt` — floating ball (main + silent-search) drag/click/progress, ball menu, dictionary overlay. Main ball is a 60dp semi-transparent dark circle with white Lens icon; small ball (silent search) is 36dp matching style. Both use `TYPE_APPLICATION_OVERLAY` system-level window.
  - `+Capture.kt` — ImageReader screenshot pipeline, area selection overlay
  - `+Result.kt` — result card lifecycle, JSON parsing, HTML formatting, export
  - `+Renderers_Huasheng.kt` — teacher-specific JSON-to-View renderers for all 7 types
  - `+Notify.kt` — notification channel management
- `MediaProjectionConsentActivity` — transparent Activity for re-requesting MediaProjection permission when the token expires (Android 14+ tokens are single-use). Has `isShowing` guard to prevent duplicate dialogs. On denial, broadcasts `ACTION_CONSENT_DENIED` to reset the service's `isRequestingConsent` flag.
- `AreaSelectionOverlay.kt` — drag-resizable screenshot area overlay (not a service extension)
- `ResizeContainer.kt` / `ZoomableLayout.kt` — reusable pinch-zoom + resize container views

**Knowledge Cards** (`knowledge/` package):
- `KnowledgeCardDb` — SQLite store for flashcard-style knowledge items, with categories (成语, 时政, 政治理论, 申论, 常识, 人物素材, 面试素材), pagination, keyword search, import/export
- `KnowledgeCardManager` — business logic layer over the DB
- `KnowledgeCardListActivity` / `KnowledgeCardEditActivity` — dedicated Activities (not fragments) for browsing and editing cards
- `KnowledgeCardFragment` — embeddable card viewer

**Study Plan** (`plan/` package):
- `PlanDb` — SQLite store for date-based study tasks with priorities (普通/重要/紧急), calendar marking, daily completion stats
- `PlanManager` — business logic layer
- `PlanFragment` + `TimelineAdapter` — calendar + timeline UI for task management

**Pomodoro Timer** (`pomodoro/` package):
- `PomodoroTimer` — state machine (IDLE/FOCUS/SHORT_BREAK/LONG_BREAK/PAUSED) with CountDownTimer
- `PomodoroManager` / `PomodoroDb` — session persistence, daily stats aggregation
- `PomodoroFragment` — main UI with circular progress ring, task binding, white noise
- `PomodoroSettingsActivity` — timer durations, behaviors, per-task whitelist management
- `PomodoroStatsActivity` — stats + history with date-grouped list
- `AppBlockerService` — foreground service that polls foreground app via `UsageStatsManager` (queryEvents + queryUsageStats with 3-second recency filter) and shows a full-screen blocking overlay for apps not in the task's whitelist. Overlay persists until user acts via buttons; ignores system/launcher packages
- `AppBlockerSettingsActivity` — per-task whitelist editor (system apps pre-selected by default, user can uncheck)
- `WhiteNoisePlayer` — MediaPlayer wrapper for ambient sounds (rain/cafe/forest)
- `PomodoroTimerHolder` — saves/restores timer state across configuration changes
- Whitelist stored per-task in SharedPreferences as `task_wl_{taskTitle}`; `AppPreferences.SYSTEM_DEFAULT_PACKAGES` defines default system app set

**Dictionary** (`dictionary/` package):
- `DictionaryManager` — loads 4 dictionaries from `assets/dictionary/` (idiom, word, ci, xiehouyu) into memory with 7 character/pinyin indexes. `searchAsync()` uses request-ID-based stale result discard. Results weighted by query length (short queries favor words, long queries favor idioms).
- `DictionaryAdapter` — RecyclerView adapter rendering 4 `DictItem` subtypes (IdiomItem, WordItem, CiItem, XiehouyuItem)
- `DictionaryActivity` — standalone search Activity

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
- `QuestionBankDb` — SQLite FTS5 full-text search with Chinese bigram tokenization + BM25 scoring + LCS similarity verification (threshold >= 0.4)
- `QuestionBankManager` — `search(ocrText)` returns `Question?`; `searchAsync()` for background queries
- `PracticeActivity` — question practice UI
- Bank data in `assets/bank/` — 6 JSON files in 2 categories: 判断 (定义判断, 类比推理, 逻辑判断) and 言语 (语句表达, 逻辑填空, 阅读理解)

**Wrong Questions** (`questionbank/` package):
- `WrongQuestionManager` — singleton managing wrong question lifecycle. Two entry points: `addFromBank()` (when question bank matches, stores full bank data: stem/options/answer/analysis) and `addFromOcr()` (no bank match, stores OCR text only). Images saved as PNG (lossless) to `filesDir/wrong_questions/`. All data in SharedPreferences as JSON array.
- `WrongQuestionsActivity` — list view with source badge (题库/OCR), summary preview, date. Tab filter for all/unsummarized. Long-press to delete.
- `WrongQuestionDetailActivity` — detail view with tab switching between 题库解析 and AI解析. Features: stem, options (correct answer highlighted), answer card, analysis tabs, summary notes, collapsible screenshot (default collapsed), export as long image. Edit summary and delete actions.
  - Export: renders all content (stem/options/answer/analysis) into a single bitmap, saves to gallery "AI伴学" folder, triggers share intent
  - AI解析: only for bank-matched questions; auto-detects question type from `QuestionBankManager.getQuestionModuleName()` → maps to `QuestionType` → calls `OpenAIApiService.analyzeText()` with type-specific prompt
- Recording flow: OCR text → `QuestionBankManager.search()` → if match, `addFromBank()` with structured data; else `addFromOcr()` with raw text
- `QuestionBankDb.getQuestionModuleId()` / `getModuleName()` — resolve question → module chain for AI analysis type detection

**Floating Ball Menu** (long-press):
- 5 items: Question Type selector (always), Teacher selector (always), Dictionary (`"dict"`), Toggle capture mode (`"capture_mode"`), Close ball (`"close"`)
- Last 3 are user-configurable via `AppPreferences.getBallMenuItems()`
- Menu appears to the left of ball (or right if no space), measured after inflation
- Dictionary overlay: draggable, resizable, auto-reads clipboard, 150ms debounce search

**Float Ball Click Actions** (`AppPreferences.getFloatClickAction()`):
- `CLICK_ACTION_AI_ANALYZE` (0) — default AI analysis flow
- `CLICK_ACTION_RECORD_WRONG` (1) — OCR → bank search → record to wrong question list (no AI call, no result card)

**Configuration:**
- `AppPreferences` — all SharedPreferences access (API config, OCR mode, capture mode, float ball click action, card display mode, ball menu items, dict card size, etc.)
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
- **Permissions:** `SYSTEM_ALERT_WINDOW` (float), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` (screen capture), `INTERNET`, `PACKAGE_USAGE_STATS` (app blocker), `QUERY_ALL_PACKAGES` (app list), `WAKE_LOCK` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (prevent app blocker service from being killed)
- **MediaProjection:** requires `FOREGROUND_SERVICE_MEDIA_PROJECTION` foreground service type (Android 14+). Tokens are single-use on Android 14+ — `onStop` callback cannot auto-recover, user must re-authorize via `MediaProjectionConsentActivity`.
- **AppBlockerService:** requires `FOREGROUND_SERVICE_SPECIAL_USE` type (Android 14+); foreground detection uses `queryEvents` (primary) + `queryUsageStats` with 3-second recency filter (fallback); `queryUsageStats.lastTimeUsed` has delay on MIUI — the 3-second filter prevents stale data from causing false positives
- **App.kt** sets `KMP_AFFINITY=none`, `OMP_NUM_THREADS=1`, etc. at earliest point to prevent PaddleOCR NCNN crashes on Android 16
- **ProGuard** is enabled for release builds; keeps OkHttp, org.json, PaddleOCR JNI, and `AreaSelectionOverlay` model class
- **Version catalog** in `gradle/libs.versions.toml`; additional JitPack repository in `settings.gradle.kts`
- **Floating ball layout:** `layout_float_ball.xml` (main 60dp ball) and `layout_small_ball.xml` (silent search 36dp ball). Both use `bg_float_circle.xml` (semi-transparent #80000000 oval) and `ic_visual_search.xml` (white Lens icon).
- **Key dependencies:** OkHttp (HTTP), PaddleOCR v5 (local OCR via JitPack), Flexbox (layout), WCDB (Tencent's SQLite fork, used for question bank FTS5 in `QuestionBankDb`)
- Teacher renderers are per-teacher (currently only "huasheng" for 花生); adding a new teacher requires: (1) a JSON profile in `assets/teachers/`, (2) a `ScreenCaptureService+Renderers_{name}.kt` with JSON-to-View renderers for all 7 types
- `qa_md/` and `question_bank/` at project root are data preparation scripts (Python), not part of the Android build
- `docs/pomodoro_plan.md` is the detailed design document for the pomodoro feature
- App blocker overlay uses `TYPE_APPLICATION_OVERLAY` with `FLAG_NOT_TOUCH_MODAL | FLAG_SHOW_WHEN_LOCKED | FLAG_KEEP_SCREEN_ON`; back key intercepted via `View.setOnKeyListener`; overlay persists until user taps "返回专注" or "结束专注"
- `extract_bank.py` at root processes question bank data
- Tests are placeholder-only (`ExampleUnitTest`, `ExampleInstrumentedTest`)

## Deploy

- Platform: Direct APK install (Android), no CI/CD
- `./gradlew installDebug` builds + installs in one step
- Or: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Merge method: direct commit to master
- Post-deploy: launch app on device, verify UI loads

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
