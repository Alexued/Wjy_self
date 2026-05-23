# 陪你刷 - 公务员考试 AI 智能刷题助手


**陪你刷** 是一款专为中国公务员考试（行测）打造的 Android 智能学习应用。通过悬浮球截图 + OCR 识别 + AI 分析，让你在任何 App 中都能即时获取题目解析，配合题库、错题本、知识卡片、番茄钟等功能，全方位助力备考。

---

## 核心功能

### 悬浮球智能截图分析

- **一键截图识别**：点击悬浮球即可截取屏幕内容，自动 OCR 识别题目文字
- **AI 深度解析**：将识别结果发送给 AI 模型（默认 DeepSeek），获取结构化的答案解析
- **静默搜题**：小悬浮球模式，后台静默截图，点击后一次性展示结果
- **多种截图模式**：支持固定区域截图和自定义框选两种模式
- **图形推理支持**：图形推理题型自动升级为多模态识图分析

### 词典识词

- **悬浮菜单快捷入口**：长按悬浮球 → 词典识词，截图后自动识别选项中的成语/词语
- **题库联动**：优先匹配本地题库原题，从选项中提取所有词语进行词典查询
- **海量词库**：内置 30,000+ 成语、16,000+ 汉字、260,000+ 词语、14,000+ 歇后语
- **实时搜索**：支持中文、拼音、缩写多维度检索

### 题库系统

- **自行导入真题**：涵盖行测六大模块，支持自定义导入题库 JSON
- **全文检索**：FTS5 中文分词 + BM25 相关性排序，毫秒级匹配
- **模块化管理**：常识判断、言语理解、数量关系、判断推理、资料分析、政治常识
- **练习模式**：按模块、正确率区间筛选题目，支持专项训练

### 错题本

- **自动录入**：OCR 识别后自动搜索题库，命中则保存结构化数据，未命中则保存 OCR 文本
- **双解析对比**：同时展示题库解析和 AI 解析，Tab 切换查看
- **长截图导出**：将错题详情导出为长图片，保存到相册或分享

### 知识卡片 （待完成）

- **分类管理**：成语、时政、政治理论、申论、常识、人物素材、面试素材
- **关键词搜索**：快速检索知识卡片内容
- **导入导出**：支持 JSON 格式的数据备份与恢复

### 学习计划 （待完成）

- **日历视图**：按日期管理学习任务，支持优先级标记（普通/重要/紧急）
- **时间线展示**：直观展示每日任务安排
- **完成统计**：追踪每日学习进度

### 番茄钟专注

- **专注计时**：可配置的工作/休息时长，支持长休息间隔
- **应用拦截**：专注期间自动屏蔽非白名单应用，强制保持专注
- **白噪音**：内置雨声、咖啡厅、森林三种环境音 （待完成）
- **任务绑定**：将番茄钟与学习计划关联，追踪专注时长 （待完成）

### 名师模式

- **多教师支持**：内置番茄等名师解题风格，可自定义导入教师配置
- **7 种题型适配**：片段阅读、逻辑填空、语句表达、图形推理（待完成）、定义判断、类比推理、逻辑判断
- **自定义提示词**：每种题型可配置专属的 AI 分析提示词

---

## 技术架构

```
单 Activity + Fragment + 前台 Service
├── MainActivity (底部导航：首页 / AI模型 / 知识卡片 / 计划 / 番茄钟)
├── ScreenCaptureService (核心前台服务)
│   ├── +Ball.kt        悬浮球拖拽/点击/菜单
│   ├── +Capture.kt     MediaProjection 截图
│   ├── +Result.kt      结果卡片展示
│   ├── +Renderers.kt   教师风格渲染器
│   └── +Notify.kt      通知管理
├── AI 管道
│   ├── OpenAIApiService   多协议 AI 客户端 (OpenAI/Anthropic/Gemini)
│   ├── MultiPassAnalyzer   多轮分析编排器
│   ├── CloudOcrClient      云端 OCR
│   └── PaddleOCR v5        本地 OCR (NCNN)
├── 数据层
│   ├── QuestionBankDb      FTS5 全文检索题库
│   ├── DictionaryDb        词典数据库
│   ├── KnowledgeCardDb     知识卡片
│   ├── PlanDb              学习计划
│   └── PomodoroDb          番茄钟记录
└── 工具
    ├── AppPreferences      配置持久化
    ├── TeacherManager      教师配置管理
    └── ModelManager        AI 模型管理
```

### 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin 2.0.21 |
| 构建 | AGP 8.11.2, Gradle 8.x |
| 最低 SDK | Android 8.0 (API 26) |
| 目标 SDK | Android 16 (API 36) |
| HTTP | OkHttp |
| 数据库 | WCDB (腾讯 SQLite 增强) |
| OCR | PaddleOCR v5 (NCNN) |
| 布局 | Flexbox |
| AI | OpenAI / Anthropic / Gemini 兼容 API |

---

## 快速开始

### 安装

从 [Releases](https://github.com/HuiYeJi-7/Pei-NI-Shua/releases) 下载最新 APK 安装即可。

### 首次使用

1. 打开 App，授予悬浮窗和录屏权限
2. 点击「开启悬浮球」
3. 切换到任意做题 App，点击悬浮球即可截图分析
4. 长按悬浮球可打开菜单：词典识词、截图选区、切换名师等

### 设置 AI 模型

1. 进入 **AI模型** 页面
2. 填写 API 地址（默认 DeepSeek）、API Key、模型名称
3. 点击保存即可

### 自定义题库

题库 JSON 格式：
```json
[
  {
    "key": "唯一ID",
    "title": "题目内容",
    "options": [
      {"text": "A选项"},
      {"text": "B选项"}
    ],
    "answer": "A",
    "analysis": "解析内容",
    "knowledge_point": "知识点"
  }
]
```

在设置页面点击「导入题库」选择 JSON 文件即可。

---

## 构建

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本
./gradlew assembleRelease

# 安装到设备
./gradlew installDebug
```

---

## 项目结构

```
app/src/main/
├── assets/
│   ├── dictionary/        词典数据 (idiom/word/ci/xiehouyu)
│   └── teachers/          教师配置 JSON
├── java/com/example/aiassistant/
│   ├── ScreenCaptureService.kt    核心服务
│   ├── ScreenCaptureService+*.kt  服务扩展模块
│   ├── AppPreferences.kt          配置管理
│   ├── fragment/                   UI Fragment
│   ├── questionbank/               题库模块
│   ├── knowledge/                  知识卡片
│   ├── plan/                       学习计划
│   ├── pomodoro/                   番茄钟
│   ├── dictionary/                 词典
│   └── skills/                     AI 工具调用
└── res/
    ├── layout/            布局文件
    ├── drawable/          图形资源
    └── values/            字符串/颜色/样式
```

---

## 题型支持

| 题型 | AI 解析 | 视觉模式 |
|------|---------|----------|
| 片段阅读 | ✅ | ❌ |
| 逻辑填空 | ✅ | ❌ |
| 语句表达 | ✅ | ❌ |
| 图形推理 | ✅ | ✅ |
| 定义判断 | ✅ | ❌ |
| 类比推理 | ✅ | ❌ |
| 逻辑判断 | ✅ | ❌ |

> 图形推理使用视觉模式，直接将截图发送给 AI 分析，无需 OCR。
> 实际上现在没做，嘻嘻

---

## 致谢

感谢 [Linux.do](https://linux.do/) 社区的支持与鼓励。Linux.do 是一个充满活力的技术社区，汇聚了众多开发者和技术爱好者，提供了宝贵的交流平台和资源分享。本项目的成长离不开社区的帮助。

---

## 许可证

本项目仅供学习交流使用。

---

> **陪陪刷** —— 让每一道题都不白做。
