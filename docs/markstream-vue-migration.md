# markstream-vue 引入过程记录

> 将前端富文本渲染从自研 markdown.ts + ChatMarkdown.vue 替换为 markstream-vue 组件库。

## 一、方案选型

对比了四个方案：

| 方案 | 框架 | 版本 | Vue 要求 | 结论 |
|------|------|------|----------|------|
| @wooshiiltd/streamdown-vue | Vue 3 | 0.1.1 | ^3.4.0 | ❌ 项目 Vue 3.2.31 不兼容；需 Tailwind v4 |
| Vercel Streamdown | React | 1.x | - | ❌ React 专属，跨框架嵌入不划算 |
| vue-stream-markdown | Vue 3 | 0.7.2 | >=3.0.0 | 备选，需 shadcn 设计令牌 |
| **markstream-vue** | Vue 3 | 0.0.14-beta.8 | **>=3.0.0** | ✅ 自包含 CSS，无框架依赖 |

选定 markstream-vue 的关键原因：
- Vue `>=3.0.0`，项目的 3.2.31 无需升级
- 自包含 CSS（`.markstream-vue` scoped），不依赖 Tailwind/shadcn
- KaTeX/Mermaid 通过 Web Worker 渲染，不阻塞主线程

## 二、变更内容

### 删除
- `src/utils/markdown.ts`（213 行，LaTeX 预处理/裁剪/占位符全部移除）
- `markdown-it`、`@types/markdown-it` 依赖
- `@wooshiiltd/streamdown-vue` 依赖（选型时临时安装的）

### 新增
- `markstream-vue` (`^0.0.14-beta.8`)
- `shiki` (`^3.23.0`) — 代码高亮引擎  
- `stream-markdown` (`0.0.15`) — Shiki 流式渲染桥接
- `stubs/stream-monaco.js`、`stubs/@antv-infographic.js`、`stubs/@terrastruct-d2.js` — 未安装的可选依赖空桩

### 重写
- `src/components/chat/ChatMarkdown.vue`（从 ~240 行减到 ~60 行）

```vue
<MarkdownRender
  :content="content"
  :final="!streaming"
  custom-id="chat"
  :max-live-nodes="streaming ? 0 : 320"
  :batch-rendering="true"
  :fade="streaming"
/>
```

### 修改
- `vite.config.ts` — 添加 `resolve.alias`（可选依赖空桩）、`optimizeDeps.exclude`、`build.rollupOptions.external`
- `package.json` — 净移除 markdown-it 及相关，新增 markstream-vue/shiki/stream-markdown

## 三、遇到的问题与修复

### 1. Vite 构建报错：动态 import 解析失败

**现象**：`Could not resolve "stream-monaco"` / `"stream-markdown"` / `"@antv/infographic"` / `"@terrastruct/d2"`

**根因**：markstream-vue 对这些可选依赖使用动态 `import()`，Vite 的 esbuild 预构建会尝试解析全部动态导入。

**修复**：
- 为未安装的可选依赖创建空桩模块（`stubs/*.js`）
- 在 `vite.config.ts` 的 `resolve.alias` 中映射到空桩
- `stream-markdown` 后续实际安装了，从空桩中移除
- `stream-monaco` / `@antv/infographic` / `@terrastruct/d2` 保持空桩

### 2. 消息气泡上下多余空白行

**现象**：气泡内容和气泡边缘之间有明显的空白间距。

**根因**：markstream-vue 的 CSS 变量 `--ms-flow-paragraph-y: 1.5em` 给每个段落（`.paragraph-node`）上下各加 1.5em margin，叠加气泡自身的 `padding: 10px 14px` 造成额外空白。

**修复**：在 `.chat-markdown` 上用更高优先级的 `.chat-markdown .markstream-vue` 选择器覆写全部 flow 间距变量为 0：
```less
.chat-markdown .markstream-vue {
  --ms-flow-paragraph-y: 0;
  --ms-flow-heading-1-mt: 0;  --ms-flow-heading-1-mb: 0;
  --ms-flow-heading-2-mt: 0;  --ms-flow-heading-2-mb: 0;
  --ms-flow-heading-3-mt: 0;  --ms-flow-heading-3-mb: 0;
  --ms-flow-list-y: 0;
  --ms-flow-codeblock-y: 0;
}
```

### 3. `render-code-blocks-as-pre` 导致 Mermaid 不渲染

**现象**：加了 `render-code-blocks-as-pre` 后 Mermaid 图表显示为原始文本。

**根因**：该 prop 把所有 fenced code block（包括 ` ```mermaid `）强制渲染为 `<pre><code>`，跳过了 `MermaidBlockNode`。

**修复**：去掉 `render-code-blocks-as-pre`。该 prop 是排查代码块问题时误加的。

### 4. markstream-vue 包被误删

**现象**：`npm uninstall @wooshiiltd/streamdown-vue markdown-it @types/markdown-it --legacy-peer-deps` 后 markstream-vue 从 node_modules 消失。

**根因**：`--legacy-peer-deps` 下 npm 的级联清理行为删除了更多包。

**修复**：重新 `npm i markstream-vue shiki@^3.0.0 stream-markdown`。

### 5. 代码块无高亮（未完全解决，降级为 CSS 兜底）

**现象演变**：
1. 初始安装后：代码块显示为纯文本 `<pre><code>`，无深色背景/圆角/等宽字体
2. 尝试安装 shiki + stream-markdown 后：代码块内容消失（Shiki 4.x→3.x 版本兼容问题）
3. 降级 shiki 到 3.23.0 后：代码块恢复但仅显示纯文本
4. 排查发现 markstream-vue 默认使用 `CodeBlockNode`（Monaco 编辑器），不是 `MarkdownCodeBlockNode`（Shiki）
5. 尝试通过 `:components="{ code: MarkdownCodeBlockNode }"` 强制切换，但该 prop 未被 MarkdownRender 识别（DOM 中显示为 `components="[object Object]"`）

**当前状态**：
- 代码块以 `<pre class="language-xxx"><code>...</code></pre>` 正确渲染
- Mermaid、KaTeX、普通 Markdown 均正常
- 代码块缺少视觉样式（深色背景、圆角、等宽字体）

**兜底方案**：用纯 CSS 给代码块添加基础视觉样式（见下文）。

## 四、当前 CSS 配置（ChatMarkdown.vue 完整样式）

```less
// 间距重置
.chat-markdown .markstream-vue {
  --ms-flow-paragraph-y: 0;
  --ms-flow-heading-1-mt: 0;  --ms-flow-heading-1-mb: 0;
  --ms-flow-heading-2-mt: 0;  --ms-flow-heading-2-mb: 0;
  --ms-flow-heading-3-mt: 0;  --ms-flow-heading-3-mb: 0;
  --ms-flow-list-y: 0;
  --ms-flow-codeblock-y: 0;
}

// 基础适配
.chat-markdown {
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  overflow-wrap: break-word;
  img { max-width: 100%; }
}
```

## 五、依赖清单（最终状态）

```
markstream-vue@0.0.14-beta.8
├── stream-markdown@0.0.15 (peer, Shiki 流式渲染)
│   ├── shiki@3.23.0 (peer, 语法高亮引擎)
│   └── shiki-stream@0.1.4 (dep, 流式 token 更新)
└── stream-markdown-parser@0.0.95 (dep, Markdown 解析)
```

`mermaid@11.14.0` 和 `katex@0.16.45` 保留作为 markstream-vue 的 peer deps。

## 六、未解决的问题

1. **代码块语法高亮**：需后续单独立项。可能方向：
   - 升级到支持 `components` prop 的 markstream-vue 版本
   - 在 CSS 方案基础上用 highlight.js 做前端高亮
   - 升级 Vue 到 3.4+ 后切换到 @wooshiiltd/streamdown-vue

2. **markstream-vue 是 beta 版本**（0.0.14-beta.8），API 可能变化，升级时需验证。

## 七、构建配置要点

`vite.config.ts` 关键配置：
```ts
resolve: {
  alias: {
    // 未安装的可选依赖 → 空桩
    'stream-monaco': path.resolve(__dirname, './stubs/stream-monaco.js'),
    '@antv/infographic': path.resolve(__dirname, './stubs/@antv-infographic.js'),
    '@terrastruct/d2': path.resolve(__dirname, './stubs/@terrastruct-d2.js'),
  }
},
build: {
  rollupOptions: {
    external: ['stream-monaco', '@antv/infographic', '@terrastruct/d2', 'vue-i18n']
  }
},
optimizeDeps: {
  exclude: ['stream-monaco', '@antv/infographic', '@terrastruct/d2']
}
```
