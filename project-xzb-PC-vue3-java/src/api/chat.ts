/**
 * AI聊天接口 — SSE 流式消费
 */
import request from '@/utils/request'
import proxy from '@/config/proxy'
import { TOKEN_NAME } from '@/config/global'

const env = import.meta.env.MODE || 'development'
const baseHost = env === 'mock' || !proxy.isRequestProxy ? '' : proxy[env].host

interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
}

interface ChatRequest {
  messages: ChatMessage[]
  sessionId?: string
  stream?: boolean
}

interface SSECallback {
  onChunk: (content: string) => void
  onDone: () => void
  onError: (error: string) => void
}

interface ChatSession {
  /** 等待流式完成 */
  promise: Promise<void>
  /** 主动取消流式读取, 触发 Java 端 SSE 断开 → WebSocket 取消 */
  cancel: () => void
}

/**
 * 发送聊天消息 (SSE 流式)
 * Java 端发送单行 SSE (每行对应 LLM 输出的一行内容)
 * 前端在每个 chunk 后追加 \n 还原原始文档结构
 * 返回 ChatSession, 其中 cancel() 通过 reader.cancel() 真正中断流式读取
 */
export function sendChatMessage(
  messages: ChatMessage[],
  sessionId: string,
  callbacks: SSECallback,
): ChatSession {
  const token = localStorage.getItem(TOKEN_NAME)
  const ac = new AbortController()
  // 存储 reader 引用, 供 cancel() 直接中止
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null

  const promise = (async () => {
    const requestBody: ChatRequest = {
      messages,
      sessionId,
      stream: true,
    }

    // 本地Vite代理需 /api 前缀, 直连服务器则不加
    const prefix = baseHost ? '' : '/api'
    const url = `${baseHost}${prefix}/ai/consumer/chat/completions`

    let response: Response
    try {
      response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: token || '',
        },
        body: JSON.stringify(requestBody),
        signal: ac.signal,
      })
    } catch (e: any) {
      if (e?.name === 'AbortError') {
        return // fetch 未发出就被取消, 静默退出
      }
      callbacks.onError(`Network error: ${e}`)
      return
    }

    if (!response.ok) {
      callbacks.onError(`HTTP ${response.status}`)
      return
    }

    reader = response.body?.getReader() ?? null
    if (!reader) {
      callbacks.onError('Response body is not readable')
      return
    }

    const decoder = new TextDecoder()
    let buffer = ''

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // 按 \n\n 拆分 SSE 事件
        const lines = buffer.split('\n\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed || !trimmed.startsWith('data:')) continue

          // 去除 "data:" 前缀 (5 个字符)
          const content = trimmed.substring(5)

          if (content === '[DONE]') {
            callbacks.onDone()
            return
          }
          if (content.startsWith('[ERROR]')) {
            callbacks.onError(content.substring(7))
            return
          }

          // 每行追加 \n 还原 LLM 原始输出中的换行
          callbacks.onChunk(content + '\n')
        }
      }
    } catch (e: any) {
      // reader.cancel() 或网络断开会抛 TypeError
      if (e?.name === 'AbortError' || e?.name === 'TypeError') {
        return // 用户主动取消, 静默退出
      }
      callbacks.onError(`Stream error: ${e}`)
      return
    }

    // 流自然结束
    callbacks.onDone()
  })()

  return {
    promise,
    cancel() {
      // 两路并行中止, 确保 Java 端一定收到取消信号:
      // 1. reader.cancel() + abort() → 中断 fetch 流式读取
      ac.abort()
      reader?.cancel().catch(() => {})
      // 2. 显式 HTTP 请求 → Java 立即取消 WebSocket (保险)
      cancelChat(sessionId)
    },
  }
}

/** 获取用户的会话列表 */
export async function listSessions(): Promise<SessionInfo[]> {
  const token = localStorage.getItem(TOKEN_NAME)
  const prefix = baseHost ? '' : '/api'
  const response = await fetch(`${baseHost}${prefix}/ai/consumer/chat/sessions`, {
    headers: { Authorization: token || '' },
  })
  if (!response.ok) return []
  // PackResultFilter 包了一层 { code, data, msg }
  const json = await response.json()
  return json.data || []
}

export interface SessionInfo {
  sessionId: string
  preview: string
  lastTime: string
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
