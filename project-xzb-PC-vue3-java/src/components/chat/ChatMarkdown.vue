<!-- Markdown 渲染组件 — 基于 markstream-vue -->
<template>
  <div class="chat-markdown">
    <MarkdownRender
      :content="content"
      :final="!streaming"
      custom-id="chat"
      :max-live-nodes="streaming ? 0 : 320"
      :batch-rendering="true"
      :fade="streaming"
    />
  </div>
</template>

<script setup lang="ts">
import MarkdownRender, { enableKatex, enableMermaid, MarkdownCodeBlockNode, setCustomComponents } from 'markstream-vue'
import 'markstream-vue/index.css'
import 'katex/dist/katex.min.css'

let ready = false
function initPlugins() {
  if (ready) return
  try {
    // 注册 Shiki 代码高亮(替换默认的 Monaco 渲染器)
    setCustomComponents({ code_block: MarkdownCodeBlockNode })
    enableMermaid()
    enableKatex()
    ready = true
  } catch { /* 静默 */ }
}

defineProps<{
  content: string
  streaming?: boolean
}>()

initPlugins()
</script>

<style lang="less">
/** 重置 markstream-vue 在聊天气泡内的多余间距 */
.chat-markdown .markstream-vue {
  --ms-flow-paragraph-y: 0;
  --ms-flow-heading-1-mt: 0;
  --ms-flow-heading-1-mb: 0;
  --ms-flow-heading-2-mt: 0;
  --ms-flow-heading-2-mb: 0;
  --ms-flow-heading-3-mt: 0;
  --ms-flow-heading-3-mb: 0;
  --ms-flow-list-y: 0;
  --ms-flow-codeblock-y: 0;
}

.chat-markdown {
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  overflow-wrap: break-word;

  img {
    max-width: 100%;
  }
}
</style>
