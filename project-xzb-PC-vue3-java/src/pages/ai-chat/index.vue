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
      <ChatMessageList
        ref="listRef"
        class="ai-chat-list"
        :messages="chatStore.messages"
        :streamingContent="chatStore.streamingContent"
        :loading="chatStore.loading"
      />
      <div class="ai-chat-footer">
        <ChatInput
          :loading="chatStore.loading"
          @send="handleSend"
          @stop="handleStop"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { sendChatMessage } from '@/api/chat'
import { useChatStore } from '@/store/modules/chat'
import ChatMessageList from '@/components/chat/ChatMessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'

const chatStore = useChatStore()
const listRef = ref<InstanceType<typeof ChatMessageList>>()
let currentSession: { promise: Promise<void>; cancel: () => void } | null = null

onMounted(() => {
  chatStore.enterSidebar()
  chatStore.loadSessions()
  nextTick(() => scrollToBottom())
})

function scrollToBottom() {
  const el = listRef.value?.$el
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

async function handleSend(content: string) {
  chatStore.addUserMessage(content)
  chatStore.startRequest()

  const recentMessages = chatStore.messages.slice(-20)
  currentSession = sendChatMessage(recentMessages, chatStore.sessionId, {
    onChunk(chunk: string) {
      chatStore.appendStreamChunk(chunk)
    },
    onDone() {
      chatStore.finishStreaming()
      currentSession = null
    },
    onError(error: string) {
      chatStore.handleError(error)
      currentSession = null
    },
  })

  await currentSession.promise
}

function handleStop() {
  currentSession?.cancel()
  chatStore.cancelStreaming()
  currentSession = null
}

function newChat() {
  handleStop()
  chatStore.resetSession()
}

async function switchToSession(sessionId: string) {
  handleStop()
  await chatStore.switchSession(sessionId)
  nextTick(() => scrollToBottom())
}

onBeforeRouteLeave((_to, _from, next) => {
  handleStop()
  chatStore.enterFloating()
  next()
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
}
.ai-chat-list {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}
.ai-chat-footer {
  flex-shrink: 0;
  padding: 12px 16px;
  background: #fff;
  border-top: 1px solid var(--td-component-border, #e8e8e8);
}
</style>
