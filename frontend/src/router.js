import { createRouter, createWebHistory } from 'vue-router'
import Login from './views/Login.vue'
import Layout from './views/Layout.vue'
import Documents from './views/Documents.vue'
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
        { path: 'chat', component: Chat },
        { path: 'documents', component: Documents },
        { path: 'history', component: History },
        { path: 'kbs', component: KbManage, meta: { admin: true } },
        { path: 'users', component: Users, meta: { admin: true } },
        { path: 'eval', component: Eval, meta: { admin: true } },
      ],
    },
  ],
})

router.beforeEach(to => {
  if (to.path !== '/login' && !localStorage.getItem('token')) return '/login'
  if (to.meta.admin && localStorage.getItem('role') !== 'admin') return '/chat'
  return true
})

export default router
