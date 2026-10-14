<!--
  用户管理（仅管理员）：创建用户、修改显示名称 / 角色 / 状态、重置密码、分配知识库权限。
-->
<template>
  <el-card>
    <template #header>
      <div class="hd">
        <b>用户管理</b>
        <el-button type="primary" icon="Plus" @click="edit()">新建用户</el-button>
      </div>
    </template>
    <el-table :data="list" v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="username" label="用户名" width="110" />
      <el-table-column prop="displayName" label="显示名称" width="160" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">
          <el-tag :type="row.role === 'admin' ? 'danger' : ''" size="small">
            {{ row.role === 'admin' ? '系统管理员' : '普通用户' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="知识库权限">
        <template #default="{ row }">
          <span v-if="row.role === 'admin'" class="muted">全部知识库</span>
          <el-tag v-for="g in row.grants" :key="g.kbId" size="small" class="grant" :type="g.role === 'editor' ? 'warning' : 'info'">
            {{ g.kbName }} · {{ ROLE_NAMES[g.role] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @change="v => toggle(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button link type="primary" @click="edit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? `编辑用户 · ${form.username}` : '新建用户'" width="600px">
      <el-form label-width="90px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" :disabled="!!form.id" placeholder="字母开头，3~30 位" />
        </el-form-item>
        <el-form-item label="显示名称"><el-input v-model="form.displayName" placeholder="如：人事专员-李婷" /></el-form-item>
        <el-form-item :label="form.id ? '重置密码' : '密码'">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '不修改请留空' : '至少 6 位'" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="form.role">
            <el-radio value="user">普通用户</el-radio>
            <el-radio value="admin">系统管理员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.role !== 'admin'" label="知识库权限">
          <div class="grants">
            <div v-for="k in kbs" :key="k.id" class="grant-row">
              <span class="kb-name">{{ k.name }}<el-tag v-if="k.visibility === 'public'" size="small" type="success">公开</el-tag></span>
              <el-radio-group v-model="form.grants[k.id]" size="small">
                <el-radio-button value="">{{ k.visibility === 'public' ? '默认查看' : '无权限' }}</el-radio-button>
                <el-radio-button value="viewer">查看</el-radio-button>
                <el-radio-button value="editor">编辑</el-radio-button>
              </el-radio-group>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { ROLE_NAMES } from '../kb'

const list = ref([])
const kbs = ref([])
const loading = ref(false)
const visible = ref(false)
const form = reactive({ id: null, username: '', displayName: '', password: '', role: 'user', grants: {} })

async function load() {
  loading.value = true
  try {
    const [u, k] = await Promise.all([api.get('/admin/users'), api.get('/admin/kbs')])
    list.value = u
    kbs.value = k
  } finally {
    loading.value = false
  }
}

function edit(row) {
  const grants = {}
  kbs.value.forEach(k => { grants[k.id] = '' })
  ;(row?.grants || []).forEach(g => { grants[g.kbId] = g.role })
  Object.assign(form, {
    id: row?.id ?? null,
    username: row?.username ?? '',
    displayName: row?.displayName ?? '',
    password: '',
    role: row?.role ?? 'user',
    grants,
  })
  visible.value = true
}

async function save() {
  const grants = Object.entries(form.grants)
    .filter(([, role]) => role)
    .map(([kbId, role]) => ({ kbId: Number(kbId), role }))
  const body = { displayName: form.displayName, role: form.role, grants, password: form.password || null }
  if (form.id) {
    await api.put(`/admin/users/${form.id}`, body)
  } else {
    await api.post('/admin/users', { ...body, username: form.username })
  }
  ElMessage.success('已保存')
  visible.value = false
  load()
}

async function toggle(row, enabled) {
  await api.put(`/admin/users/${row.id}`, { enabled })
  ElMessage.success(enabled ? '已启用' : '已停用')
  load()
}

onMounted(load)
</script>

<style scoped>
.hd { display: flex; justify-content: space-between; align-items: center; }
.grant { margin: 2px 4px 2px 0; }
.muted { color: #999; }
.grants { width: 100%; }
.grant-row { display: flex; justify-content: space-between; align-items: center; padding: 4px 0; border-bottom: 1px dashed #eee; }
.kb-name { display: flex; gap: 6px; align-items: center; }
</style>
