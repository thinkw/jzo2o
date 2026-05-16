/** AI 聊天状态管理 — 会话管理 + 消息状态共享 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listSessions, getSessionMessages } from '@/api/chat'
import type { SessionInfo } from '@/api/chat'
import type { ChatMessagesData } from '@tdesign-vue-next/chat'

export interface ChatMsg {
  role: 'user' | 'assistant'
  content: string
}

export const useChatStore = defineStore('chat', () => {
  /** 渲染模式: floating=悬浮窗, sidebar=侧边栏页 */
  const mode = ref<'floating' | 'sidebar'>('floating')

  /** 会话 ID */
  const sessionId = ref(generateSessionId())

  /** 历史会话列表 */
  const sessions = ref<SessionInfo[]>([])

  /** 共享消息列表：悬浮窗和侧边栏共享同一份消息 */
  const sharedMessages = ref<ChatMessagesData[]>([])

  function generateSessionId(): string {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0
      const v = c === 'x' ? r : (r & 0x3) | 0x8
      return v.toString(16)
    })
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
    sessionId.value = generateSessionId()
    sharedMessages.value = []  // 同时清空共享消息
  }

  /** 加载历史会话列表 */
  async function loadSessions() {
    sessions.value = await listSessions()
  }

  /** 切换到指定会话, 返回转换后的消息数据 */
  async function switchSession(sid: string): Promise<ChatMessagesData[]> {
    sessionId.value = sid
    const msgs = await getSessionMessages(sid)
    const chatMsgs = toChatMessagesData(msgs)
    sharedMessages.value = chatMsgs  // 同步到共享消息
    return chatMsgs
  }

  /** 更新共享消息列表 */
  function updateSharedMessages(msgs: ChatMessagesData[]) {
    sharedMessages.value = msgs
  }

  /** 将后端消息格式转为 TDesign Chatbot 格式 */
  function toChatMessagesData(msgs: ChatMsg[]): ChatMessagesData[] {
    return msgs.map((m, i) => {
      const id = `msg-${Date.now()}-${i}`
      if (m.role === 'user') {
        return {
          id,
          role: 'user' as const,
          content: [{ type: 'text' as const, data: m.content }],
        }
      } else {
        return {
          id,
          role: 'assistant' as const,
          content: [{ type: 'markdown' as const, data: m.content }],
        }
      }
    })
  }

  /** 提取消息中的纯文本内容 */
  function getMessageText(msg: ChatMessagesData): string {
    if (!msg.content) return ''
    return msg.content
      .map(c => {
        if (c.type === 'text' || c.type === 'markdown') {
          return typeof c.data === 'string' ? c.data : ''
        }
        return ''
      })
      .join('')
  }

  return {
    mode,
    sessionId,
    sessions,
    sharedMessages,
    enterSidebar,
    enterFloating,
    resetSession,
    loadSessions,
    switchSession,
    updateSharedMessages,
    toChatMessagesData,
    getMessageText,
  }
})
