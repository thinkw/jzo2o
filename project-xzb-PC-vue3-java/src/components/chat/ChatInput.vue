<!-- 聊天输入框 — 基于 TDesign ChatSender -->
<template>
  <div class="chat-input-area">
    <ChatSender
      v-model="inputText"
      :loading="loading"
      :placeholder="placeholder"
      @send="handleSend"
      @stop="$emit('stop')"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ChatSender } from '@tdesign-vue-next/chat'

const props = defineProps<{
  loading: boolean
  placeholder?: string
}>()

const emit = defineEmits<{
  (e: 'send', content: string): void
  (e: 'stop'): void
}>()

const inputText = ref('')

function handleSend() {
  const content = inputText.value.trim()
  if (!content || props.loading) return
  emit('send', content)
  inputText.value = ''
}
</script>

<style lang="less" scoped>
.chat-input-area {
  flex-shrink: 0;
  border-top: 1px solid var(--td-component-border, #eee);
  background: #fff;
}
</style>
