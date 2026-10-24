<!--
  客户（broker）管理：仅超级管理员。创建客户、启停、指定客户管理员。
-->
<template>
  <div class="page">
    <PageHeader title="客户管理" icon="OfficeBuilding" desc="创建客户、启停、为每个客户指定管理员；超管不进入业务数据">
      <el-button type="primary" icon="Plus" @click="editBroker()">新建客户</el-button>
    </PageHeader>
    <div class="panel panel-pad">
      <el-table :data="list" v-loading="loading">
        <el-table-column label="客户" min-width="180">
          <template #default="{ row }">
            <div><b>{{ row.name }}</b></div>
            <span class="muted">编码 {{ row.code }} · ID {{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="管理员 / 用户" width="140">
          <template #default="{ row }">{{ row.adminCount }} / {{ row.userCount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="v => toggle(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="editBroker(row)">编辑</el-button>
            <el-button link type="primary" @click="openAdmins(row)">管理员</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无客户" :image-size="90" /></template>
      </el-table>
    </div>

    <el-dialog v-model="brokerVisible" :title="brokerForm.id ? `编辑客户 · ${brokerForm.code}` : '新建客户'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="brokerForm.name" placeholder="客户名称" /></el-form-item>
        <el-form-item label="编码">
          <el-input v-model="brokerForm.code" :disabled="!!brokerForm.id" placeholder="仅字母数字，创建后不可改" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="brokerForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="brokerVisible = false">取消</el-button>
        <el-button type="primary" @click="saveBroker">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="adminVisible" :title="`客户管理员 · ${current?.name || ''}`" width="640px">
      <div class="toolbar-row" style="margin-bottom:12px">
        <el-button type="primary" size="small" icon="Plus" @click="editAdmin()">添加管理员</el-button>
      </div>
      <el-table :data="admins" v-loading="adminLoading" size="small">
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="displayName" label="显示名称" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeAdmin(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="adminFormVisible" title="添加客户管理员" width="440px">
      <el-form label-width="90px">
        <el-form-item label="用户名"><el-input v-model="adminForm.username" placeholder="字母开头，3~30 位" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="adminForm.displayName" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="adminForm.password" type="password" show-password placeholder="至少 6 位" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adminFormVisible = false">取消</el-button>
        <el-button type="primary" @click="saveAdmin">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { encryptPassword } from '../passwordCrypto'
import PageHeader from '../components/PageHeader.vue'

const list = ref([])
const loading = ref(false)
const brokerVisible = ref(false)
const brokerForm = reactive({ id: null, name: '', code: '', enabled: true })

const adminVisible = ref(false)
const adminLoading = ref(false)
const admins = ref([])
const current = ref(null)
const adminFormVisible = ref(false)
const adminForm = reactive({ username: '', displayName: '', password: '' })

async function load() {
  loading.value = true
  try { list.value = await api.get('/super/brokers') }
  finally { loading.value = false }
}

function editBroker(row) {
  Object.assign(brokerForm, {
    id: row?.id ?? null,
    name: row?.name ?? '',
    code: row?.code ?? '',
    enabled: row ? !!row.enabled : true,
  })
  brokerVisible.value = true
}

async function saveBroker() {
  if (!brokerForm.name.trim()) return ElMessage.warning('请填写名称')
  if (!brokerForm.id && !/^[A-Za-z0-9]+$/.test(brokerForm.code)) return ElMessage.warning('编码只能是字母数字')
  if (brokerForm.id) {
    await api.put(`/super/brokers/${brokerForm.id}`, { name: brokerForm.name, enabled: brokerForm.enabled })
  } else {
    await api.post('/super/brokers', { name: brokerForm.name, code: brokerForm.code, enabled: brokerForm.enabled })
  }
  ElMessage.success('已保存')
  brokerVisible.value = false
  load()
}

async function toggle(row, enabled) {
  await api.put(`/super/brokers/${row.id}`, { enabled })
  ElMessage.success(enabled ? '已启用' : '已停用（该客户下用户立即失效）')
  load()
}

async function openAdmins(row) {
  current.value = row
  adminVisible.value = true
  adminLoading.value = true
  try { admins.value = await api.get(`/super/brokers/${row.id}/admins`) }
  finally { adminLoading.value = false }
}

function editAdmin() {
  Object.assign(adminForm, { username: '', displayName: '', password: '' })
  adminFormVisible.value = true
}

async function saveAdmin() {
  if (!adminForm.username || !adminForm.password) return ElMessage.warning('请填写用户名和密码')
  if (adminForm.password.length < 6) return ElMessage.warning('密码至少 6 位')
  let passwordCipher
  try { passwordCipher = await encryptPassword(adminForm.password) }
  catch (e) { if (!e.response) ElMessage.error('密码加密失败：' + e.message); return }
  await api.post(`/super/brokers/${current.value.id}/admins`, {
    username: adminForm.username,
    displayName: adminForm.displayName || adminForm.username,
    passwordCipher,
  })
  ElMessage.success('已添加')
  adminFormVisible.value = false
  openAdmins(current.value)
  load()
}

async function removeAdmin(row) {
  await ElMessageBox.confirm(`确定移除管理员 ${row.username}？账号将被删除。`, '确认')
  await api.delete(`/super/brokers/${current.value.id}/admins/${row.id}`)
  ElMessage.success('已移除')
  openAdmins(current.value)
  load()
}

onMounted(load)
</script>

<style scoped>
.muted { font-size: 12px; color: var(--text-3); }
</style>
