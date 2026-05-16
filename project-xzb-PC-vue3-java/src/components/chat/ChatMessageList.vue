<!-- 聊天消息列表 — 基于 TDesign ChatList + ChatMessage -->
<template>
  <div class="chat-message-list">
    <ChatList
      v-if="messages.length || loading"
      :auto-scroll="true"
    >
      <ChatMessage
        v-for="(msg, idx) in messages"
        :key="idx"
        :role="msg.role"
        :placement="msg.role === 'user' ? 'right' : 'left'"
        :variant="msg.role === 'user' ? 'base' : 'text'"
        :actions="false"
      >
        <template #avatar>
          <span class="custom-avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</span>
        </template>
        <template #content>
          <ChatMarkdown :content="msg.content" />
        </template>
      </ChatMessage>

      <!-- 流式加载中的 AI 回复 -->
      <ChatMessage
        v-if="loading && streamingContent"
        role="assistant"
        placement="left"
        variant="text"
        :actions="false"
      >
        <template #avatar>
          <span class="custom-avatar">🤖</span>
        </template>
        <template #content>
          <ChatMarkdown :content="streamingContent" :streaming="true" />
        </template>
      </ChatMessage>

      <!-- 等待中(还没收到第一个 token) -->
      <ChatMessage
        v-if="loading && !streamingContent"
        role="assistant"
        placement="left"
        variant="text"
        :actions="false"
      >
        <template #avatar>
          <span class="custom-avatar">🤖</span>
        </template>
        <template #content>
          <span class="loading-text">思考中...</span>
        </template>
      </ChatMessage>
    </ChatList>

    <!-- 空态 -->
    <div v-if="!messages.length && !loading" class="chat-empty">
      你好，我是云岚到家 AI 助手，有什么可以帮你的？
    </div>
  </div>
</template>

<script setup lang="ts">
import { ChatList, ChatMessage } from '@tdesign-vue-next/chat'
import ChatMarkdown from './ChatMarkdown.vue'

defineProps<{
  messages: { role: 'user' | 'assistant'; content: string }[]
  streamingContent: string
  loading: boolean
}>()
</script>

<style lang="less" scoped>
.chat-message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f9f9f9;
}
.loading-text {
  color: #999;
  font-style: italic;
  font-size: 14px;
}
.custom-avatar {
  font-size: 20px;
  line-height: 1;
}
.chat-empty {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  color: #999;
  font-size: 14px;
  padding: 0 16px;
}
</style>
