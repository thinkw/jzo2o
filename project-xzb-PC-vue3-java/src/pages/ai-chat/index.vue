<!-- AI 助手侧边栏全屏页 -->
<template>
  <div class="ai-chat-page">
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
      />
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

// 直接通过侧边栏菜单进入时, 设置模式并隐藏悬浮窗
onMounted(() => {
  chatStore.enterSidebar()
  nextTick(() => scrollToBottom())
})

/** 滚动到列表底部 */
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

// 离开此页时恢复悬浮窗
onBeforeRouteLeave((_to, _from, next) => {
  chatStore.enterFloating()
  next()
})
</script>

<style lang="less" scoped>
.ai-chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--td-comp-size-xxxl, 56px) - 48px);
  background: #f9f9f9;
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
