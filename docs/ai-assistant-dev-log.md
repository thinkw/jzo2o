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
- **定位**: WebClient `bodyToFlux` 按网络缓冲切分，`data: 你好\n\n` 被切成 `"data: "` 和 `"你好\n\n"`，Java 的 SSE 解析逻辑无法处理碎片
- **解决**: 架构调整 — Python 发纯文本 token（换行分隔），Java 包 SSE，彻底消除碎片化 SSE 解析

## 最终文件变更统计

| 模块 | 新增 | 修改 |
|------|------|------|
| jzo2o-ai-engine | 13 文件 | 0 |
| jzo2o-ai | 16 文件 (+5 bootstrap) | 0 |
| jzo2o-gateway | 0 | 1 行 (`bootstrap.yml`) |
| jzo2o-framework/jzo2o-mvc | 0 | 1 行 (`PackResultFilter.java`) |
| project-xzb-PC-vue3-java | 5 文件 | 1 文件 (`layouts/index.vue`, 4 行) |
| .gitignore | 1 文件 | 0 |
| docs | 2 文件 | 0 |
