<template>
  <div class="wrap"><el-card class="card">
    <h2>企业AI知识库</h2><p class="sub">基于 RAG 的企业文档智能问答</p>
    <el-form @submit.prevent="login">
      <el-form-item><el-input v-model="f.username" placeholder="用户名" prefix-icon="User" size="large" /></el-form-item>
      <el-form-item><el-input v-model="f.password" type="password" placeholder="密码" prefix-icon="Lock" size="large" show-password @keyup.enter="login" /></el-form-item>
      <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="login">登 录</el-button>
    </el-form><p class="tip">演示账号：admin / admin123（管理员）<br />hr01 / Hr@2026 · fin01 / Fin@2026 · eng01 / Eng@2026</p>
  </el-card></div>
</template>
<script setup>
import { reactive, ref } from 'vue'; import { useRouter } from 'vue-router'; import api from '../api'
const f = reactive({ username: '', password: '' }), loading = ref(false), router = useRouter()
async function login() { loading.value = true
  try { const r = await api.post('/auth/login', f); localStorage.setItem('token', r.token); localStorage.setItem('username', r.username)
    localStorage.setItem('displayName', r.displayName || r.username); localStorage.setItem('role', r.role)
    sessionStorage.clear()   // 换账号登录时清掉上一个人的对话
    router.push('/chat') } finally { loading.value = false } }
</script>
<style scoped>.wrap{height:100%;display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,#1e3c72,#2a5298)}
.card{width:380px;padding:12px 8px}h2{text-align:center;margin:0 0 4px}.sub{text-align:center;color:#888;margin:0 0 24px;font-size:13px}.tip{text-align:center;color:#aaa;font-size:12px;margin-top:16px}</style>
