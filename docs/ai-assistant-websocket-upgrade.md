> 将 AI 引擎通信从 HTTP 单向流升级为 WebSocket 双向通道，为 LangChain/LangGraph Agent 工具调用铺路。

## 一、为什么要换掉 HTTP

AI 助手当前的通信架构是：

```
前端 ──SSE──→ Java ──HTTP POST──→ Python ──text/plain token流──→ Java ──SSE──→ 前端
```

这套方案在纯聊天场景下运行良好。但后续 Python 侧要升级为 LangChain/LangGraph Agent，Agent 的执行模式是**多轮思考循环**：

```
思考 → 决定调工具 → 等工具结果 → 再思考 → 再调工具 → ... → 最终回复
```

工具调用需要查数据库、调业务 API，这些操作按架构原则**只能由 Java 执行**（Python 严禁直连生产 DB）。HTTP 单向流无法支持这种"Python 中途请 Java 帮忙执行，拿到结果继续思考"的模式。

**决策**：Java ↔ Python 升级为 WebSocket 双向通道，Java ↔ 前端 SSE 维持不变。

## 二、新架构

```
┌─────────────────────────────────────────────────────────┐
│  前端 ←──SSE (不变)──→ Java ←──WebSocket JSON──→ Python  │
│                         ↓                           ↓   │
│                      鉴权/持久化                  LLM 推理│
│                      工具执行                     Agent  │
└─────────────────────────────────────────────────────────┘
```

- **连接模型**：一个 WebSocket 连接对应一个 chat session，用户打开聊天窗即建连，关闭即断开
- **消息格式**：JSON 文本帧，`type` 字段区分消息类型
- **可降级**：保留原有 HTTP 端点，配置 `jzo2o.ai.engine.mode=http` 即可回退

### 消息协议

**Java → Python**：

```json
{"type": "user_message", "messages": [{"role": "user", "content": "..."}]}
{"type": "cancel"}
```

**Python → Java**：

```json
{"type": "token", "content": "你好"}
{"type": "error", "message": "LLM 调用超时"}
{"type": "agent_finish"}
```

`token` / `agent_finish` / `error` 三个类型已实现，后续 LangGraph 升级时扩展 `tool_call` / `tool_result` 等。

## 三、Python 侧实现

### 新增 WebSocket 端点

在 `app/api/ws_chat.py` 新增 `/ws/chat/{session_id}` 端点，复用现有 `stream_chat()` 异步生成器。

```python
@router.websocket("/ws/chat/{session_id}")
async def ws_chat(websocket: WebSocket, session_id: str):
    await websocket.accept()

    cancel_event = asyncio.Event()
    streaming_task: Optional[asyncio.Task] = None

    # 循环接收消息, user_message 启动流, cancel 中断流
    while True:
        msg = json.loads(await websocket.receive_text())
        if msg["type"] == "user_message":
            streaming_task = asyncio.create_task(stream_and_send())
        elif msg["type"] == "cancel":
            cancel_event.set()
            streaming_task.cancel()
```

关键设计：
- `asyncio.Event` 作为取消信号，LLM 每产出一个 token 检查一次，避免 API 费用浪费
- `WebSocketDisconnect` 异常捕获确保断连时自动取消任务
- `asyncio.Task` 包装流式协程，`cancel()` 能真正中断执行

### main.py 注册路由

```python
from app.api.ws_chat import router as ws_chat_router
app.include_router(ws_chat_router, tags=["WebSocket"])
```

保留原有 HTTP 路由不变，两个端点共存。

## 四、Java 侧实现

### WebSocketClientConfig — Reactor Netty Bean

Spring Boot 2.7 的 `spring-boot-starter-webflux` 已包含完整的 Reactor Netty WebSocket 客户端，无需新增 Maven 依赖：

```java
@Configuration
public class WebSocketClientConfig {
    @Bean
    public ReactorNettyWebSocketClient reactorNettyWebSocketClient() {
        return new ReactorNettyWebSocketClient();
    }
}
```

### AiEngineWebSocketClient — 连接管理与消息路由

这是本次升级最核心的 Java 文件，职责：

1. **Per-session 连接管理**：`ConcurrentHashMap<String, Disposable>` 维护 sessionId → 活跃连接的映射
2. **新旧连接切换**：同一 session 发新消息时自动关闭旧连接，建立新连接
3. **JSON 帧路由**：`token` → `SseEmitter.send()`，`agent_finish` → 触发持久化 → `emitter.complete()`，`error` → `emitter.completeWithError()`

```java
public void connectAndStream(String sessionId,
                             List<Map<String, String>> messages,
                             SseEmitter emitter,
                             Consumer<String> tokenAccumulator,
                             Runnable onCompleteCallback) {
    // 1. 取消该 session 的旧连接
    cancelExistingSession(sessionId);

    // 2. 连接 Python WebSocket, 发送 user_message
    Disposable disposable = wsClient.execute(URI.create(wsUri), session -> {
        Mono<Void> send = session.send(Mono.just(session.textMessage(userMessageJson)));
        Mono<Void> receive = session.receive()
                .doOnNext(wsMessage -> handleWsMessage(
                        wsMessage.getPayloadAsText(), emitter, sessionId,
                        tokenAccumulator, onCompleteCallback))
                .then();
        return send.then(receive);
    }).subscribe(...);

    activeSubscriptions.put(sessionId, disposable);
}
```

关键设计决策：
- **线程安全**：`StringBuffer` 而非 `StringBuilder`，因为 WebSocket token 回调在 Reactor Netty 线程，持久化回调在 Servlet 线程
- **回调模式**：`tokenAccumulator` 和 `onCompleteCallback` 由 `ChatServiceImpl` 传入，持久化逻辑不侵入 WS 客户端

### ChatServiceImpl 改造

SSE 创建逻辑完全不变，只在步骤 7（流式代理）按 `mode` 分支：

```java
if ("ws".equals(aiEngineProperties.getMode())) {
    StringBuffer responseBuffer = new StringBuffer();
    aiEngineWebSocketClient.connectAndStream(sessionId, messages, emitter,
            token -> responseBuffer.append(token).append("\n"),
            () -> {
                String fullResponse = responseBuffer.toString();
                if (StrUtil.isNotBlank(fullResponse)) {
                    saveRecord(..., AiConstants.ROLE_ASSISTANT, fullResponse);
                }
            });
} else {
    // HTTP 路径 — 原有逻辑不变
}
```

### AiEngineProperties — 配置扩展

```yaml
jzo2o:
  ai:
    engine:
      ws-url: ws://localhost:8000     # WebSocket 地址
      mode: ws                        # ws | http (默认 ws)
      base-url: http://localhost:8000 # HTTP 地址 (降级时使用)
```

## 五、踩坑记录

### 5.1 逐 token 换行 — WebSocket 比 HTTP 多一层"粒度"

**现象**：WebSocket 升级后，前端显示每个字独占一行，形如：

```
哈<br>喽<br>哈<br>喽<br>！<br>😊
```

**根因**：三层协议的数据粒度不同：

```
HTTP 路径:  LLM token → WebClient 行解码器按 \n 合并 → Java SSE 事件 (一行) → 前端补 \n
WS 路径:    LLM token → Python WS 帧 (一个字) → Java SSE 事件 (一个字) → 前端补 \n
```

HTTP 路径中，WebClient `bodyToFlux(String.class)` 使用 Reactor Netty 行解码器，即使 Python 不加任何分隔符，`\n` 也会触发解码器切帧。LLM 输出的自然换行被解码器用作"行边界"，每个 Flux 元素包含一整行的 token。

WS 路径没有行解码器。`stream_chat()` 每产出一个 token（可能是单字、词或长片段），就作为独立 JSON 帧发送。前端给每个 SSE 事件末尾补 `\n`，于是每个 token 变成了一行。

**修复**：在 Python `ws_chat.py` 的 `stream_and_send()` 中加缓冲区，按 `\n` 合并 token 后再发送，行为等价于 HTTP 路径的行解码器：

```python
buffer = ""
async for token in stream_chat(messages):
    if "\n" in token:
        parts = token.split("\n")
        for i, part in enumerate(parts):
            if i > 0:
                await websocket.send_text(json.dumps({"type": "token", "content": buffer}))
                buffer = part
            else:
                buffer += part
    else:
        buffer += token
# 发送最后一行
if buffer:
    await websocket.send_text(json.dumps({"type": "token", "content": buffer}))
```

> **教训**：从点对点协议升级时，要注意每一层是否存在隐式的"打包"行为。行解码器就是一个典型的隐式协议层——它在你不经意间决定数据如何分帧。

### 5.2 LaTeX `\[...\]` 不渲染

**现象**：黎曼ζ函数方程等复杂公式显示为原始文本，不渲染为数学公式。

**根因**：渲染引擎只支持 `$...$`（行内）和 `$$...$$`（块级）两种 LaTeX 分隔符。但 LLM（尤其是数学相关回复）经常使用标准 LaTeX 语法的 `\(...\)`（行内）和 `\[...\]`（块级）。

**修复**：在 `markdown.ts` 的 `preprocessLatex` 中新增两步处理：

```
处理顺序: $$ → \[ → $ → \(
          ↑块级优先↑      ↑行内随后↑
```

添加 `\[...\]`（显示公式）和 `\(...\)`（行内公式）的正则匹配，均使用 `[\s\S]*?` 允许跨行内容：

```typescript
// \[...\] 显示公式
processed = processed.replace(/\\\[([\s\S]*?)\\\]/g, (_match, formula: string) => {
  const html = renderKatex(formula, true) // displayMode: true
  ...
})

// \(...\) 行内公式
processed = processed.replace(/\\\(([\s\S]*?)\\\)/g, (_match, formula: string) => {
  const html = renderKatex(formula, false) // displayMode: false
  ...
})
```

同时更新 `clipIncompleteLatex`（流式裁剪函数），新增 `\[`/`\]` 和 `\(`/`\)` 的深度平衡检测，防止流式输出中未闭合的 `\[` 导致渲染异常。

> **教训**：LLM 的输出格式不完全可控。渲染引擎应尽可能覆盖多种等价语法（`$$`、`\[`、`$`、`\(`），而不应假设 LLM 一定使用某一种。

### 5.3 `renderKatex` 统一封装

原来的 `preprocessLatex` 有 4 处几乎相同的 `katex.renderToString` + try-catch 代码块。抽取为统一的 `renderKatex` 函数：

```typescript
function renderKatex(formula: string, displayMode: boolean): string | null {
  try {
    return katex.renderToString(formula.trim(), {
      displayMode,
      throwOnError: false,
      strict: false,  // 对 \! \, 等间距命令更宽容
    })
  } catch {
    return null
  }
}
```

`strict: false` 让 KaTeX 对 LaTeX 间距命令（`\!`、`\,`）等非标准用法更宽容，减少复杂公式的渲染失败。

## 六、前端富文本 — 从自研到 markstream-vue

### 为什么换掉自研方案

WebSocket 通道上线后，前端的 markdown 渲染链路是：

```
LLM 输出 → Python WS → Java SSE → 前端累积文本 → markdown-it 渲染 → KaTeX/Mermaid 后处理
```

`src/utils/markdown.ts`（~213 行）承担了 LaTeX 预处理、流式裁剪、占位符回填等逻辑，在此基础上又叠了 4 种 LaTeX 分隔符兼容、`clipIncompleteLatex` 流式裁剪。随着后续 LangGraph Agent 可能新增工具调用事件、`<thinking>` 标签等结构化输出，自研管线会越来越重。

**决策**：引入社区流式 Markdown 组件库，删除自研的 markdown.ts + ChatMarkdown.vue 渲染逻辑。

### 方案选型

| 方案 | 框架 | 版本 | Vue 要求 | 结论 |
|------|------|------|----------|------|
| Vercel Streamdown | React | 1.x | - | ❌ 项目是 Vue 3，跨框架嵌入不划算 |
| @wooshiiltd/streamdown-vue | Vue 3 | 0.1.1 | ^3.4.0 | ❌ 项目 3.2.31 不兼容，需 Tailwind v4 |
| vue-stream-markdown | Vue 3 | 0.7.2 | >=3.0.0 | 备选 |
| **markstream-vue** | Vue 3 | 0.0.14-beta.8 | **>=3.0.0** | ✅ 自包含 CSS，不依赖 Tailwind/shadcn |

选定 [markstream-vue](https://github.com/Simon-He95/markstream-vue)（~2.3k stars），核心优势：
- Vue `>=3.0.0`，无需升级项目基建
- 自包含 CSS（`.markstream-vue` scoped），与项目 Less + TDesign 体系无冲突
- KaTeX / Mermaid 通过 Web Worker 渲染，不阻塞主线程
- 内置流式支持（`final` prop 区分 streaming/static 模式）

### 变更

| 操作 | 文件 | 说明 |
|------|------|------|
| ❌ 删除 | `src/utils/markdown.ts` | 213 行，所有 LaTeX 预处理/裁剪/占位符逻辑移除 |
| ❌ 删除 | `markdown-it`、`@types/markdown-it` | 不再使用 |
| ➕ 新增 | `markstream-vue`、`shiki`、`stream-markdown` | 渲染组件 + 语法高亮引擎 |
| ✏️ 重写 | `src/components/chat/ChatMarkdown.vue` | 240 行 → 60 行，核心只需 `<MarkdownRender>` |
| 🔧 修改 | `vite.config.ts` | 可选依赖空桩 + alias |
| — | `chat.ts`、`ChatMessageList.vue`、`ChatWindow.vue` | 不动 |

### 坑点

1. **可选依赖构建报错**：markstream-vue 对 `stream-monaco`（Monaco 编辑器）、`@antv/infographic`、`@terrastruct/d2` 使用动态 `import()`，Vite 构建时无法解析。解决：创建空桩模块 + `resolve.alias` 映射。

2. **气泡空白行**：markstream-vue CSS 变量 `--ms-flow-paragraph-y: 1.5em` 给段落上下各加 1.5em margin。解决：`.chat-markdown .markstream-vue` 覆写 flow 间距变量为 0。

3. **代码块语法高亮未启用**：markstream-vue 默认用 Monaco 编辑器渲染代码块，Monaco 未安装时回退到纯 `<pre><code>`。当前接受降级，代码块以基础 CSS 样式渲染，语法高亮后续单独立项。

## 七、文件变更总览

| 阶段 | 模块 | 新增 | 修改/删除 |
|------|------|------|-----------|
| WebSocket 通道 | jzo2o-ai-engine | `app/schemas/ws_message.py`、`app/api/ws_chat.py` | `main.py` |
| WebSocket 通道 | jzo2o-ai | `AiEngineWebSocketClient.java`、`WebSocketClientConfig.java` | `ChatServiceImpl.java`、`AiEngineProperties.java` |
| 前端渲染 | project-xzb-PC-vue3-java | `markstream-vue`、`shiki`、`stream-markdown`、`stubs/` | ❌ `markdown.ts`、`markdown-it`；✏️ `ChatMarkdown.vue`、`vite.config.ts` |

**不改的部分**：前端 SSE 消费（chat.ts）、ChatController、ChatService 接口、Python HTTP 端点（chat.py）、Java HTTP 客户端（AiEngineClient）——全部保留，可通过配置一键降级。

## 八、升级路径

当前已完成：
1. ✅ HTTP → WebSocket 通道切换（Java ↔ Python 双向通信）
2. ✅ 前端自研渲染 → markstream-vue（停造轮子）

后续 LangChain/LangGraph 升级时：

1. **协议扩展**：在 JSON 帧中新增 `tool_call` / `tool_result` 类型
2. **Python 侧**：`stream_chat()` → LangChain `astream_events()`
3. **Java 侧**：新增工具注册表 + 工具执行逻辑，WebSocket 接收到 `tool_call` → 查业务数据 → 回传 `tool_result`
4. **前端侧**：可选新增工具调用中间态 UI（"正在查询..."进度提示）

WebSocket 通道 + 流式渲染组件已就绪，后续升级只需扩展消息类型，不需要再动通信和渲染架构。
