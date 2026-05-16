// 项目入口文件
import { createApp } from 'vue'

import TDesign from 'tdesign-vue-next'
import 'tdesign-vue-next/es/style/index.css'
import '@tdesign-vue-next/chat/es/style/index.css'
import 'katex/dist/katex.min.css'
import mermaid from 'mermaid'
import 'default-passive-events'

// 通过 CDN 导入 KaTeX（Cherry Markdown 需要全局的 window.katex）
import 'https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js'

// 全局注册 mermaid
window.mermaid = mermaid
mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose', // 允许外部脚本
})

import { store } from './store'
import router from './router'
import BaiduMap from 'vue-baidu-map-3x'
import '@/style/index.less'
import './permission' // permission是路由权限控制
import App from './App.vue'


const app = createApp(App)

app.use(BaiduMap, {
  ak: 'PYGGt7wfgyorRicuGvuHybVdQmGbPIq5'
})
app.use(TDesign)
app.use(store)
app.use(router)

app.mount('#app')
