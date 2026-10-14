<!-- 登录页：左侧品牌介绍，右侧登录表单；窄屏只显示表单 -->
<template>
  <div class="login">
    <section class="hero">
      <div class="glow g1" /><div class="glow g2" />
      <div class="hero-inner">
        <div class="logo"><div class="mark"><el-icon><MagicStick /></el-icon></div><span>星辰智造 · 企业AI知识库</span></div>
        <h1>让企业知识<br /><em>一问即达</em></h1>
        <p class="lead">基于 RAG 检索增强生成，制度、手册、表格、PPT、扫描件统一入库，回答附带原文出处，可追溯、可验证。</p>
        <ul class="features">
          <li><el-icon><Search /></el-icon><div><b>混合检索 + 重排序</b><span>向量 + 关键词召回，前 3 条命中率 100%</span></div></li>
          <li><el-icon><Reading /></el-icon><div><b>原文溯源高亮</b><span>点击引用直接定位到 PDF 原页并高亮</span></div></li>
          <li><el-icon><Lock /></el-icon><div><b>知识库权限隔离</b><span>按部门授权，查看 / 编辑分级管理</span></div></li>
        </ul>
      </div>
      <div class="copyright">© 2026 星辰智造 · Powered by DeepSeek & 阿里云百炼</div>
    </section>

    <section class="form-side">
      <div class="form-card">
        <h2>欢迎回来 👋</h2>
        <p class="sub">登录你的账号，开始智能问答</p>
        <el-form :model="f" @submit.prevent="login" size="large" label-position="top">
          <el-form-item label="用户名">
            <el-input v-model="f.username" placeholder="请输入用户名" prefix-icon="User" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="f.password" type="password" placeholder="请输入密码" prefix-icon="Lock" show-password
              autocomplete="current-password" @keyup.enter="login" />
          </el-form-item>
          <el-button type="primary" class="submit" :loading="loading" @click="login">登 录</el-button>
        </el-form>
        <el-divider>演示账号（点击填入）</el-divider>
        <div class="demo">
          <div v-for="a in demos" :key="a.u" class="demo-item" @click="fill(a)">
            <el-avatar :size="28" :style="{ background: a.c }">{{ a.n.slice(0, 1) }}</el-avatar>
            <div><b>{{ a.n }}</b><span>{{ a.u }}</span></div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'

const f = reactive({ username: '', password: '' })
const loading = ref(false)
const router = useRouter()
const demos = [
  { n: '管理员', u: 'admin', p: 'admin123', c: '#4f46e5' },
  { n: '人事', u: 'hr01', p: 'Hr@2026', c: '#0ea5e9' },
  { n: '财务', u: 'fin01', p: 'Fin@2026', c: '#10b981' },
  { n: '工程', u: 'eng01', p: 'Eng@2026', c: '#f59e0b' },
]
const fill = a => Object.assign(f, { username: a.u, password: a.p })

async function login() {
  if (!f.username || !f.password) return
  loading.value = true
  try {
    const r = await api.post('/auth/login', f)
    localStorage.setItem('token', r.token)
    localStorage.setItem('username', r.username)
    localStorage.setItem('displayName', r.displayName || r.username)
    localStorage.setItem('role', r.role)
    sessionStorage.clear()   // 换账号登录时清掉上一个人的对话
    router.push('/chat')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login { height: 100%; display: flex; background: #fff; }
.hero { flex: 1.15; position: relative; overflow: hidden; color: #fff; display: flex; flex-direction: column; justify-content: center; padding: 56px 72px;
  background: radial-gradient(1200px 600px at 10% 10%, #312e81 0%, transparent 60%), linear-gradient(135deg, #0f172a 0%, #1e1b4b 55%, #3b0764 100%); }
.glow { position: absolute; border-radius: 50%; filter: blur(80px); opacity: .55; }
.g1 { width: 420px; height: 420px; background: #6366f1; right: -120px; top: -100px; }
.g2 { width: 360px; height: 360px; background: #a855f7; left: -120px; bottom: -140px; }
.hero-inner { position: relative; max-width: 520px; }
.logo { display: flex; align-items: center; gap: 12px; font-size: 16px; font-weight: 600; margin-bottom: 48px; }
.mark { width: 40px; height: 40px; border-radius: 12px; display: grid; place-items: center; font-size: 22px; background: linear-gradient(135deg, #6366f1, #a855f7); }
h1 { font-size: 44px; line-height: 1.25; margin: 0 0 20px; font-weight: 700; letter-spacing: 1px; }
h1 em { font-style: normal; background: linear-gradient(90deg, #a5b4fc, #f0abfc); -webkit-background-clip: text; background-clip: text; color: transparent; }
.lead { color: #c7d2fe; font-size: 15px; line-height: 1.8; margin: 0 0 36px; }
.features { list-style: none; padding: 0; margin: 0; display: grid; gap: 14px; }
.features li { display: flex; gap: 14px; align-items: center; padding: 14px 16px; border-radius: 14px; background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.1); backdrop-filter: blur(6px); }
.features .el-icon { font-size: 22px; color: #a5b4fc; flex-shrink: 0; }
.features b { display: block; font-size: 14px; }
.features span { font-size: 12px; color: #a5b4fc; }
.copyright { position: absolute; bottom: 24px; left: 72px; font-size: 12px; color: #818cf8; }
.form-side { flex: 1; display: flex; align-items: center; justify-content: center; padding: 32px; background: #fafbff; }
.form-card { width: 100%; max-width: 400px; }
h2 { margin: 0 0 6px; font-size: 26px; }
.sub { color: var(--text-3); margin: 0 0 28px; font-size: 14px; }
.submit { width: 100%; height: 44px; font-size: 15px; margin-top: 6px; background: linear-gradient(90deg, #4f46e5, #7c3aed); border: none; box-shadow: 0 8px 20px rgba(79,70,229,.3); }
.demo { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.demo-item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; cursor: pointer; background: #fff; transition: all .2s; }
.demo-item:hover { border-color: var(--brand); box-shadow: 0 4px 12px rgba(79,70,229,.12); }
.demo-item b { display: block; font-size: 13px; }
.demo-item span { font-size: 12px; color: var(--text-3); }
@media (max-width: 900px) { .hero { display: none; } }
</style>
