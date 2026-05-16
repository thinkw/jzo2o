# AI 助手剩余升级计划

> 从 `ai-assistant-langchain-upgrade-plan.md` 中提取的未完成步骤。
> 原计划 10 步中已完成 6 步（WebSocket 协议、Python 端点、Java 客户端、SSE 透传、LangGraph Agent、工具调用），剩余 4 步。

---

## 一、连接管理完善 (步骤 7)

### 目标
将当前简单的 WebSocket 连接管理升级为生产级方案。

### 要点
- **会话级连接管理**: `ConcurrentHashMap<String, WebSocketSession>` — sessionId → 连接映射
- **按需建连**: 仅在用户发言且该 session 无活跃连接时建立 WebSocket
- **闲置断连**: 连接空闲超时后自动关闭（如 30 分钟无活动），释放资源
- **重连退避策略**: 连接异常断开时自动重连，采用指数退避（1s → 2s → 4s → ... 上限 30s）
- **并发安全**: `ConcurrentHashMap` 确保多线程下 put/remove 安全

### 关键文件
| 文件 | 模块 | 说明 |
|------|------|------|
| `AiEngineWebSocketClient.java` | jzo2o-ai | 新增 session 管理、重连逻辑 |
| `ChatServiceImpl.java` | jzo2o-ai | 调用建连/断连逻辑 |

---

## 二、心跳保活 + 取消机制 (步骤 8)

### 目标
确保长连接稳定，支持用户中途取消 AI 生成。

### 要点

**心跳保活**:
- Java `WebSocketClient` 配置自动 ping 间隔（60s）
- Python 端 `websocket.timeout` 设为 None（不因 idle 超时断开）
- Agent 长时间无输出时 Python 发送 `heartbeat` 消息，Java 透传给前端保持 SSE 连接活跃

**取消机制**:
- 前端取消（关窗或点停止按钮）→ Java 关闭 WebSocket 连接
- Python 端 `websocket.receive()` 抛异常 → `asyncio.CancelError` 中断 Agent 执行
- 工具等待阶段也需响应取消（`asyncio.wait_for` 或 `create_task` + `cancel`）

### 关键文件
| 文件 | 模块 | 说明 |
|------|------|------|
| `AiEngineWebSocketClient.java` | jzo2o-ai | ping/pong 配置 + cancel 处理 |
| `ws_chat.py` | jzo2o-ai-engine | heartbeat 发送 + CancelError 处理 |
| 前端聊天组件 | project-xzb-PC-vue3-java | 停止按钮触发取消 |

---

## 三、LangGraph Checkpointing 集成 (步骤 9)

### 目标
Agent 对话状态持久化，用户刷新页面后可恢复对话上下文。

### 要点
- **Checkpointer 选型**: Redis（推荐，与 Java 侧共用 Redis 实例）
- **职责划分**:
  - Java 侧 `ai_chat_record` 表：只记录最终用户可见的对话消息
  - Python 侧 LangGraph checkpoint：管理 Agent 内部状态（对话历史、工具调用栈、推理步骤）
  - 两边通过 `sessionId` 对齐
- **恢复流程**: 用户刷新/重新进入 → Java 从 DB 恢复历史消息 → 发给 Python → Python 从 checkpoint 恢复 Agent 状态 → 继续对话
- **数据量评估**: 评估 checkpoint 数据量，考虑独立 Redis 实例或 LRU 淘汰策略

### 关键文件
| 文件 | 模块 | 说明 |
|------|------|------|
| `graph.py` | jzo2o-ai-engine | 替换 InMemorySaver → RedisSaver |
| `ws_chat.py` | jzo2o-ai-engine | session 恢复逻辑 |
| `config.py` | jzo2o-ai-engine | Redis 连接配置 |
| `ChatServiceImpl.java` | jzo2o-ai | 历史消息恢复 + 发给 Python |

---

## 四、端到端联调测试 (步骤 10)

### 目标
全链路验证所有功能正常工作。

### 测试用例

| 编号 | 场景 | 预期结果 |
|------|------|----------|
| T1 | 用户发送普通聊天消息 | 正常流式回复，markdown 渲染正确 |
| T2 | Agent 调用本地工具（计算、时间） | Python 本地执行，结果融入回复 |
| T3 | Agent 调用远程工具（订单查询） | Java 执行，结果回传 Python，融入回复 |
| T4 | 评价 AI 总结 — 手动触发 | 正确读取增量评价，生成合并总结，写入 evaluation_summary |
| T5 | 评价 AI 总结 — 已有总结再次触发 | 仅读新评价，合并旧总结，更新记录 |
| T6 | 评价 AI 总结 — 定时任务 | XXL-JOB 自动执行，日志正常 |
| T7 | WebSocket 连接断开重连 | 自动重连，指数退避 |
| T8 | 用户中途取消生成 | WebSocket 关闭，Python Agent 停止，前端恢复输入 |
| T9 | 心跳保活 | 长时间无输出时连接不断开 |
| T10 | 页面刷新后恢复对话 | Checkpoint 恢复，Agent 继续之前上下文 |

---

## 风险点

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| WebSocket 连接数过高 | 服务器资源压力 | 按需建连 + 闲置超时自动关闭 |
| Redis 内存压力（Checkpoint + 业务缓存） | 内存不足 | 评估数据量，考虑独立 Redis 或 LRU |
| 重连时状态恢复失败 | 用户对话丢失 | Checkpoint 重放验证，异常时回退到纯对话模式 |
| Gateway 对 WebSocket 施加 idle timeout | 连接意外断开 | 验证 Gateway 配置，必要时调整 |

---

## 建议执行顺序

```
步骤 7 (连接管理) → 步骤 8 (心跳+取消) → 步骤 9 (Checkpointing) → 步骤 10 (联调测试)
```

连接管理是心跳和取消的基础，Checkpointing 依赖稳定的连接管理，联调放在最后。
