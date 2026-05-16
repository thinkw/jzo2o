/**
 * AI聊天接口 — 会话管理 (SSE 流式逻辑已迁移到 Chatbot + ChatServiceConfig)
 */
import proxy from '@/config/proxy'
import { TOKEN_NAME } from '@/config/global'

const env = import.meta.env.MODE || 'development'
const baseHost = env === 'mock' || !proxy.isRequestProxy ? '' : proxy[env].host

export interface SessionInfo {
  sessionId: string
  preview: string
  lastTime: string
}

/** 获取用户的会话列表 */
export async function listSessions(): Promise<SessionInfo[]> {
  const token = localStorage.getItem(TOKEN_NAME)
  const prefix = baseHost ? '' : '/api'
  const response = await fetch(`${baseHost}${prefix}/ai/consumer/chat/sessions`, {
    headers: { Authorization: token || '' },
  })
  if (!response.ok) return []
  const json = await response.json()
  return json.data || []
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
}

/** 获取指定会话的消息历史 */
export async function getSessionMessages(sessionId: string): Promise<ChatMessage[]> {
  const token = localStorage.getItem(TOKEN_NAME)
  const prefix = baseHost ? '' : '/api'
  const response = await fetch(`${baseHost}${prefix}/ai/consumer/chat/sessions/${encodeURIComponent(sessionId)}`, {
    headers: { Authorization: token || '' },
  })
  if (!response.ok) return []
  const json = await response.json()
  return json.data || []
}

/** 向 Java 发送取消请求, 确保 WebSocket 被关闭 */
export function cancelChat(sessionId: string) {
  const token = localStorage.getItem(TOKEN_NAME)
  const prefix = baseHost ? '' : '/api'
  fetch(`${baseHost}${prefix}/ai/consumer/chat/cancel?sessionId=${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
    headers: { Authorization: token || '' },
  }).catch(() => {}) // 忽略取消请求本身的错误
}
