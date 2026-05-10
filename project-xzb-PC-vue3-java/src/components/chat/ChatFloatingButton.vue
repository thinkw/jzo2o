<!-- 悬浮聊天按钮 -->
<template>
  <div class="chat-float-btn" @click="toggleChat" :class="{ active: visible }">
    <div class="chat-float-icon">
      <span v-if="!visible">AI</span>
      <span v-else>✕</span>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{ visible: boolean }>()
const emit = defineEmits<{ (e: 'toggle'): void }>()

const toggleChat = () => emit('toggle')
</script>

<style lang="less" scoped>
.chat-float-btn {
  position: fixed;
  right: 28px;
  bottom: 80px;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: var(--td-brand-color, #0052d9);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(0, 82, 217, 0.3);
  z-index: 999;
  transition: transform 0.2s, box-shadow 0.2s;

  &:hover {
    transform: scale(1.08);
    box-shadow: 0 6px 20px rgba(0, 82, 217, 0.45);
  }

  &.active {
    background: #666;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
  }
}
.chat-float-icon {
  font-size: 20px;
  font-weight: 600;
  line-height: 1;
}
</style>
