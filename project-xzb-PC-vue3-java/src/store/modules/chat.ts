/** AI 聊天状态管理 — 悬浮窗与侧边栏页共享 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listSessions, getSessionMessages } from '@/api/chat'
import type { SessionInfo } from '@/api/chat'

export interface ChatMsg {
  role: 'user' | 'assistant'
  content: string
}

export const useChatStore = defineStore('chat', () => {
  /** 渲染模式: floating=悬浮窗, sidebar=侧边栏页 */
  const mode = ref<'floating' | 'sidebar'>('floating')

  /** 对话历史 */
  const messages = ref<ChatMsg[]>([])

  /** 当前流式接收中的内容 */
  const streamingContent = ref('')

  /** 是否等待 AI 回复 */
  const loading = ref(false)

  /** 会话 ID */
  const sessionId = ref(generateSessionId())

  /** 历史会话列表 */
  const sessions = ref<SessionInfo[]>([])

  function generateSessionId(): string {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0
      const v = c === 'x' ? r : (r & 0x3) | 0x8
      return v.toString(16)
    })
  }

  /** 添加用户消息 */
  function addUserMessage(content: string) {
    messages.value.push({ role: 'user', content })
  }

  /** 流式结束: 将累积内容写入正式消息 */
  function finishStreaming() {
    if (streamingContent.value) {
      messages.value.push({ role: 'assistant', content: streamingContent.value })
    }
    streamingContent.value = ''
    loading.value = false
  }

  /** 取消流式: 丢弃已累积的部分内容 (不写入消息历史) */
  function cancelStreaming() {
    streamingContent.value = ''
    loading.value = false
  }

  /** 追加流式内容片段 */
  function appendStreamChunk(chunk: string) {
    streamingContent.value += chunk
  }

  /** 错误处理 */
  function handleError(error: string) {
    messages.value.push({ role: 'assistant', content: `[错误] ${error}` })
    streamingContent.value = ''
    loading.value = false
  }

  /** 开始新一轮请求 */
  function startRequest() {
    loading.value = true
    streamingContent.value = ''
  }

  /** 切换到侧边栏模式 */
  function enterSidebar() {
    mode.value = 'sidebar'
  }

  /** 切换回悬浮窗模式 */
  function enterFloating() {
    mode.value = 'floating'
  }

  /** 重置会话(新建会话) */
  function resetSession() {
    messages.value = []
    streamingContent.value = ''
    loading.value = false
    sessionId.value = generateSessionId()
  }

  /** 加载历史会话列表 */
  async function loadSessions() {
    sessions.value = await listSessions()
  }

  /** 切换到指定会话, 加载消息历史 */
  async function switchSession(sid: string) {
    messages.value = []
    streamingContent.value = ''
    loading.value = false
    sessionId.value = sid
    const msgs = await getSessionMessages(sid)
    messages.value = msgs.map(m => ({
      role: m.role as 'user' | 'assistant',
      content: m.content,
    }))
  }

  return {
    mode,
    messages,
    streamingContent,
    loading,
    sessionId,
    sessions,
    addUserMessage,
    finishStreaming,
    cancelStreaming,
    appendStreamChunk,
    handleError,
    startRequest,
    enterSidebar,
    enterFloating,
    resetSession,
    loadSessions,
    switchSession,
  }
})
