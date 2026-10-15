import { createRouter, createWebHistory } from 'vue-router'
import Login from './views/Login.vue'
import Layout from './views/Layout.vue'
import Documents from './views/Documents.vue'
import Datasets from './views/Datasets.vue'
import Chat from './views/Chat.vue'
import History from './views/History.vue'
import KbManage from './views/KbManage.vue'
import Users from './views/Users.vue'
import Eval from './views/Eval.vue'

// meta.admin = true 的页面只有系统管理员能进入（后端接口也会再校验一次）
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    {
      path: '/',
      component: Layout,
      redirect: '/chat',
      children: [
        { path: 'chat', component: Chat, meta: { title: '智能问答', icon: 'ChatDotRound' } },
        { path: 'documents', component: Documents, meta: { title: '文档管理', icon: 'Document' } },
        { path: 'datasets', component: Datasets, meta: { title: '数据集', icon: 'Grid' } },
        { path: 'history', component: History, meta: { title: '问答历史', icon: 'Clock' } },
        { path: 'kbs', component: KbManage, meta: { admin: true, title: '知识库管理', icon: 'Collection', group: '系统管理' } },
        { path: 'users', component: Users, meta: { admin: true, title: '用户管理', icon: 'User', group: '系统管理' } },
        { path: 'eval', component: Eval, meta: { admin: true, title: '效果评测', icon: 'DataAnalysis', group: '系统管理' } },
      ],
    },
  ],
})

router.beforeEach(to => {
  if (to.path !== '/login' && !localStorage.getItem('token')) return '/login'
  if (to.meta.admin && localStorage.getItem('role') !== 'admin') return '/chat'
  return true
})

// 浏览器标签页标题
router.afterEach(to => { document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '星辰智造 · 企业AI知识库' })

export default router
