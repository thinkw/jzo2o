<!-- 聊天消息列表 -->
<template>
  <div class="chat-message-list" ref="listRef">
    <div
      v-for="(msg, idx) in messages"
      :key="idx"
      class="chat-message"
      :class="{ user: msg.role === 'user', assistant: msg.role === 'assistant' }"
    >
      <div class="chat-avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
      <div class="chat-bubble">
        <ChatMarkdown :content="msg.content" />
      </div>
    </div>
    <!-- 加载中 -->
    <div v-if="loading && streamingContent" class="chat-message assistant">
      <div class="chat-avatar">AI</div>
      <div class="chat-bubble streaming">
        <ChatMarkdown :content="streamingContent" />
        <span class="cursor">|</span>
      </div>
    </div>
    <div v-if="loading && !streamingContent" class="chat-message assistant">
      <div class="chat-avatar">AI</div>
      <div class="chat-bubble thinking">思考中...</div>
    </div>
    <!-- 空态 -->
    <div v-if="!messages.length && !loading" class="chat-empty">
      你好，我是云岚到家 AI 助手，有什么可以帮你的？
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import ChatMarkdown from './ChatMarkdown.vue'

interface ChatMsg {
  role: 'user' | 'assistant'
  content: string
}

const props = defineProps<{
  messages: ChatMsg[]
  streamingContent: string
  loading: boolean
}>()

const listRef = ref<HTMLElement>()

// 新消息到达时自动滚到底部
watch(
  () => [props.messages.length, props.streamingContent],
  () => nextTick(() => {
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  }),
  { deep: true, immediate: true }
)
</script>

<style lang="less" scoped>
.chat-message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: #f9f9f9;
}
.chat-message {
  display: flex;
  gap: 8px;
  max-width: 85%;

  &.user {
    align-self: flex-end;
    flex-direction: row-reverse;
    .chat-avatar { background: var(--td-brand-color, #0052d9); color: #fff; }
    .chat-bubble { background: var(--td-brand-color, #0052d9); color: #fff; }
  }
  &.assistant {
    align-self: flex-start;
    .chat-avatar { background: #e8e8e8; color: #333; }
    .chat-bubble { background: #fff; color: #333; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
  }
}
.chat-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}
.chat-bubble {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;

  &.streaming { border: 1px dashed #ddd; }
}
.cursor {
  display: inline-block;
  animation: blink 1s step-end infinite;
  color: var(--td-brand-color, #0052d9);
}
@keyframes blink {
  50% { opacity: 0; }
}
.thinking {
  color: #999;
  font-style: italic;
}
.chat-empty {
  align-self: center;
  color: #999;
  font-size: 14px;
  margin-top: 40px;
}
</style>
