# 云岚到家 — AI 智能助手模块开发实战

> 为 Spring Cloud 微服务体系引入 Python AI 引擎，从零搭建可流式输出的智能聊天助手。

## 一、背景与目标

云岚到家是家政 O2O 平台，包含机构端 PC、管理后台 PC、移动端 App 三个前端，后端为 Java Spring Cloud 微服务。目标是给机构端和服务人员端添加一个 AI 助手，初期实现流式聊天，后续扩展 LangChain / LangGraph Agent 能力。

**核心原则**：最小侵入现有系统，Python AI 引擎独立部署，Java 侧负责安全与业务数据。

## 二、方案选型

### 2.1 整体架构

| 方案 | 描述 | 优缺点 |
|------|------|--------|
| **A. Gateway 直连 Python** | 前端 → Gateway → Python FastAPI | 路径最短，但 Gateway 需处理 SSE 代理，Python 要自己搞定鉴权 |
| **B. Java 中转 (采用)** | 前端 → Gateway → Java jzo2o-ai → Python jzo2o-ai-engine | 多一跳，但职责清晰，Java 管鉴权/持久化，Python 只管 AI 推理 |
| **C. Java 内嵌 Python** | 在 Java 进程里调 Python | 部署简单，但耦合重，不适合后续 Agent 扩展 |

**选择方案 B**，理由：Java 和 Python 各司其职，后续 LangChain Agent 需要 Python 生态，独立服务便于升级和扩容。

### 2.2 职责边界

```
┌──────────────────────────────────────┐
│  jzo2o-ai (Java)                     │
│  鉴权、敏感词初滤、对话持久化、流量整形   │
│  严禁: Prompt 编排、向量检索            │
└──────────────┬───────────────────────┘
               │ HTTP/SSE
┌──────────────┴───────────────────────┐
│  jzo2o-ai-engine (Python FastAPI)    │
│  Prompt 编排、LangChain、工具调用、LLM适配│
│  严禁: 直连生产DB、执行业务操作           │
└──────────────────────────────────────┘
```

### 2.3 通信协议

| 方案 | 流式支持 | 复杂度 |
|------|---------|--------|
| **HTTP REST + SSE (采用)** | 原生支持 | 低，FastAPI StreamingResponse + Spring SseEmitter |
| gRPC | 原生支持 | 高，需 proto 定义 |

HTTP REST 完全满足流式输出需求，且 Spring Cloud Gateway (WebFlux) 已有 Reactor 基础设施。

### 2.4 AI 接口

选用兼容 OpenAI 接口的 API，`.env` 中配置 `LLM_API_BASE` 即可切换 DeepSeek / OpenAI / 本地模型等任意后端。

## 三、模块结构

```
jzo2o-ai-engine/                  ← Python FastAPI
├── main.py                       # 启动入口 (uvicorn)
├── requirements.txt              # fastapi, uvicorn, openai, python-dotenv
├── .env.example                  # LLM_API_BASE, LLM_API_KEY, LLM_MODEL
└── app/
    ├── api/chat.py               # POST /chat/completions → 纯文本 token 流
    ├── core/
    │   ├── config.py             # 环境变量 → Settings
    │   └── llm_client.py         # AsyncOpenAI 客户端, 迭代 token
    └── schemas/chat.py           # Pydantic 请求/响应模型

jzo2o-ai/                         ← Java Spring Boot
├── pom.xml                       # 父 POM: jzo2o-parent
├── AiApplication.java            # @SpringBootApplication
├── controller/consumer/ChatController.java  # POST /ai/consumer/chat/completions
├── service/impl/ChatServiceImpl.java        # 鉴权 → 持久化 → 调Python → SSE
├── client/AiEngineClient.java              # WebClient 调 Python
├── model/domain/AiChatRecord.java          # ai_chat_record 实体
├── mapper/AiChatRecordMapper.java          # MyBatis-Plus BaseMapper
├── config/WebClientConfig.java             # WebClient Bean
├── properties/AiEngineProperties.java      # Python 引擎连接配置
├── resources/bootstrap.yml                 # Nacos 服务注册
└── resources/db/migration/V1.0__create_ai_chat_record.sql
```

## 四、开发过程

### 4.1 第一步：搭 Python 引擎

核心流水线：

```
POST /chat/completions  {messages: [...]}
  → AsyncOpenAI.chat.completions.create(stream=True)
  → async for chunk: yield token + "\n"
  → StreamingResponse (text/plain)
```

关键代码 (`chat.py`)：

```python
@router.post("/completions")
async def chat_completions(request: ChatCompletionRequest):
    messages_dict = [m.model_dump() for m in request.messages]

    async def generate():
        try:
            async for content_chunk in stream_chat(messages_dict):
                yield content_chunk + "\n"  # 纯文本，换行分隔
            yield "[DONE]\n"
        except Exception as e:
            yield f"[ERROR] {str(e)}\n"

    return StreamingResponse(generate(), media_type="text/plain")
```

### 4.2 第二步：搭 Java 中转层

核心职责：从 `UserContext` 取当前用户 → 持久化用户消息 → 调 Python 引擎 → SSE 代理到前端。

```java
public SseEmitter chat(ChatRequestDTO request) {
    CurrentUserInfo user = UserContext.currentUser();
    saveRecord(user.getId(), user.getUserType(), sessionId, ROLE_USER, userContent);

    SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

    aiEngineClient.streamChat(messages).subscribe(
        rawChunk -> {
            for (String token : splitTokens(rawChunk)) {
                emitter.send(SseEmitter.event().data(token)); // SSE 包装发前端
                responseBuilder.append(token);
            }
        },
        error -> emitter.completeWithError(error),
        () -> {
            saveRecord(userId, userType, sessionId, ROLE_ASSISTANT, responseBuilder.toString());
            emitter.complete();
        }
    );
    return emitter;
}
```

### 4.3 第三步：Gateway 路由

```yaml
- id: ai
  uri: lb://jzo2o-ai
  predicates:
    - Path=/ai/**
  filters:
    - Token
```

### 4.4 第四步：前端悬浮聊天窗

在 `project-xzb-PC-vue3-java` 的 `src/layouts/index.vue` 引入：

```vue
<ChatFloatingButton :visible="showChat" @toggle="showChat = !showChat" />
<ChatWindow :visible="showChat" @close="showChat = false" />
```

4 个新组件：`ChatFloatingButton` / `ChatWindow` / `ChatMessageList` / `ChatInput`，原生 `fetch` + `ReadableStream` 消费 SSE。

## 五、踩坑记录

### 5.1 页面白屏 — defineProps 返回值未接收

**现象**：前端白屏，无任何渲染。

**根因**：`ChatMessageList.vue` 中 `defineProps<{...}>()` 没有赋值给变量，`<script>` 里直接访问 `messages` 和 `streamingContent` 为 `undefined`，运行时崩溃。

**修复**：
```typescript
// 错误
defineProps<{ messages: ChatMsg[], streamingContent: string }>()
watch(() => [messages.length, streamingContent.value], ...)

// 正确
const props = defineProps<{...}>()
watch(() => [props.messages.length, props.streamingContent], ...)
```

> Vue 3 `<script setup>` 中，`defineProps` 的返回值只在 `<template>` 中自动可用，`<script>` 中必须通过变量访问。

### 5.2 前端 404 — 缺少 `/api` 代理前缀

**现象**：发送消息后显示 `[错误] HTTP 404`。

**根因**：项目 axios 请求工具统一加 `/api` 前缀，Vite 开发服务器将 `/api/*` 代理到 Gateway。但聊天接口用的原生 `fetch`，没有加这个前缀，请求打到了 Vite 自身而非 Gateway。

**修复**：
```typescript
// 本地 Vite 代理需 /api 前缀, 直连服务器则不加
const prefix = baseHost ? '' : '/api'
const url = `${baseHost}${prefix}/ai/consumer/chat/completions`
```

### 5.3 Python 404 — 路由重复拼接

**现象**：`POST /chat/completions HTTP/1.1 404 Not Found`

**根因**：`include_router(router, prefix="/chat")` 和 `@router.post("/chat/completions")` 同时写了 `/chat`，实际路径变成 `/chat/chat/completions`。

**修复**：路由改为 `@router.post("/completions")`，完整路径 = prefix `/chat` + path `/completions` = `/chat/completions`。

### 5.4 前端收不到 AI 回复 — PackResultFilter 劫持 SSE 响应

**现象**：Java 日志显示调用成功、Python 200、DeepSeek 200，但前端收不到任何消息，Network 面板显示空 `data:` 行。

**根因**：项目的 `PackResultFilter` 会用 `ResponseWrapper` 劫持所有 HTTP 响应、包装成项目统一 JSON 格式 `{"code":200,...}`。`SseEmitter` 是异步写入，其输出被 `ResponseWrapper` 的缓冲区捕获后丢弃，前端收到的是一行行空的 `data:`。

**修复**：在 `PackResultFilter.doFilter()` 的跳过条件中增加 SSE 端点：

```java
if (requestURI.contains(".") ||
        requestURI.contains("/swagger") ||
        requestURI.contains("/api-docs") ||
        requestURI.contains("/inner") ||
        requestURI.contains("/chat/completions")) {  // 新增
    filterChain.doFilter(servletRequest, servletResponse);
    return;
}
```

> **教训**：在 Servlet Filter 使用 `ResponseWrapper` 的项目中，SSE / WebSocket 等长连接响应必须显式排除，否则异步写入会失效。

### 5.5 前端仍不显示 — WebClient Flux 碎片化与 SSE 解析竞态

**现象**：跳过 Filter 后，前端收到 `data:` 但后面始终没有内容。

**根因**：最初 Python 端自己包 SSE 格式 (`data: {token}\n\n`)，Java 端用 `WebClient.bodyToFlux(String.class)` 接收后解析 `data:` 前缀。但 `bodyToFlux` 按网络缓冲区切分数据，一条完整的 `data: 你好\n\n` 可能被切成 `"data: "` 和 `"你好\n\n"` 两个元素，Java 的 `extractContent` 无法处理碎片化的 SSE。

**修复**：让 Python 抛弃 SSE 格式，改发纯文本 token（换行分隔 `\n`）。Java 收到后按 `\n` 拆分，再用 `SseEmitter.event().data()` 包装成 SSE 发给前端。两个方向各管各的格式，谁也不再解析谁的。

```
Python → Java:  token1\ntoken2\n...  (text/plain, 简单换行)
Java → 前端:   data: token1\n\ndata: token2\n\n  (text/event-stream)
```

## 六、启动顺序

```
1. Nacos / MySQL / Redis    (中间件)
2. Gateway                  (网关 :11500)
3. jzo2o-customer           (登录鉴权, 签发 JWT)
4. jzo2o-ai-engine          (Python :8000)
5. jzo2o-ai                 (Java :11511)
6. npm run dev:linux        (前端 :6001)
```

## 七、文件清单

| 模块 | 新增文件数 | 修改文件数 |
|------|-----------|-----------|
| jzo2o-ai-engine | 13 | 0 |
| jzo2o-ai | 16 | 0 |
| jzo2o-gateway | 0 | 1 (`bootstrap.yml`) |
| jzo2o-framework/jzo2o-mvc | 0 | 1 (`PackResultFilter.java`) |
| project-xzb-PC-vue3-java | 5 | 1 (`layouts/index.vue`) |
| .gitignore | 1 | 0 |

**总侵入度**：现有 Java 业务模块零改动，框架层 1 行，Gateway 1 条路由，前端布局文件 4 行。
