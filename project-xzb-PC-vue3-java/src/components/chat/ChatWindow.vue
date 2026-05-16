<!-- AI聊天弹窗(悬浮窗模式) -->
<template>
  <transition name="chat-slide">
    <div v-if="visible" class="chat-window">
      <div class="chat-header">
        <span>AI 助手</span>
        <div class="chat-header-actions">
          <button class="chat-expand-btn" title="展开到大窗口" @click="expand">⛶</button>
          <button class="chat-close-btn" @click="close">✕</button>
        </div>
      </div>
      <div class="chat-body">
        <Chatbot
          ref="chatbotRef"
          :default-messages="defaultMessages"
          :chat-service-config="serviceConfig"
          :list-props="{ autoScroll: true }"
          :sender-props="{ placeholder: '输入你的问题...' }"
          :message-props="{ assistant: { chatContentProps: { markdown: { options: markdownOptions } } } }"
        />
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { Chatbot } from '@tdesign-vue-next/chat'
import type { ChatMessagesData, ChatServiceConfig, ChatRequestParams, SSEChunkData } from '@tdesign-vue-next/chat'
import { useChatStore } from '@/store/modules/chat'
import { cancelChat } from '@/api/chat'
import proxy from '@/config/proxy'
import { TOKEN_NAME } from '@/config/global'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

/** 同步间隔计时器 ID */
let syncIntervalId: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  // 启动快速同步：每 200ms 同步一次
  syncIntervalId = setInterval(syncMessagesToStore, 200)
})

onBeforeUnmount(() => {
  if (syncIntervalId) {
    clearInterval(syncIntervalId)
  }
})

const router = useRouter()
const chatStore = useChatStore()
const chatbotRef = ref<InstanceType<typeof Chatbot>>()

const env = import.meta.env.MODE || 'development'
const baseHost = env === 'mock' || !proxy.isRequestProxy ? '' : proxy[env].host

/** 初始/历史消息 */
const defaultMessages = ref<ChatMessagesData[]>([])

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

  /** 解析后端 SSE：每行纯文本 */
  onMessage(chunk: SSEChunkData) {
    const data = chunk.data
    if (!data || data === '[DONE]' || (typeof data === 'string' && data.startsWith('[ERROR]'))) {
      return null
    }
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
      // 数学公式配置
      mathBlock: {
        engine: 'katex',
        src: 'https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js',
        css: 'https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css',
      },
      inlineMath: {
        engine: 'katex',
      },
      // Mermaid 图表配置
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

/** 监听 visible 变化：打开时同步共享消息 */
watch(
  () => props.visible,
  (visible) => {
    if (visible && chatStore.sharedMessages.length > 0) {
      const currentStore = chatbotRef.value?.messagesStore as { messages?: ChatMessagesData[] } | undefined
      const currentMsgs = currentStore?.messages || []
      // 只有当 Chatbot 当前没有消息时才从 store 同步
      if (currentMsgs.length === 0) {
        defaultMessages.value = [...chatStore.sharedMessages]
        setTimeout(() => {
          chatbotRef.value?.setMessages?.(chatStore.sharedMessages, 'replace')
        }, 100)
      }
    }
  }
)

function expand() {
  // 展开前同步当前消息到 store，供侧边栏使用
  syncMessagesToStore()
  chatStore.enterSidebar()
  emit('close')
  router.push('/ai-chat')
}

function close() {
  // 关闭前同步消息到 store
  syncMessagesToStore()
  emit('close')
}

/** 同步当前消息到 store */
function syncMessagesToStore() {
  const store = chatbotRef.value?.messagesStore as { messages?: ChatMessagesData[] } | undefined
  if (store?.messages) {
    chatStore.updateSharedMessages([...store.messages])
  }
}
</script>

<style lang="less" scoped>
.chat-window {
  position: fixed;
  right: 28px;
  bottom: 144px;
  width: 380px;
  height: 520px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  z-index: 998;
  overflow: hidden;
}
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--td-brand-color, #0052d9);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
}
.chat-header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.chat-expand-btn {
  background: none;
  border: none;
  color: #fff;
  font-size: 16px;
  cursor: pointer;
  padding: 0 4px;
  opacity: 0.8;
  line-height: 1;
  &:hover { opacity: 1; }
}
.chat-close-btn {
  background: none;
  border: none;
  color: #fff;
  font-size: 18px;
  cursor: pointer;
  padding: 0 4px;
  opacity: 0.8;
  &:hover { opacity: 1; }
}
.chat-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.chat-slide-enter-active,
.chat-slide-leave-active {
  transition: all 0.25s ease;
}
.chat-slide-enter-from,
.chat-slide-leave-to {
  opacity: 0;
  transform: translateY(20px) scale(0.96);
}
</style>
