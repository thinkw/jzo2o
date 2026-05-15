<!-- AI聊天弹窗(悬浮窗模式) -->
<template>
  <transition name="chat-slide">
    <div v-if="visible" class="chat-window">
      <div class="chat-header">
        <span>云岚到家 AI 助手</span>
        <div class="chat-header-actions">
          <!-- 展开到侧边栏 -->
          <button class="chat-expand-btn" title="展开到大窗口" @click="expand">⛶</button>
          <button class="chat-close-btn" @click="close">✕</button>
        </div>
      </div>
      <ChatMessageList
        :messages="chatStore.messages"
        :streamingContent="chatStore.streamingContent"
        :loading="chatStore.loading"
      />
      <ChatInput
        :loading="chatStore.loading"
        @send="handleSend"
      />
    </div>
  </transition>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { sendChatMessage } from '@/api/chat'
import { useChatStore } from '@/store/modules/chat'
import ChatMessageList from './ChatMessageList.vue'
import ChatInput from './ChatInput.vue'

defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const router = useRouter()
const chatStore = useChatStore()

async function handleSend(content: string) {
  chatStore.addUserMessage(content)
  chatStore.startRequest()

  const recentMessages = chatStore.messages.slice(-20)
  await sendChatMessage(recentMessages, chatStore.sessionId, {
    onChunk(chunk: string) {
      chatStore.appendStreamChunk(chunk)
    },
    onDone() {
      chatStore.finishStreaming()
    },
    onError(error: string) {
      chatStore.handleError(error)
    },
  })
}

function expand() {
  chatStore.enterSidebar()
  emit('close')
  router.push('/ai-chat')
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
