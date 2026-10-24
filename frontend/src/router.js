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
import Brokers from './views/Brokers.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    {
      path: '/',
      component: Layout,
      redirect: () => localStorage.getItem('role') === 'admin' ? '/brokers' : '/chat',
      children: [
        { path: 'brokers', component: Brokers, meta: { superAdmin: true, title: '客户管理', icon: 'OfficeBuilding', group: '系统' } },
        { path: 'chat', component: Chat, meta: { business: true, title: '智能问答', icon: 'ChatDotRound' } },
        { path: 'documents', component: Documents, meta: { business: true, title: '文档管理', icon: 'Document' } },
        { path: 'datasets', component: Datasets, meta: { business: true, title: '数据集', icon: 'Grid' } },
        { path: 'history', component: History, meta: { business: true, title: '问答历史', icon: 'Clock' } },
        { path: 'kbs', component: KbManage, meta: { brokerAdmin: true, title: '知识库管理', icon: 'Collection', group: '客户管理' } },
        { path: 'users', component: Users, meta: { brokerAdmin: true, title: '用户管理', icon: 'User', group: '客户管理' } },
        { path: 'eval', component: Eval, meta: { brokerAdmin: true, title: '效果评测', icon: 'DataAnalysis', group: '客户管理' } },
      ],
    },
  ],
})

router.beforeEach(to => {
  if (to.path !== '/login' && !localStorage.getItem('token')) return '/login'
  const role = localStorage.getItem('role')
  if (to.meta.superAdmin && role !== 'admin') return role === 'admin' ? '/brokers' : '/chat'
  if (to.meta.business && role === 'admin') return '/brokers'
  if (to.meta.brokerAdmin && role !== 'broker_admin') return '/chat'
  return true
})

router.afterEach(to => { document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '企业AI知识库' })

export default router
