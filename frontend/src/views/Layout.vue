<!--
  后台布局：左侧可折叠深色导航（分组），顶部栏含折叠按钮、面包屑和用户菜单，内容区带切换动画。
  响应式：桌面（≥1200px）展开；平板（768–1199px）默认折叠为图标栏，可手动展开；
  手机（<768px）隐藏侧边栏，点左上角汉堡按钮从左侧滑出导航抽屉。
-->
<template>
  <el-container class="layout">
    <!-- 手机：导航抽屉 -->
    <el-drawer v-if="isPhone" v-model="navOpen" direction="ltr" size="264px" :with-header="false" class="nav-drawer" modal-class="nav-mask">
      <div class="aside drawer-aside">
        <div class="logo" @click="go('/chat')">
          <div class="mark"><el-icon><MagicStick /></el-icon></div>
          <div class="brand"><b>企业AI知识库</b><span>星辰智造 · RAG</span></div>
        </div>
        <el-scrollbar>
          <el-menu :default-active="$route.path" class="menu" @select="go">
            <div class="group">工作台</div>
            <el-menu-item v-for="m in mainMenus" :key="m.path" :index="m.path">
              <el-icon><component :is="m.icon" /></el-icon><template #title>{{ m.title }}</template>
            </el-menu-item>
            <template v-if="admin">
              <div class="group">系统管理</div>
              <el-menu-item v-for="m in adminMenus" :key="m.path" :index="m.path">
                <el-icon><component :is="m.icon" /></el-icon><template #title>{{ m.title }}</template>
              </el-menu-item>
            </template>
          </el-menu>
        </el-scrollbar>
        <div class="aside-foot"><el-icon><Cpu /></el-icon><span>DeepSeek · 百炼 Embedding</span></div>
      </div>
    </el-drawer>

    <el-aside v-if="!isPhone" :width="collapsed ? '68px' : '228px'" class="aside">
      <div class="logo" @click="$router.push('/chat')">
        <div class="mark"><el-icon><MagicStick /></el-icon></div>
        <transition name="fade"><div v-if="!collapsed" class="brand"><b>企业AI知识库</b><span>星辰智造 · RAG</span></div></transition>
      </div>
      <el-scrollbar>
        <el-menu :default-active="$route.path" router :collapse="collapsed" :collapse-transition="false" class="menu">
          <div v-if="!collapsed" class="group">工作台</div>
          <el-menu-item v-for="m in mainMenus" :key="m.path" :index="m.path">
            <el-icon><component :is="m.icon" /></el-icon><template #title>{{ m.title }}</template>
          </el-menu-item>
          <!-- 以下菜单仅系统管理员可见 -->
          <template v-if="admin">
            <div v-if="!collapsed" class="group">系统管理</div>
            <el-menu-item v-for="m in adminMenus" :key="m.path" :index="m.path">
              <el-icon><component :is="m.icon" /></el-icon><template #title>{{ m.title }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
      <div v-if="!collapsed" class="aside-foot">
        <el-icon><Cpu /></el-icon><span>DeepSeek · 百炼 Embedding</span>
      </div>
    </el-aside>

    <el-container class="right">
      <el-header class="header">
        <div class="h-left">
          <el-button text circle class="fold" :aria-label="isPhone ? '打开导航' : '折叠导航'" @click="toggleNav">
            <el-icon :size="isPhone ? 22 : 18"><component :is="isPhone ? 'Menu' : collapsed ? 'Expand' : 'Fold'" /></el-icon>
          </el-button>
          <b v-if="isCompact" class="m-title">{{ $route.meta.title }}</b>
          <el-breadcrumb v-else separator="/">
            <el-breadcrumb-item :to="{ path: '/chat' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="$route.meta.group">{{ $route.meta.group }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ $route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <el-dropdown trigger="click" @command="onCommand">
          <div class="user">
            <el-avatar :size="34" class="avatar">{{ initial }}</el-avatar>
            <div class="u-text">
              <b>{{ displayName }}</b>
              <span>{{ admin ? '系统管理员' : '普通用户' }}</span>
            </div>
            <el-icon class="arrow"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled><el-icon><User /></el-icon>{{ username }}</el-dropdown-item>
              <el-dropdown-item command="history"><el-icon><Clock /></el-icon>我的问答历史</el-dropdown-item>
              <el-dropdown-item divided command="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in"><component :is="Component" /></transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { isPhone, isCompact, PAD_MAX } from '../responsive'

const displayName = localStorage.getItem('displayName') || localStorage.getItem('username')
const username = localStorage.getItem('username')
const admin = localStorage.getItem('role') === 'admin'
const router = useRouter()
const collapsed = ref(window.innerWidth <= PAD_MAX)
const navOpen = ref(false)
const initial = computed(() => (displayName || '?').slice(0, 1).toUpperCase())

const mainMenus = [
  { path: '/chat', title: '智能问答', icon: 'ChatDotRound' },
  { path: '/documents', title: '文档管理', icon: 'Document' },
  { path: '/datasets', title: '数据集', icon: 'Grid' },
  { path: '/history', title: '问答历史', icon: 'Clock' },
]
const adminMenus = [
  { path: '/kbs', title: '知识库管理', icon: 'Collection' },
  { path: '/users', title: '用户管理', icon: 'User' },
  { path: '/eval', title: '效果评测', icon: 'DataAnalysis' },
]

function toggleNav() {
  if (isPhone.value) navOpen.value = true
  else collapsed.value = !collapsed.value
}
/** 抽屉里点菜单：跳转并关闭抽屉 */
function go(path) {
  navOpen.value = false
  if (path !== router.currentRoute.value.path) router.push(path)
}

function onCommand(c) {
  if (c === 'logout') logout()
  else if (c === 'history') router.push('/history')
}
function logout() { localStorage.clear(); sessionStorage.clear(); router.push('/login') }

// 从桌面缩到平板宽度时自动折叠；放大回桌面时自动展开
let lastWide = window.innerWidth > PAD_MAX
const onResize = () => {
  const wide = window.innerWidth > PAD_MAX
  if (wide !== lastWide) collapsed.value = !wide
  lastWide = wide
  if (!isPhone.value) navOpen.value = false
}
onMounted(() => window.addEventListener('resize', onResize))
onUnmounted(() => window.removeEventListener('resize', onResize))
</script>

<style scoped>
.layout { height: 100%; }
.aside { background: var(--sider); display: flex; flex-direction: column; transition: width .25s; overflow: hidden; }
.logo { height: 64px; display: flex; align-items: center; gap: 12px; padding: 0 16px; cursor: pointer; flex-shrink: 0; }
.mark { width: 36px; height: 36px; border-radius: 10px; display: grid; place-items: center; color: #fff; font-size: 20px; flex-shrink: 0;
  background: linear-gradient(135deg, #6366f1, #a855f7); box-shadow: 0 4px 14px rgba(99, 102, 241, .45); }
.brand { display: flex; flex-direction: column; white-space: nowrap; }
.brand b { color: #fff; font-size: 16px; letter-spacing: .5px; }
.brand span { color: #94a3b8; font-size: 12px; margin-top: 2px; }
.group { color: #64748b; font-size: 12px; padding: 18px 22px 6px; letter-spacing: 1px; }
.menu { border: none; background: transparent; padding: 0 10px;
  --el-menu-text-color: #cbd5e1; --el-menu-hover-text-color: #fff; --el-menu-active-color: #fff;
  --el-menu-hover-bg-color: rgba(255,255,255,.06); --el-menu-item-height: 44px; }
.menu.el-menu--collapse { padding: 0 8px; width: 68px; }
.menu :deep(.el-menu-item) { border-radius: 10px; margin: 2px 0; }
.menu :deep(.el-menu-item.is-active) { background: linear-gradient(90deg, #4f46e5, #6d5df6); box-shadow: 0 4px 12px rgba(79, 70, 229, .4); }
.aside-foot { color: #64748b; font-size: 12px; padding: 14px 20px; display: flex; gap: 6px; align-items: center; border-top: 1px solid rgba(255,255,255,.06); white-space: nowrap; }
.right { min-width: 0; }
.header { height: 64px; background: rgba(255,255,255,.85); backdrop-filter: blur(8px); display: flex; justify-content: space-between; align-items: center;
  border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 10; }
.h-left { display: flex; align-items: center; gap: 12px; }
.fold { color: var(--text-2); }
.user { display: flex; align-items: center; gap: 10px; cursor: pointer; padding: 4px 8px; border-radius: 10px; transition: background .2s; outline: none; }
.user:hover { background: #f3f4f8; }
.avatar { background: linear-gradient(135deg, #6366f1, #a855f7); font-weight: 600; }
.u-text { display: flex; flex-direction: column; line-height: 1.25; }
.u-text b { font-size: 14px; color: var(--text-1); }
.u-text span { font-size: 12px; color: var(--text-3); }
.arrow { color: var(--text-3); }
.main { padding: 24px; overflow: auto; }
.page-enter-active, .page-leave-active { transition: opacity .18s, transform .18s; }
.page-enter-from { opacity: 0; transform: translateY(6px); }
.page-leave-to { opacity: 0; }
.fade-enter-active { transition: opacity .3s; } .fade-enter-from { opacity: 0; }
.m-title { font-size: 16px; color: var(--text-1); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.drawer-aside { height: 100%; padding-top: env(safe-area-inset-top); padding-bottom: env(safe-area-inset-bottom); }
:global(.nav-drawer .el-drawer__body) { padding: 0 !important; background: var(--sider); overflow: hidden !important; }
@media (max-width: 1199px) { .u-text { display: none; } .arrow { display: none; } }
@media (max-width: 767px) {
  .header { height: calc(56px + env(safe-area-inset-top)); padding: env(safe-area-inset-top) max(12px, env(safe-area-inset-right)) 0 max(8px, env(safe-area-inset-left)); }
  .h-left { gap: 6px; min-width: 0; }
  .fold { width: 44px; height: 44px; }
  .main { padding: 12px max(12px, env(safe-area-inset-right)) max(12px, env(safe-area-inset-bottom)) max(12px, env(safe-area-inset-left)); }
}
</style>
