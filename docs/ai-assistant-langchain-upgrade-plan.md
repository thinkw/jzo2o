# AI 助手升级 LangChain + LangGraph 计划

> 当前架构：Python 纯 token 流 → Java 包 SSE → 前端拼接。
> 升级目标：Python 端从 `stream_chat()` 替换为 LangChain/LangGraph Agent，支持工具调用、多轮推理、状态持久化。
> **核心决策**：Java ↔ Python 升级为 WebSocket，Java ↔ 前端维持 SSE 不变。

---

## 一、架构决策：Java ↔ Python 升级为 WebSocket

### 为什么必须换掉 HTTP

当前 HTTP 单向流是**请求-响应模式**，而 LangGraph Agent 的执行模式是**多轮对话**——Agent 思考过程中需要调用外部工具获取数据，然后基于结果继续推理：

```
HTTP 能做的:
  Java ──POST messages──→ Python
  Java ←──token流──────── Python        （Java 只能被动读，不能中途说话）

Agent 实际需要:
  Java ──POST messages──→ Python
  Java ←──token────────── Python
  Java ←──tool_call────── Python        "帮我查用户 123 的订单"
  Java ──tool_result────→ Python        "查到 3 个订单: ..."
  Java ←──token────────── Python        "根据查询结果..."
  Java ←──agent_finish─── Python
```

WebSocket 让 Java 成为 Python Agent 的**工具执行代理**——Python 要查数据不直连 DB，而是通过同一条连接让 Java 去查，Java 查完传回结果。**这恰好符合初始架构的职责边界（Python 严禁直连生产 DB、严禁执行业务操作）。**

### 改动范围

| 链路 | 协议 | 改动 |
|------|------|------|
| Python ↔ Java | **HTTP → WebSocket** | 双向通信，结构化消息 |
| Java ↔ 前端 | **SSE 不变** | 维持当前实现，前端无感知 |

### WebSocket 相比 HTTP 的改善一览

| 问题 | HTTP 方案 | WebSocket 方案 |
|------|-----------|----------------|
| 结构化多事件 | 塞进 HTTP 响应体，解析麻烦 | 天然支持，每个事件一条消息 |
| 中止/取消 | 需要额外 `DELETE` 端点 | Java 直接 close WebSocket，Python 侧立即感知 |
| 工具调用回传 | Python 需另调 HTTP 回 Java | 同一条连接双向通信 |
| 心跳保活 | 在 SSE 响应流中插注释 | WebSocket 原生 ping/pong |
| 连接超时 | Gateway/浏览器有 idle timeout | WebSocket 长连接，无此问题 |

---

## 二、WebSocket 消息协议设计

### 连接模型

**一个 WebSocket 连接 = 一个 chat session**。用户打开聊天窗 → Java 按需建立到 Python 的连接，关闭聊天窗 → 断开。好处：
- 连接数可控（活跃用户数 = 活跃连接数）
- LangGraph checkpoint state 和连接生命周期一致
- 不需要每次用户发言都重新建连

### Python 端点

```
WebSocket ws://jzo2o-ai-engine:8000/ws/chat/{session_id}
```

### 消息格式

全部用 JSON 文本帧，`type` 字段区分消息类型。

**Java → Python**（上行）：

```json
// 用户消息
{"type": "user_message", "messages": [{"role": "user", "content": "查下我的订单"}]}

// 工具执行结果（Python Agent 调工具 → Java 执行 → 回传）
{"type": "tool_result", "call_id": "abc123", "result": {"orders": [...]}}

// 取消当前 Agent 运行
{"type": "cancel"}
```

**Python → Java**（下行）：

```json
// LLM 逐 token 输出
{"type": "token", "content": "好的"}

// Agent 决定调用工具（需要 Java 侧执行）
{"type": "tool_call", "call_id": "abc123", "tool": "search_orders", "args": {"userId": 123}}

// 工具执行中继信息（可选，用于前端展示进度）
{"type": "tool_start", "call_id": "abc123", "tool": "search_orders"}
{"type": "tool_end", "call_id": "abc123", "success": true}

// Agent 完成最终回复
{"type": "agent_finish", "summary": "..."}

// 错误
{"type": "error", "message": "LLM 调用超时"}

// 心跳（Agent 运行中但长时间无输出时保活）
{"type": "heartbeat"}
```

---

## 三、各端改造要点

### Java 侧

**新增 `AiEngineWebSocketClient`**（替换现有 `AiEngineClient`）：
- Spring WebFlux `ReactorNettyWebSocketClient`（与现有 `WebClient` 同源）
- 按需建连：接收到用户消息时，如该 session 无活跃连接则建立
- 消息路由逻辑：
  - `token` / `heartbeat` → 直接透传为 SSE 发前端
  - `tool_call` → 解析工具名和参数，调用对应 Java 业务方法，结果通过 WebSocket 回传
  - `tool_start` / `tool_end` → 透传给前端做 UI 进度展示
  - `agent_finish` → 持久化完整回复，通知前端完成
  - `error` → 记录日志，通知前端

**修改 `ChatServiceImpl`**：
- 用户发送消息后，通过 WebSocket 发给 Python
- 仍然用 `SseEmitter` 给前端推送（逻辑基本不变）
- 增加 WebSocket 生命周期管理（建连、断连、重连策略）

**新增工具注册表**：
- 将 Java 侧可执行的操作注册为工具列表（如 `search_orders`、`get_user_info`）
- 首次建立连接时，Java 将可用工具列表发给 Python

**WebSocket 连接管理**：
- `ConcurrentHashMap<String, WebSocketSession>`：sessionId → 连接
- 用户关闭聊天窗 → close WebSocket → Python Agent 收到 `asyncio.CancelError`
- 连接异常断开 → 自动重连（退避策略）

### Python 侧

**替换 HTTP 端点为 WebSocket**：

```python
@router.websocket("/ws/chat/{session_id}")
async def chat_websocket(websocket: WebSocket, session_id: str):
    await websocket.accept()
    # 加载 LangGraph checkpoint state
    # 循环: 收消息 → 运行 Agent → 发事件
    # 连接断开 → 取消 Agent 执行
```

**从 `stream_chat()` 改为 LangChain Agent**：
- 使用 `astream_events()` 产生结构化事件
- 收到 `cancel` → `asyncio.CancelError` 中断执行
- Agent 工具定义为 stub（只声明参数，实际执行等 Java 回传）

**消息循环伪代码**：

```
while True:
    msg = await websocket.receive_json()
    if msg["type"] == "user_message":
        async for event in agent.astream_events(msg["messages"]):
            if event == "on_tool_start":
                await websocket.send_json({"type": "tool_call", ...})
                result = await wait_for_tool_result(websocket, call_id)  # 等 Java 回传
                ...
            elif event == "on_chat_model_stream":
                await websocket.send_json({"type": "token", "content": token})
        await websocket.send_json({"type": "agent_finish"})
    elif msg["type"] == "cancel":
        raise asyncio.CancelError()
```

### 前端侧

**维持不变**。SSE 消费逻辑无需改动——Java 仍然是 SSE 推送方，前端看到的仍然是每行 `data:` + 内容。唯一可选升级：解析 Java 转发的 `tool_start`/`tool_end` 事件展示"正在查询..."进度提示。

---

## 四、状态管理：Java 持久化 vs LangGraph Checkpointing

### 问题

两条持久化路径可能不一致：

- **Java 侧**：`ai_chat_record` 表持久化用户消息和 AI 回复（最终结果）
- **Python 侧**：LangGraph Checkpointing 持久化 Agent 内部状态（对话历史、工具调用栈、推理步骤）

如果两者不统一：
- Java 记录显示"AI 回复了 X"，但 Python checkpoint 里 Agent 状态和 X 对不上
- 用户刷新页面后，Java 从 DB 恢复消息列表发给 Python，但 Python 的 LangGraph state 里缺少之前的工具调用上下文

### 待办

- [ ] 确认 LangGraph Checkpointer 选型（推荐 Redis，与 Java 侧共用 Redis 实例）
- [ ] 职责划分定稿：
  - Java 侧：只记录**最终用户可见的对话消息**（现有 `ai_chat_record` 表）
  - Python 侧：管理 **Agent 内部状态**（LangGraph checkpoint，含工具调用上下文）
  - 两边通过 `sessionId` 对齐
- [ ] 用户刷新/重新进入时，Java 恢复历史消息发给 Python，Python 从 checkpoint 恢复 Agent 状态后继续对话

---

## 五、中止/取消机制

WebSocket 天然解决取消问题：

- 前端取消（关窗或点停止）→ Java 关闭 WebSocket 连接 → Python `websocket.receive()` 抛异常 → `asyncio.CancelError` 中断 Agent
- 无需额外的 HTTP `DELETE` 端点
- Python 端需在工具等待阶段也能响应取消（`asyncio.wait_for` 或 `create_task` + `cancel`）

---

## 六、连接超时与心跳

- **WebSocket 原生 ping/pong**：Java `WebSocketClient` 配置自动 ping 间隔（60s）
- Python `websocket.timeout` 设为 None（非 idle 超时），靠 ping/pong 保活
- Gateway 对 WebSocket 升级请求的处理：确认 Spring Cloud Gateway 不会对 WebSocket 连接施加 idle timeout（通常 WebSocket 不受 HTTP timeout 限制，上升级后连接不再走 HTTP 响应流路径）

---

## 七、无需更改的部分

| 模块 | 原因 |
|------|------|
| Java 鉴权（`UserContext` + Token 拦截器） | 鉴权逻辑完全独立于 AI 推理 |
| 敏感词初滤 | 依然在 Java 层做，Python 不接触 |
| `ai_chat_record` 持久化 | 只需按最终回复内容写入，不关心中间过程 |
| Gateway 路由（`/ai/** → jzo2o-ai`） | 路由不变，WebSocket 升级后仍在 `/ai` 下 |
| Python 不直连生产 DB | Agent 工具调用通过 WebSocket 让 Java 执行，强化了这条边界 |
| Python 独立部署 | FastAPI 服务独立运行，WebSocket 只是换了端点实现 |
| **前端 SSE 消费** | 不改一行代码，Java 仍是 SSE 推送方 |

---

## 八、建议升级顺序

```
1. 设计 WebSocket 消息协议 (JSON 格式、消息类型、错误码)
     ↓
2. Python 端: 新增 WebSocket 端点 + mock Agent（不做真正推理，只模拟事件流）
     ↓
3. Java 端: 实现 WebSocket 客户端，连 Python 验证双向通信
     ↓
4. Java 端: 消息路由 → SSE 透传 token，验证前后端没断
     ↓
5. Python 端: 接入真实 LangChain/LangGraph astream_events()
     ↓
6. Java 端: 工具注册 + 工具调用执行 + 结果回传
     ↓
7. Java 端: 连接管理（建连/断连/重连/ConcurrentHashMap）
     ↓
8. 心跳 keepalive + 取消机制
     ↓
9. LangGraph Checkpointing 集成 (Redis)
     ↓
10. 联调 + 端到端测试
```

---

## 九、风险点

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| WebSocket 消息协议设计不合理 | 三端都要返工 | 第 1 步定稿后再编码，每条消息类型标注示例 |
| Spring Cloud Gateway 对 WebSocket 支持 | Gateway 层可能阻断升级 | 第 2 步先验证 Gateway → jzo2o-ai 的 WebSocket 升级是否通畅 |
| LangGraph API 变动 | Python 端实现受阻 | 锁定 LangGraph 版本，关注 Changelog |
| 工具调用耗时过长（如查大表） | Agent 等待超时 | 工具调用前后发 UI 提示，Java 侧设工具执行超时兜底 |
| WebSocket 连接数过高 | 服务器资源压力 | 按需建连（用户发言时），闲置超时自动关闭 |
| Redis 不够用（Checkpoint + 业务缓存） | 内存压力 | 评估 Checkpoint 数据量，考虑独立 Redis 实例或 LRU 淘汰 |
| 重连时状态恢复失败 | 用户体验差 | LangGraph checkpoint 重放验证，异常时回退到纯对话模式 |
