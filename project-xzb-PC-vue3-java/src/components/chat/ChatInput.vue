<!-- 聊天输入框 -->
<template>
  <div class="chat-input-area">
    <textarea
      ref="inputRef"
      class="chat-textarea"
      v-model="inputText"
      :placeholder="placeholder"
      :disabled="loading"
      @keydown.enter.exact.prevent="handleSend"
      rows="1"
    ></textarea>
    <button
      class="chat-send-btn"
      :disabled="!canSend"
      @click="handleSend"
    >
      {{ loading ? '...' : '发送' }}
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  loading: boolean
  placeholder?: string
}>()

const emit = defineEmits<{ (e: 'send', content: string): void }>()

const inputText = ref('')
const inputRef = ref<HTMLTextAreaElement>()

const canSend = computed(() => inputText.value.trim() && !props.loading)

function handleSend() {
  const content = inputText.value.trim()
  if (!content || props.loading) return
  emit('send', content)
  inputText.value = ''
}
</script>

<style lang="less" scoped>
.chat-input-area {
  display: flex;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid #eee;
  gap: 8px;
  background: #fff;
}
.chat-textarea {
  flex: 1;
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 14px;
  resize: none;
  outline: none;
  min-height: 38px;
  max-height: 100px;
  line-height: 1.5;
  font-family: inherit;

  &:focus {
    border-color: var(--td-brand-color, #0052d9);
  }
  &:disabled {
    background: #f5f5f5;
  }
}
.chat-send-btn {
  width: 60px;
  height: 38px;
  border: none;
  border-radius: 8px;
  background: var(--td-brand-color, #0052d9);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  flex-shrink: 0;

  &:disabled {
    background: #ccc;
    cursor: not-allowed;
  }
  &:hover:not(:disabled) {
    opacity: 0.9;
  }
}
</style>
