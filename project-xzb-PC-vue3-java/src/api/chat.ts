/**
 * AI聊天接口 — SSE 流式消费
 */
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

/**
 * 发送聊天消息 (SSE 流式)
 * 使用 fetch + ReadableStream 消费服务端推送
 */
export async function sendChatMessage(
  messages: ChatMessage[],
  sessionId: string,
  callbacks: SSECallback
): Promise<void> {
  const token = localStorage.getItem(TOKEN_NAME)

  const requestBody: ChatRequest = {
    messages,
    sessionId,
    stream: true,
  }

  // 本地Vite代理需 /api 前缀, 直连服务器则不加
  const prefix = baseHost ? '' : '/api'
  const url = `${baseHost}${prefix}/ai/consumer/chat/completions`

  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: token || '',
    },
    body: JSON.stringify(requestBody),
  })

  if (!response.ok) {
    callbacks.onError(`HTTP ${response.status}`)
    return
  }

  // 逐行读取 SSE 流
  const reader = response.body?.getReader()
  if (!reader) {
    callbacks.onError('Response body is not readable')
    return
  }

  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })

    // 按行解析 SSE: data: <content>\n\n
    const lines = buffer.split('\n\n')
    buffer = lines.pop() || '' // 保留未完成的行

    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed || !trimmed.startsWith('data:')) continue

      const content = trimmed.substring(5).trim()
      if (content === '[DONE]') {
        callbacks.onDone()
        return
      }
      if (content.startsWith('[ERROR]')) {
        callbacks.onError(content.substring(7).trim())
        return
      }
      callbacks.onChunk(content)
    }
  }

  callbacks.onDone()
}
