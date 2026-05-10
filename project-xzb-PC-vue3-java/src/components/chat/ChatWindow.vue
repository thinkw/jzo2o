<!-- AI聊天弹窗 -->
<template>
  <transition name="chat-slide">
    <div v-if="visible" class="chat-window">
      <div class="chat-header">
        <span>云岚到家 AI 助手</span>
        <button class="chat-close-btn" @click="close">✕</button>
      </div>
      <ChatMessageList
        :messages="messages"
        :streamingContent="streamingContent"
        :loading="loading"
      />
      <ChatInput
        :loading="loading"
        @send="handleSend"
      />
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { sendChatMessage } from '@/api/chat'
import ChatMessageList from './ChatMessageList.vue'
import ChatInput from './ChatInput.vue'
import { v4 as uuidv4 } from 'uuid' // 可选: 使用 simple UUID

interface ChatMsg {
  role: 'user' | 'assistant'
  content: string
}

defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const messages = ref<ChatMsg[]>([])
const streamingContent = ref('')
const loading = ref(false)
const sessionId = ref(generateSessionId())

function generateSessionId(): string {
  // 生成简易 UUID (不依赖外部库)
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

async function handleSend(content: string) {
  // 添加用户消息
  messages.value.push({ role: 'user', content })

  // 准备发送给AI的消息列表 (最近20条, 防止token过多)
  const recentMessages = messages.value.slice(-20)

  loading.value = true
  streamingContent.value = ''

  await sendChatMessage(recentMessages, sessionId.value, {
    onChunk(chunk: string) {
      streamingContent.value += chunk
    },
    onDone() {
      // 将累积的流式内容转为正式消息
      if (streamingContent.value) {
        messages.value.push({ role: 'assistant', content: streamingContent.value })
      }
      streamingContent.value = ''
      loading.value = false
    },
    onError(error: string) {
      messages.value.push({ role: 'assistant', content: `[错误] ${error}` })
      streamingContent.value = ''
      loading.value = false
    },
  })
}

function close() {
  emit('close')
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

// 入场/出场动画
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
