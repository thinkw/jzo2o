/**
 * Markdown 渲染工具 — 支持 Markdown + Mermaid 图表 + LaTeX 数学公式
 */
import MarkdownIt from 'markdown-it'
import katex from 'katex'

/**
 * 生成不会被 markdown 语法误解析的唯一占位符 (纯字母+数字, 零 markdown 语义)
 */
function ph(type: string, n: number): string {
  return `KATEX${type}${String(n).padStart(4, '0')}END`
}

// 创建 markdown-it 实例, 关闭原始 HTML 标签 (防 XSS), 开启链接自动识别
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})

// 保存原始 fence 渲染规则
const defaultFence = md.renderer.rules.fence!

/**
 * 自定义 fence 渲染: mermaid 代码块转为 .mermaid div, 由组件端渲染为 SVG
 */
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const lang = token.info.trim()

  if (lang === 'mermaid') {
    // markdown-it 内置 HTML 转义, 组件端通过 textContent 自动解码
    const escaped = md.utils.escapeHtml(token.content)
    return `<div class="mermaid">${escaped}</div>`
  }

  return defaultFence(tokens, idx, options, env, self)
}

/**
 * 预处理 LaTeX 数学公式: 先用 KaTeX 渲染为 HTML, 用占位符保护, 避免与 markdown 语法冲突
 */
function preprocessLatex(content: string): { text: string; placeholders: Map<string, string> } {
  const placeholders = new Map<string, string>()
  let counter = 0

  // 先处理块级公式 $$...$$
  let processed = content.replace(/\$\$([\s\S]*?)\$\$/g, (_match, formula: string) => {
    try {
      const html = katex.renderToString(formula.trim(), {
        displayMode: true,
        throwOnError: false,
      })
      const key = ph('BLOCK', counter++)
      placeholders.set(key, html)
      // 左右加空格, 避免占位符和周围 markdown 文本粘连形成意外语法
      return ` ${key} `
    } catch {
      return _match
    }
  })

  // 再处理行内公式 $...$ (排除 $$ 边界)
  processed = processed.replace(/(?<!\$)\$(?!\$)([^$]+?)(?<!\$)\$(?!\$)/g, (_match, formula: string) => {
    try {
      const html = katex.renderToString(formula.trim(), {
        displayMode: false,
        throwOnError: false,
      })
      const key = ph('INLINE', counter++)
      placeholders.set(key, html)
      return key
    } catch {
      return _match
    }
  })

  return { text: processed, placeholders }
}

/**
 * 将 KaTeX 占位符替换回渲染后的 HTML
 */
function restorePlaceholders(html: string, placeholders: Map<string, string>): string {
  let result = html
  placeholders.forEach((value, key) => {
    // 使用 split+join 替代 replace, 避免正则特殊字符问题
    result = result.split(key).join(value)
  })
  return result
}

/**
 * 渲染 Markdown 文本为 HTML
 * 流程: LaTeX 预处理 → markdown-it 渲染 → 恢复 LaTeX HTML
 */
export function renderMarkdown(content: string): string {
  if (!content) return ''

  try {
    const { text, placeholders } = preprocessLatex(content)
    let html = md.render(text)
    html = restorePlaceholders(html, placeholders)
    return html
  } catch (e) {
    console.error('[Markdown] 渲染失败:', e)
    return content
  }
}
