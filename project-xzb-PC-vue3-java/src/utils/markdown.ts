/**
 * Markdown 渲染工具 — 支持 Markdown + Mermaid 图表 + LaTeX 数学公式
 * LaTeX 分隔符支持: $$...$$ | \[...\] | $...$ | \(...\)
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
    const escaped = md.utils.escapeHtml(token.content)
    return `<div class="mermaid">${escaped}</div>`
  }

  return defaultFence(tokens, idx, options, env, self)
}

/** 用 KaTeX 渲染公式, 失败时返回 null (静默, 控制台可查) */
function renderKatex(formula: string, displayMode: boolean): string | null {
  try {
    return katex.renderToString(formula.trim(), {
      displayMode,
      throwOnError: false,
      strict: false,
    })
  } catch (e) {
    // throwOnError=false 理论上不抛异常, 保留 catch 作防御
    return null
  }
}

/**
 * 预处理 LaTeX 数学公式: 先用 KaTeX 渲染为 HTML, 用占位符保护, 避免与 markdown 语法冲突
 * 处理顺序: $$ → \[ → $ → \( (块级优先, 避免单 $ 误匹配 \[ 内的 $ 字面量)
 */
function preprocessLatex(content: string): { text: string; placeholders: Map<string, string> } {
  const placeholders = new Map<string, string>()
  let counter = 0

  // 1. 块级公式 $$...$$
  let processed = content.replace(/\$\$([\s\S]*?)\$\$/g, (_match, formula: string) => {
    const html = renderKatex(formula, true)
    if (!html) return _match
    const key = ph('BLOCK', counter++)
    placeholders.set(key, html)
    return ` ${key} `
  })

  // 2. 块级公式 \[...\] (LLM 常用显示公式格式)
  processed = processed.replace(/\\\[([\s\S]*?)\\\]/g, (_match, formula: string) => {
    const html = renderKatex(formula, true)
    if (!html) return _match
    const key = ph('BLOCK', counter++)
    placeholders.set(key, html)
    return ` ${key} `
  })

  // 3. 行内公式 $...$ (排除 $$ 边界)
  processed = processed.replace(/(?<!\$)\$(?!\$)([^$]+?)(?<!\$)\$(?!\$)/g, (_match, formula: string) => {
    const html = renderKatex(formula, false)
    if (!html) return _match
    const key = ph('INLINE', counter++)
    placeholders.set(key, html)
    return key
  })

  // 4. 行内公式 \(...\) (LLM 常用行内公式格式)
  processed = processed.replace(/\\\(([\s\S]*?)\\\)/g, (_match, formula: string) => {
    const html = renderKatex(formula, false)
    if (!html) return _match
    const key = ph('INLINE', counter++)
    placeholders.set(key, html)
    return key
  })

  return { text: processed, placeholders }
}

/**
 * 将 KaTeX 占位符替换回渲染后的 HTML
 */
function restorePlaceholders(html: string, placeholders: Map<string, string>): string {
  let result = html
  placeholders.forEach((value, key) => {
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

/**
 * 裁掉尾部不完整的 LaTeX 块, 避免流式输出中正则匹配不到闭合分隔符
 * 检测四种格式: $$...$$  \[...\]  $...$  \(...\)
 */
function clipIncompleteLatex(content: string): string {
  let result = content
  let changed = true

  while (changed) {
    changed = false

    // 检查 $$ 块
    if ((result.match(/\$\$/g) || []).length % 2 === 1) {
      result = result.substring(0, result.lastIndexOf('$$')).trimEnd()
      changed = true
      continue
    }

    // 检查 \[ 块 (匹配 \\[ 文本)
    if ((result.match(/\\\[/g) || []).length !== (result.match(/\\\]/g) || []).length) {
      // 找最后一个未闭合的 \[  (找到 \] 数量少于 \[)
      // 从末尾向前找, 找到多余的 \[ 的位置
      let depth = 0
      for (let i = 0; i < result.length; i++) {
        if (result[i] === '\\' && result[i + 1] === '[') { depth++; i++; }
        else if (result[i] === '\\' && result[i + 1] === ']') { depth--; i++; }
      }
      // depth > 0 表示有未闭合的 \[, 找最后一个 \[ 并裁掉
      if (depth > 0) {
        const idx = result.lastIndexOf('\\[')
        if (idx !== -1) {
          result = result.substring(0, idx).trimEnd()
          changed = true
          continue
        }
      }
    }

    // 检查 $ 行内公式 (排除 $$ 后)
    const withoutDd = result.replace(/\$\$/g, '')
    const sCount = (withoutDd.match(/\$/g) || []).length
    if (sCount % 2 === 1) {
      for (let i = result.length - 1; i >= 0; i--) {
        if (result[i] === '$') {
          if (i > 0 && result[i - 1] === '$') { i--; continue }
          result = result.substring(0, i)
          changed = true
          break
        }
      }
      continue
    }

    // 检查 \( 块
    if ((result.match(/\\\(/g) || []).length !== (result.match(/\\\)/g) || []).length) {
      // 找最后一个未闭合的 \(
      let depth = 0
      for (let i = 0; i < result.length; i++) {
        if (result[i] === '\\' && result[i + 1] === '(') { depth++; i++; }
        else if (result[i] === '\\' && result[i + 1] === ')') { depth--; i++; }
      }
      if (depth > 0) {
        const idx = result.lastIndexOf('\\(')
        if (idx !== -1) {
          result = result.substring(0, idx).trimEnd()
          changed = true
        }
      }
    }
  }

  return result
}

/**
 * 流式渲染 Markdown — 先裁掉尾部不完整的 LaTeX, 避免未闭合公式干扰
 * 仅流式场景使用; 对话完成后使用 renderMarkdown 原样渲染完整内容
 */
export function renderMarkdownStreaming(content: string): string {
  if (!content) return ''
  const clipped = clipIncompleteLatex(content)
  return renderMarkdown(clipped)
}
