import Layout from '@/layouts/index.vue'
import { ChatIcon } from 'tdesign-icons-vue-next'

const chatRouter = [
  {
    path: '/ai-chat',
    name: 'aiChat',
    component: Layout,
    redirect: '/ai-chat/index',
    meta: {
      title: 'AI 助手',
      icon: ChatIcon,
      single: true,
    },
    children: [
      {
        path: 'index',
        name: 'aiChatIndex',
        component: () => import('@/pages/ai-chat/index.vue'),
        meta: {
          title: 'AI 助手',
          singles: true,
        },
      },
    ],
  },
]

export default chatRouter
