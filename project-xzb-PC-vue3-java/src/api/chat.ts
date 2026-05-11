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
 * Java 端发送单行 SSE (每行对应 LLM 输出的一行内容)
 * 前端在每个 chunk 后追加 \n 还原原始文档结构
 */
export async function sendChatMessage(
  messages: ChatMessage[],
  sessionId: string,
  callbacks: SSECallback,
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

  const reader = response.body?.getReader()
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

        // 去除 "data:" 前缀 (5 个字符), 保留内容原样 (不 trim, 不做任何转义处理)
        const content = trimmed.substring(5)

        // 信号检测
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
  } catch (e) {
    callbacks.onError(`Stream error: ${e}`)
    return
  }

  // 流自然结束
  callbacks.onDone()
}
