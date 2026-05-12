<!-- Markdown 渲染组件: 支持 Markdown + Mermaid 图表 + LaTeX 公式 -->
<template>
  <div ref="containerRef" class="chat-markdown" v-html="renderedHtml"></div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, nextTick } from 'vue'
import { renderMarkdown, renderMarkdownStreaming } from '@/utils/markdown'
import mermaid from 'mermaid'
// 直接导入 KaTeX CSS (Vite 原生支持 CSS import)
import 'katex/dist/katex.min.css'

// 全局初始化 mermaid (仅一次, 所有组件实例共享)
let mermaidReady = false
function initMermaid() {
  if (mermaidReady) return
  try {
    mermaid.initialize({
      startOnLoad: false,
      theme: 'default',
      securityLevel: 'loose',
      fontFamily: 'inherit',
    })
    mermaidReady = true
  } catch (e) {
    console.warn('[ChatMarkdown] Mermaid 初始化失败:', e)
  }
}

const props = defineProps<{
  content: string
  /** 是否为流式输出 — 流式时裁掉尾部不完整的 LaTeX, 避免闪烁 */
  streaming?: boolean
}>()

const containerRef = ref<HTMLElement>()
const renderedHtml = ref('')
let diagramCounter = 0

/**
 * 渲染当前组件范围内的 mermaid 图表
 */
async function renderMermaidDiagrams() {
  const container = containerRef.value
  if (!container) return

  const mermaidEls = container.querySelectorAll('.mermaid')
  if (mermaidEls.length === 0) return

  for (const el of mermaidEls) {
    // textContent 自动解码 markdown-it 的 HTML 实体转义
    const code = (el.textContent || '').trim()
    if (!code) continue

    try {
      const id = `mermaid-${Date.now()}-${diagramCounter++}`
      const { svg } = await mermaid.render(id, code)
      el.innerHTML = svg
    } catch (e) {
      console.warn('[ChatMarkdown] Mermaid 渲染失败:', e)
      el.classList.add('mermaid-error')
      el.textContent = `[图表渲染错误]\n${code}`
    }
  }
}

/**
 * 完整渲染流程: markdown → HTML → DOM → mermaid 图表
 */
async function doRender(content: string) {
  if (!content) {
    renderedHtml.value = ''
    return
  }

  renderedHtml.value = props.streaming
    ? renderMarkdownStreaming(content)
    : renderMarkdown(content)
  await nextTick()
  await renderMermaidDiagrams()
}

// 监听 content 变化, immediate 确保首次加载即渲染
watch(
  () => props.content,
  (val) => doRender(val),
  { immediate: true },
)

onMounted(() => initMermaid())
</script>

<style lang="less">
/**
 * 聊天 Markdown 样式 (不使用 scoped, 内容通过 v-html 渲染不受 scoped 影响)
 */

.chat-markdown {
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  overflow-wrap: break-word;

  // ===== 段落 =====
  p {
    margin: 0 0 8px;
    &:last-child { margin-bottom: 0; }
  }

  // ===== 标题 =====
  h1, h2, h3, h4, h5, h6 {
    margin: 14px 0 6px;
    font-weight: 600;
    line-height: 1.35;
    &:first-child { margin-top: 0; }
  }
  h1 { font-size: 1.4em; }
  h2 { font-size: 1.25em; }
  h3 { font-size: 1.1em; }

  // ===== 列表 =====
  ul, ol {
    margin: 6px 0;
    padding-left: 22px;
  }
  li {
    margin: 3px 0;
    &::marker { color: #999; }
  }

  // ===== 代码块 =====
  pre {
    background: #1e1e1e;
    color: #d4d4d4;
    border-radius: 6px;
    padding: 12px 16px;
    overflow-x: auto;
    margin: 8px 0;
    font-size: 13px;
    line-height: 1.55;

    code {
      background: none;
      color: inherit;
      padding: 0;
      font-size: inherit;
    }
  }

  // ===== 行内代码 =====
  code {
    background: rgba(0, 0, 0, 0.06);
    color: #c7254e;
    padding: 2px 6px;
    border-radius: 3px;
    font-size: 0.9em;
    font-family: 'Menlo', 'Consolas', 'Courier New', monospace;
  }

  // ===== 表格 =====
  table {
    border-collapse: collapse;
    margin: 8px 0;
    width: 100%;
  }
  th, td {
    border: 1px solid #ddd;
    padding: 6px 10px;
    text-align: left;
    font-size: 13px;
  }
  th {
    background: #f5f5f5;
    font-weight: 600;
  }

  // ===== 引用 =====
  blockquote {
    margin: 8px 0;
    padding: 6px 14px;
    border-left: 3px solid var(--td-brand-color, #0052d9);
    color: #555;
    background: #f5f7fa;
    p { margin: 4px 0; }
  }

  // ===== 链接 =====
  a {
    color: var(--td-brand-color, #0052d9);
    text-decoration: none;
    &:hover { text-decoration: underline; }
  }

  // ===== 粗体 / 斜体 =====
  strong { font-weight: 600; }
  em { font-style: italic; }

  // ===== 分割线 =====
  hr {
    border: none;
    border-top: 1px solid #e8e8e8;
    margin: 12px 0;
  }

  // ===== 图片 =====
  img {
    max-width: 100%;
    border-radius: 4px;
  }

  // ===== Mermaid 图表容器 =====
  .mermaid {
    margin: 10px 0;
    text-align: center;
    overflow-x: auto;

    svg {
      max-width: 100%;
      height: auto;
    }
  }

  // Mermaid 错误状态
  .mermaid-error {
    background: #fff3cd;
    color: #856404;
    padding: 12px;
    border-radius: 6px;
    font-size: 13px;
    white-space: pre-wrap;
  }

  // ===== KaTeX 公式 =====
  .katex-display {
    margin: 8px 0;
    overflow-x: auto;
    overflow-y: hidden;
  }
  .katex {
    font-size: 1.05em;
  }
}
</style>
