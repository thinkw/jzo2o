<!-- AI 助手侧边栏全屏页 — 左侧会话面板 + 右侧聊天区 -->
<template>
  <div class="ai-chat-page">
    <!-- 左侧会话面板 -->
    <aside class="chat-sidebar">
      <div class="sidebar-header">
        <span>对话历史</span>
        <button class="sidebar-new-btn" title="新建对话" @click="newChat">+ 新对话</button>
      </div>
      <div class="sidebar-session-list">
        <div
          v-if="chatStore.sessions.length === 0"
          class="sidebar-empty"
        >
          暂无历史对话
        </div>
        <div
          v-for="s in chatStore.sessions"
          :key="s.sessionId"
          class="sidebar-session-item"
          :class="{ active: s.sessionId === chatStore.sessionId }"
          @click="switchToSession(s.sessionId)"
        >
          <span class="s-preview">{{ s.preview }}</span>
          <span class="s-time">{{ s.lastTime }}</span>
        </div>
      </div>
    </aside>

    <!-- 右侧聊天区 -->
    <div class="chat-main">
      <div class="ai-chat-list">
        <Chatbot
          ref="chatbotRef"
          class="chatbot-full"
          :default-messages="defaultMessages"
          :chat-service-config="serviceConfig"
          :list-props="{ autoScroll: true }"
          :sender-props="{ placeholder: '输入你的问题...' }"
          :message-props="{ assistant: { chatContentProps: { markdown: { options: markdownOptions } } } }"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { Chatbot } from '@tdesign-vue-next/chat'
import type { ChatMessagesData, ChatServiceConfig, ChatRequestParams, SSEChunkData } from '@tdesign-vue-next/chat'
import { useChatStore } from '@/store/modules/chat'
import { cancelChat } from '@/api/chat'
import proxy from '@/config/proxy'
import { TOKEN_NAME } from '@/config/global'

const env = import.meta.env.MODE || 'development'
const baseHost = env === 'mock' || !proxy.isRequestProxy ? '' : proxy[env].host

const chatStore = useChatStore()
const chatbotRef = ref<InstanceType<typeof Chatbot>>()

/** 初始/历史消息 */
const defaultMessages = ref<ChatMessagesData[]>([])

/** 同步间隔计时器 ID */
let syncIntervalId: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  chatStore.enterSidebar()
  await chatStore.loadSessions()

  // 如果从悬浮窗切换过来，有共享消息则同步
  if (chatStore.sharedMessages.length > 0) {
    defaultMessages.value = [...chatStore.sharedMessages]
    // 等待 Chatbot 挂载后设置消息
    setTimeout(() => {
      chatbotRef.value?.setMessages?.(chatStore.sharedMessages, 'replace')
    }, 100)
  }

  // 启动快速同步：将 Chatbot 消息同步回 store（200ms 更及时）
  syncIntervalId = setInterval(syncMessagesToStore, 200)
})

onBeforeUnmount(() => {
  // 离开前同步消息到 store
  syncMessagesToStore()
  if (syncIntervalId) {
    clearInterval(syncIntervalId)
  }
})

/** 同步当前消息到 store */
function syncMessagesToStore() {
  const store = chatbotRef.value?.messagesStore as { messages?: ChatMessagesData[] } | undefined
  if (store?.messages && store.messages.length > 0) {
    chatStore.updateSharedMessages([...store.messages])
  }
}

/** 新建对话 */
async function newChat() {
  chatbotRef.value?.clearHistory?.()
  chatStore.resetSession()
  defaultMessages.value = []
}

/** 切换会话 */
async function switchToSession(sessionId: string) {
  const msgs = await chatStore.switchSession(sessionId)
  chatbotRef.value?.setMessages?.(msgs, 'replace')
}

/** 从消息中提取纯文本 */
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

/** 获取最近消息（用于发送给后端） */
function getRecentMessages(): { role: 'user' | 'assistant'; content: string }[] {
  // messagesStore 返回 { messageIds: string[], messages: ChatMessagesData[] }
  const store = chatbotRef.value?.messagesStore as { messages?: ChatMessagesData[] } | undefined
  const msgs = store?.messages || []
  return msgs.slice(-20).map(m => ({
    role: m.role as 'user' | 'assistant',
    content: getMessageText(m),
  }))
}

/** Chatbot 服务配置 */
const serviceConfig: ChatServiceConfig = {
  endpoint: `${baseHost}/api/ai/consumer/chat/completions`,
  stream: true,

  /** 自定义请求：注入 Authorization + messages 数组 + sessionId */
  onRequest(params: ChatRequestParams) {
    const token = localStorage.getItem(TOKEN_NAME)
    const recentMessages = getRecentMessages()
    // params.prompt 是用户当前输入，但还未写入 messagesStore，需手动追加
    if (params.prompt) {
      recentMessages.push({ role: 'user' as const, content: params.prompt })
    }
    return {
      ...params,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: token || '',
      },
      body: JSON.stringify({
        messages: recentMessages,
        sessionId: chatStore.sessionId,
        stream: true,
      }),
    }
  },

  /** 解析我们后端的自定义 SSE 格式：每行纯文本 */
  onMessage(chunk: SSEChunkData) {
    const data = chunk.data
    if (!data || data === '[DONE]' || (typeof data === 'string' && data.startsWith('[ERROR]'))) {
      return null
    }
    // 后端每行返回纯文本 → 作为 markdown 内容追加
    return { type: 'markdown' as const, data: (data as string) + '\n' }
  },

  /** 取消时通知 Java 端关闭 WebSocket */
  async onAbort() {
    cancelChat(chatStore.sessionId)
  },

  /** 错误处理 */
  onError(err: Error | Response) {
    console.error('Chat error:', err)
  },
}

/** Markdown 渲染配置：Cherry Markdown 引擎，支持 LaTeX 数学公式和 Mermaid 图表 */
const markdownOptions = {
  engine: {
    global: {
      flowSessionContext: true,
    },
    syntax: {
      // 数学公式配置 - 启用 trust 以支持更多 LaTeX 命令
      mathBlock: {
        engine: 'katex',
        katexConfig: {
          trust: true,
          strict: false,
          throwOnError: false,
        },
      },
      inlineMath: {
        engine: 'katex',
        katexConfig: {
          trust: true,
          strict: false,
          throwOnError: false,
        },
      },
      // Mermaid 图表配置 - 自动检测 ```mermaid 块
      codeBlock: {
        wrap: false,
        lineNumber: false,
        copyCode: true,
        editCode: false,
        mermaid: {
          svg2img: false,
          showSourceToolbar: true,
        },
      },
    },
  },
}

onBeforeRouteLeave(() => {
  chatStore.enterFloating()
})
</script>

<style lang="less" scoped>
.ai-chat-page {
  display: flex;
  height: calc(100vh - var(--td-comp-size-xxxl, 56px) - 48px);
  background: #f9f9f9;
}

// ── 左侧会话面板 ──
.chat-sidebar {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-right: 1px solid #eee;
}
.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  font-size: 14px;
  font-weight: 600;
  color: #333;
  border-bottom: 1px solid #eee;
}
.sidebar-new-btn {
  border: none;
  background: var(--td-brand-color, #0052d9);
  color: #fff;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 4px;
  cursor: pointer;
  &:hover { opacity: 0.85; }
}
.sidebar-session-list {
  flex: 1;
  overflow-y: auto;
}
.sidebar-empty {
  padding: 24px 16px;
  color: #999;
  font-size: 13px;
  text-align: center;
}
.sidebar-session-item {
  padding: 10px 16px;
  cursor: pointer;
  border-bottom: 1px solid #f5f5f5;
  &:hover { background: #f0f5ff; }
  &.active { background: #e8f0fe; }
}
.s-preview {
  display: block;
  font-size: 13px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.s-time {
  display: block;
  font-size: 11px;
  color: #999;
  margin-top: 3px;
}

// ── 右侧聊天区 ──
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
}
.ai-chat-list {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.chatbot-full {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
