<template>
  <el-container style="height:100%">
    <el-aside width="200px" class="aside"><div class="logo">企业AI知识库</div>
      <el-menu :default-active="$route.path" router background-color="#001529" text-color="#bbb" active-text-color="#fff">
        <el-menu-item index="/chat"><el-icon><ChatDotRound /></el-icon>智能问答</el-menu-item>
        <el-menu-item index="/documents"><el-icon><Document /></el-icon>文档管理</el-menu-item>
        <el-menu-item index="/history"><el-icon><Clock /></el-icon>问答历史</el-menu-item>
        <!-- 以下菜单仅系统管理员可见 -->
        <template v-if="admin">
          <el-menu-item index="/kbs"><el-icon><Collection /></el-icon>知识库管理</el-menu-item>
          <el-menu-item index="/users"><el-icon><User /></el-icon>用户管理</el-menu-item>
          <el-menu-item index="/eval"><el-icon><DataAnalysis /></el-icon>效果评测</el-menu-item>
        </template>
      </el-menu></el-aside>
    <el-container><el-header class="header"><span>{{ displayName }}<el-tag v-if="admin" size="small" style="margin-left:6px">管理员</el-tag></span><el-button link @click="logout">退出登录</el-button></el-header>
      <el-main><router-view /></el-main></el-container>
  </el-container>
</template>
<script setup>
import { useRouter } from 'vue-router'
const displayName = localStorage.getItem('displayName') || localStorage.getItem('username')
const admin = localStorage.getItem('role') === 'admin'
const router = useRouter()
function logout() { localStorage.clear(); sessionStorage.clear(); router.push('/login') }
</script>
<style scoped>.aside{background:#001529}.logo{color:#fff;font-weight:600;font-size:17px;padding:20px;text-align:center}.el-menu{border:none}
.header{background:#fff;display:flex;justify-content:flex-end;align-items:center;gap:12px;box-shadow:0 1px 4px rgba(0,0,0,.08)}</style>
