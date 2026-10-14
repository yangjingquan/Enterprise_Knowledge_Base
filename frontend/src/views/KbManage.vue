<!--
  知识库管理（仅管理员）：新建 / 编辑 / 删除知识库，设置成员及其角色。
  可见范围：公开 = 所有登录用户可查看；私有 = 仅被授权的成员可查看。
  成员角色：查看（问答、看原文）/ 编辑（还可上传、删除文档）。
-->
<template>
  <div class="page">
    <PageHeader title="知识库管理" icon="Collection" desc="按部门划分知识库，设置可见范围与成员权限（查看 / 编辑）">
      <el-button type="primary" icon="Plus" @click="edit()">新建知识库</el-button>
    </PageHeader>

    <el-skeleton v-if="loading && !list.length" :rows="6" animated class="panel panel-pad" />
    <div v-else class="grid">
      <div v-for="(row, i) in list" :key="row.id" class="kb panel">
        <div class="kb-top">
          <div class="kb-icon" :style="{ background: palette[i % palette.length] }"><el-icon><Folder /></el-icon></div>
          <div class="kb-title">
            <b>{{ row.name }}</b>
            <el-tag :type="row.visibility === 'public' ? 'success' : 'info'" size="small" round effect="light">
              {{ row.visibility === 'public' ? '公开 · 全员可查看' : '私有 · 仅成员' }}
            </el-tag>
          </div>
        </div>
        <p class="desc">{{ row.description || '暂无说明' }}</p>
        <div class="kb-stats">
          <div><b>{{ row.docCount ?? 0 }}</b><span>文档</span></div>
          <div><b>{{ row.memberCount ?? 0 }}</b><span>成员</span></div>
          <div><b>#{{ row.id }}</b><span>ID</span></div>
        </div>
        <div class="kb-actions">
          <el-button text type="primary" icon="UserFilled" @click="openMembers(row)">成员</el-button>
          <el-button text type="primary" icon="Edit" @click="edit(row)">编辑</el-button>
          <el-popconfirm title="确定删除该知识库？（库中有文档时无法删除）" width="260" @confirm="remove(row)">
            <template #reference><el-button text type="danger" icon="Delete">删除</el-button></template>
          </el-popconfirm>
        </div>
      </div>
      <div class="kb add-card" @click="edit()">
        <el-icon><Plus /></el-icon><span>新建知识库</span>
      </div>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑知识库' : '新建知识库'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="可见范围">
          <el-radio-group v-model="form.visibility">
            <el-radio value="private">私有（仅成员）</el-radio>
            <el-radio value="public">公开（全员可查看）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 成员 -->
    <el-dialog v-model="memberVisible" :title="`成员 · ${current?.name || ''}`" width="620px">
      <div class="add">
        <el-select v-model="addUser" filterable placeholder="选择用户" style="width:240px">
          <el-option
            v-for="u in candidates"
            :key="u.id"
            :label="`${u.displayName || u.username}（${u.username}）`"
            :value="u.id"
          />
        </el-select>
        <el-select v-model="addRole" style="width:120px">
          <el-option label="查看" value="viewer" />
          <el-option label="编辑" value="editor" />
        </el-select>
        <el-button :disabled="!addUser" @click="addMember">添加</el-button>
      </div>
      <el-table :data="members" size="small">
        <el-table-column label="用户">
          <template #default="{ row }">{{ row.displayName || row.username }}（{{ row.username }}）</template>
        </el-table-column>
        <el-table-column label="角色" width="140">
          <template #default="{ row }">
            <el-select v-model="row.role" size="small">
              <el-option label="查看" value="viewer" />
              <el-option label="编辑" value="editor" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column width="80">
          <template #default="{ $index }">
            <el-button link type="danger" @click="members.splice($index, 1)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="tip">系统管理员无需授权即可访问全部知识库。</p>
      <template #footer>
        <el-button @click="memberVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMembers">保存成员</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import PageHeader from '../components/PageHeader.vue'

const palette = ['linear-gradient(135deg,#6366f1,#8b5cf6)', 'linear-gradient(135deg,#0ea5e9,#22d3ee)', 'linear-gradient(135deg,#10b981,#34d399)', 'linear-gradient(135deg,#f59e0b,#fb923c)', 'linear-gradient(135deg,#ec4899,#f472b6)']

const list = ref([])
const loading = ref(false)
const formVisible = ref(false)
const form = reactive({ id: null, name: '', description: '', visibility: 'private' })
const memberVisible = ref(false)
const current = ref(null)
const members = ref([])
const users = ref([])
const addUser = ref(null)
const addRole = ref('viewer')

/** 可添加的用户：排除管理员和已是成员的用户 */
const candidates = computed(() => users.value.filter(u =>
  u.role !== 'admin' && !members.value.some(m => m.userId === u.id)))

async function load() {
  loading.value = true
  try {
    list.value = await api.get('/admin/kbs')
  } finally {
    loading.value = false
  }
}

function edit(row) {
  Object.assign(form, row
    ? { id: row.id, name: row.name, description: row.description, visibility: row.visibility }
    : { id: null, name: '', description: '', visibility: 'private' })
  formVisible.value = true
}

async function save() {
  const body = { name: form.name, description: form.description, visibility: form.visibility }
  if (form.id) await api.put(`/admin/kbs/${form.id}`, body)
  else await api.post('/admin/kbs', body)
  ElMessage.success('已保存')
  formVisible.value = false
  load()
}

async function remove(row) {
  await api.delete(`/admin/kbs/${row.id}`)
  ElMessage.success('已删除')
  load()
}

async function openMembers(row) {
  current.value = row
  const [m, u] = await Promise.all([api.get(`/admin/kbs/${row.id}/members`), api.get('/admin/users')])
  members.value = m
  users.value = u
  addUser.value = null
  memberVisible.value = true
}

function addMember() {
  const u = users.value.find(x => x.id === addUser.value)
  members.value.push({ userId: u.id, username: u.username, displayName: u.displayName, role: addRole.value })
  addUser.value = null
}

async function saveMembers() {
  await api.put(`/admin/kbs/${current.value.id}/members`, members.value.map(m => ({ userId: m.userId, role: m.role })))
  ElMessage.success('成员已保存')
  memberVisible.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.kb { padding: 20px; display: flex; flex-direction: column; transition: transform .2s, box-shadow .2s; }
.kb:hover { transform: translateY(-3px); box-shadow: var(--shadow-lg); }
.kb-top { display: flex; gap: 12px; align-items: center; }
.kb-icon { width: 46px; height: 46px; border-radius: 12px; display: grid; place-items: center; color: #fff; font-size: 22px; flex-shrink: 0; }
.kb-title { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
.kb-title b { font-size: 16px; }
.desc { color: var(--text-2); font-size: 13px; line-height: 1.6; min-height: 42px; margin: 14px 0; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.kb-stats { display: grid; grid-template-columns: repeat(3, 1fr); background: #f8f9fc; border-radius: 10px; padding: 10px 0; text-align: center; }
.kb-stats b { display: block; font-size: 18px; }
.kb-stats span { font-size: 12px; color: var(--text-3); }
.kb-actions { display: flex; justify-content: flex-end; margin-top: 12px; gap: 0; }
.add-card { border: 2px dashed #d4d7e3; border-radius: var(--radius); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: var(--text-3); cursor: pointer; min-height: 240px; font-size: 14px; }
.add-card .el-icon { font-size: 30px; }
.add-card:hover { border-color: var(--brand); color: var(--brand); background: var(--brand-soft); }
.add { display: flex; gap: 8px; margin-bottom: 12px; }
.tip { color: var(--text-3); font-size: 12px; }
</style>
