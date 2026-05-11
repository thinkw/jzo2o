# 云岚到家 AI 助手 — 开发日志

## 2026-05-09 需求对齐与方案设计

### 需求确认
- 为机构端和服务人员端添加 AI 助手
- Phase 1：纯聊天 + 流式输出，Phase 2：升级 LangChain/LangGraph Agent
- Python 模块独立于现有 Java 微服务，命名遵循 `jzo2o-*` 规范
- 前端嵌入悬浮聊天窗口，先上机构端 PC
- 使用兼容 OpenAI 接口的 API

### 架构决策

| 决策点 | 选项 | 结论 |
|--------|------|------|
| 整体架构 | Gateway直连 / Java中转 | **Java 中转**，职责分离 |
| Python 框架 | FastAPI / Flask / Django | **FastAPI**，异步+流式原生支持 |
| 通信协议 | HTTP REST / gRPC | **HTTP REST + SSE**，简单可靠 |
| 对话存储 | MySQL / Redis / MySQL+Redis | **MySQL**，Phase 2 Redis 留给 LangGraph checkpointing |
| 模块命名 | jzo2o-assistant / jzo2o-ai | **jzo2o-ai** (Java) + **jzo2o-ai-engine** (Python) |

### 职责边界定稿
```
Java (jzo2o-ai):     鉴权、敏感词初滤、业务数据预取、对话持久化、流量整形
Python (AI-Engine):   Prompt编排、LangChain运行、工具调用决策、LLM适配
```

## 2026-05-10 编码实施

### Step 1 — 创建 Python 引擎 jzo2o-ai-engine
- 搭建 FastAPI 项目骨架：`main.py`、`app/api/chat.py`、`app/core/`、`app/schemas/`
- 实现 `POST /chat/completions` 流式接口
- 封装 OpenAI 兼容客户端 `llm_client.py`
- 配置管理支持环境变量 + Nacos（Phase 2 启用）
- **13 个文件**，未改动任何现有模块

### Step 2 — 创建 Java 模块 jzo2o-ai
- 搭建 Spring Boot 项目：`AiApplication.java`、pom.xml
- 实现 `ChatController` → `ChatServiceImpl` → `AiEngineClient` 调用链
- `AiEngineClient` 使用 `WebClient` 消费 Python 纯文本 token 流
- `ChatServiceImpl` 用 `SseEmitter` 将 token 包装为 SSE 发前端
- MyBatis-Plus 持久化 `ai_chat_record` 表
- 配置文件对齐现有模块模式：undertow、Nacos env var、shared-configs
- **16 个文件 + 5 个 bootstrap-*.yml**，未改动任何现有模块

### Step 3 — Gateway 路由配置
- `jzo2o-gateway/bootstrap.yml` 新增 1 条路由：`/ai/** → lb://jzo2o-ai`，带 Token 过滤器

### Step 4 — 前端悬浮聊天窗
- 新增 `src/api/chat.ts`：`fetch` + `ReadableStream` 消费 SSE
- 新增 4 个 Vue 组件：`ChatFloatingButton`、`ChatWindow`、`ChatMessageList`、`ChatInput`
- 修改 `src/layouts/index.vue`：引入组件 + `showChat` ref（4 行改动）

### Step 5 — 配置文件对齐
- 读取 `foundations`、`market` 模块的配置文件，按相同模式重写 `jzo2o-ai` 配置
- 删除多余的 `application.yml`，数据源走 Nacos `shared-mysql.yaml`
- 新增 `bootstrap-dev/test/prod/local.yml`

### Step 6 — .gitignore 整合
- 合并 10 个子模块 `.gitignore` 到根目录
- 覆盖范围：Java/Maven、IDEA/STS/VS Code、Node、Python、日志、OS

## 2026-05-11 Markdown/Mermaid/LaTeX 渲染支持

### Step 1 — 移除 ExpBall 悬浮蓝球
- `App.vue` 删除 `ExpBall` 组件引入及使用
- 删除 `src/components/expBall/ExpBall.vue`

### Step 2 — 安装渲染依赖
- `npm install markdown-it katex mermaid` + `@types/markdown-it`
- markdown-it: Markdown → HTML 解析
- KaTeX: LaTeX 数学公式渲染
- mermaid: 流程图/时序图渲染

### Step 3 — 创建渲染引擎 `src/utils/markdown.ts`
- markdown-it 实例配置（`html: false` 防 XSS、`linkify: true`、`breaks: true`）
- 自定义 fence 渲染规则：`mermaid` 语言生成 `<div class="mermaid">` 占位
- LaTeX 预处理：`$$...$$` 块级 + `$...$` 行内 → KaTeX HTML → 占位符保护 → markdown-it 渲染 → 恢复 KaTeX HTML
- 占位符设计：纯字母数字 `KATEXBLOCK0000END`，避免与 markdown 语法冲突

### Step 4 — 创建 Vue 渲染组件 `src/components/chat/ChatMarkdown.vue`
- Props `content: string`，`v-html` 渲染 HTML
- `onMounted` 初始化 mermaid，`watch` 监听 content 变化
- 每次渲染后 `mermaid.render()` 将 `.mermaid` div 转为 SVG
- 完整 CSS 样式：代码块深色主题、表格、引用、Mermaid 容器、KaTeX 排版

### Step 5 — 修改 ChatMessageList 使用 ChatMarkdown
- 用户消息和 AI 回复的 `{{ msg.content }}` → `<ChatMarkdown :content="msg.content" />`
- 流式输出的 `{{ streamingContent }}` → `<ChatMarkdown :content="streamingContent" />`
- 保留角色头像、加载状态等现有逻辑

### Step 6 — Python→Java→前端 换行符传输问题排查与修复

#### 问题发现
前端 AI 对话只有粗体/斜体生效，标题、列表、引用、代码块、Mermaid、LaTeX 全部不显示。

#### 排查过程

**第一轮：怀疑前端渲染引擎**
- Node.js 测试 markdown-it + KaTeX 管道：全部语法正常输出 HTML
- 结论：渲染引擎本身没有问题

**第二轮：Elements 面板排查**
- 发现所有块级 markdown 语法挤在一行，无换行
- `<code>` 标签内容显示 `mermaidgraphLRA`（mermaid 和 graph 之间无空格/换行）
- 粗体/斜体（行内语法）正常，块级语法（`#`、`-`、`>`、`` ``` ``）全部失效
- 结论：`renderMarkdown()` 收到的内容没有换行符

**第三轮：Java 端定位**
- 原始协议：Python 用 `\n` 做 token 分隔符，Java 按 `\n` 拆分后 trim + filter empty
- **根因 1**：LLM 输出内容本身包含 `\n`（markdown 换行），与 token 分隔符同字符，Java `split("\n")` 无法区分，内容中的 `\n` 被当作分隔符丢弃
- **根因 2**：`.map(String::trim).filter(s -> !s.isEmpty())` 进一步丢弃了空白行（段落分隔）

**第四轮：尝试修复分隔符**
- 尝试 1：改用 `\x1e`（ASCII RS）作为分隔符 → WebClient/SSE 传输层问题导致渲染异常
- 尝试 2：Python 不加分隔符，Java 原样透传 → WebClient 行解码器把 `\n` 当行分隔符吃了，Java 日志显示所有 chunk `hasNewline=false`
- 尝试 3：Java 端把 `\n` 转义为 `\\n` 字面量，前端解转义 → LaTeX 命令 `\nabla`、`\neq` 中的 `\n` 被前端正则误替换

**第五轮：Java 日志确认行解码器行为**
- 在 Java `ChatServiceImpl` 添加 `rawChunk` 日志
- 发现每个 Flux 元素恰好是原始内容的一行（`"```mermaid"` → `"graph TD"` → `"    A --> B"`）
- WebClient 的 `bodyToFlux(String.class)` 使用 Reactor Netty 行解码器，`\n` 被当作行边界丢弃，Java 代码从未收到 `\n` 字符

#### 最终解决方案

**协议改造：零分隔符 + 行末补 `\n`**

三层改动：

| 层 | 改动 | 说明 |
|----|------|------|
| Python `chat.py` | `yield content_chunk`（原样，不加任何分隔符） | LLM token 直接透传 |
| Java `ChatServiceImpl.java` | `emitter.send(SseEmitter.event().data(rawChunk))`（原样发送，不做 split/trim/转义） | 每个 SSE event 是原始内容的一行 |
| 前端 `chat.ts` | `callbacks.onChunk(content + '\n')`（每行末尾追加 `\n`） | 前端负责还原原始文档结构 |

**为何这个方案正确：**
- 每个 SSE event 是单行纯文本，不含控制字符，SSE 协议零冲突
- 没有任何转义/解转义步骤，LaTeX `\nabla` 等命令不受影响
- 空白行（段落分隔）自然保留为 `data:` → 前端追加 `\n` → 输出 `"\n"`（空行）
- Mermaid 代码块内换行正确保留，SVG 渲染正常

## 问题记录

### #1 前端白屏 (已解决)
- **时间**: 2026-05-10
- **现象**: 机构端 PC 空白一片
- **定位**: `ChatMessageList.vue` 中 `defineProps` 返回值未接收，`<script>` 内访问 props 为 `undefined`
- **解决**: `const props = defineProps<{...}>()`, watcher 改用 `props.messages`、`props.streamingContent`

### #2 未使用的 uuid 导入 (已解决)
- **时间**: 2026-05-10
- **现象**: `ChatWindow.vue` 导入了未安装的 `uuid` 包
- **解决**: 删除 `import { v4 as uuidv4 } from 'uuid'`，已有内联 `generateSessionId()`

### #3 前端请求 404 (已解决)
- **时间**: 2026-05-10
- **现象**: 聊天窗口显示 `[错误] HTTP 404`
- **定位**: 项目 axios 统一加 `/api` 前缀走 Vite 代理，但 `fetch` 调用没加
- **解决**: `chat.ts` 中当 `baseHost` 为空时自动拼 `/api` 前缀

### #4 Python 路由 404 (已解决)
- **时间**: 2026-05-10
- **现象**: Python 日志 `POST /chat/completions 404 Not Found`
- **定位**: `include_router(prefix="/chat")` + `@router.post("/chat/completions")` = `/chat/chat/completions`
- **解决**: 路由改为 `@router.post("/completions")`

### #5 AI 回复不显示 (已解决)
- **时间**: 2026-05-10
- **现象**: 后端全部 200，前端 Network Content-Type 正确，但消息区无内容
- **定位**: `PackResultFilter` 用 `ResponseWrapper` 劫持了 `SseEmitter` 的异步输出
- **解决**: `PackResultFilter` 跳过条件新增 `/chat/completions`

### #6 前端仍无内容 (已解决)
- **时间**: 2026-05-10
- **现象**: Filter 已跳过，但 `data:` 行后面无实际内容
- **定位**: WebClient `bodyToFlux` 按网络缓冲切分，SSE 碎片化解析失败
- **解决**: 架构调整 — Python 发纯文本 token，Java 包 SSE

### #7 Markdown/Mermaid/LaTeX 全部不渲染 (已解决)
- **时间**: 2026-05-11
- **现象**: 只有粗体/斜体生效，所有块级语法（标题、列表、引用、代码块）及 Mermaid、LaTeX 均不显示
- **根因**: Java 端 `split("\n").map(trim).filter(not empty)` 把 LLM 输出中的 markdown 换行符当作 token 分隔符全部丢弃；后续修复中发现 WebClient `bodyToFlux(String.class)` 使用行解码器，`\n` 在到达 Java 层之前就已被当作行边界丢弃
- **定位过程**: 
  1. Node.js 测试确认 markdown-it + KaTeX 管道正常 → 排除渲染引擎
  2. Elements 面板发现内容无换行 → 确认换行符丢失
  3. Java 日志确认所有 chunk `hasNewline=false` → 定位行解码器行为
  4. 前端 Console 日志双向对比 → 确认传输链路
- **解决**:
  1. Python 去掉所有 token 分隔符，`yield content_chunk` 原样输出
  2. Java 去掉 `splitTokens()` 及所有 trim/filter 逻辑，`SseEmitter` 直接发送每行内容
  3. 前端在每行 SSE 数据后追加 `\n` 还原原始文档结构
  4. 避免任何转义/解转义步骤，彻底消除与 LaTeX/Markdown 语法的冲突
- **教训**: 传输层不要对内容做任何假设性的拆分或转义；行解码器是一个隐式的协议层，设计协议时必须明确其行为；分隔符方案天然有冲突风险，追加式（在边界外补回丢失的结构信息）比转义式更稳健

### #8 LaTeX `\\n` 转义方案冲突 (已解决)
- **时间**: 2026-05-11
- **现象**: 采用 `\n` 转义方案（Java `replace("\n", "\\n")` + 前端 `replace(/\\n/g, '\n')`）后，LaTeX 命令如 `\nabla`、`\neq` 被前端正则误替换
- **根因**: LaTeX 命令中的 `\n`（反斜杠+n）与换行转义序列 `\\n` 同形，前端 `/\\n/g` 正则无法区分
- **解决**: 放弃全部转义方案，采用行末追加 `\n` 方案（见 #7 最终解决方案）

### #9 ExpBall 悬浮蓝球残留 (已解决)
- **时间**: 2026-05-11
- **现象**: 页面右下角有一个蓝色渐变悬浮球（项目说明浮窗），与 AI 悬浮球功能重叠
- **解决**: 从 `App.vue` 移除 `ExpBall` 组件，删除 `src/components/expBall/ExpBall.vue`

## 最终文件变更统计

| 模块 | 新增 | 修改 |
|------|------|------|
| jzo2o-ai-engine | 13 文件 | 1 (`app/api/chat.py`) |
| jzo2o-ai | 16 文件 (+5 bootstrap) | 1 (`ChatServiceImpl.java`) |
| jzo2o-gateway | 0 | 1 行 (`bootstrap.yml`) |
| jzo2o-framework/jzo2o-mvc | 0 | 1 行 (`PackResultFilter.java`) |
| project-xzb-PC-vue3-java | 7 文件 | 3 文件 (`layouts/index.vue`, `App.vue`, `ChatMessageList.vue`) |
| .gitignore | 1 文件 | 0 |
| docs | 2 文件 | 0 |
