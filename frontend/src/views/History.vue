<!--
  问答历史页面：查看所有问答记录，按反馈筛选（有用 / 没用 / 未反馈），
  顶部显示反馈统计；展开行可看完整答案、改写后的问题、引用来源和点踩原因。
-->
<template>
  <el-card>
    <template #header>
      <div class="hd">
        <b>问答历史</b>
        <div class="stats">
          <span>共 {{ stats.total }} 条</span>
          <span class="up">👍 有用 {{ stats.up }}</span>
          <span class="down">👎 没用 {{ stats.down }}</span>
          <span>未反馈 {{ stats.none }}</span>
          <span v-if="stats.up + stats.down">满意度 {{ satisfaction }}%</span>
        </div>
        <el-radio-group v-model="filter" size="small" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="up">有用</el-radio-button>
          <el-radio-button value="down">没用</el-radio-button>
          <el-radio-button value="none">未反馈</el-radio-button>
        </el-radio-group>
      </div>
    </template>

    <el-table :data="list" v-loading="loading">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail">
            <p v-if="row.rewrittenQuestion" class="meta">多轮改写：{{ row.rewrittenQuestion }}</p>
            <p v-if="row.feedbackComment" class="meta down">点踩原因：{{ row.feedbackComment }}</p>
            <div v-html="md(row.answer)" />
            <div v-if="sources(row).length" class="meta">
              引用：
              <span v-for="s in sources(row)" :key="s.index">[{{ s.index }}] {{ s.file_name }} {{ loc(s) }}　</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="question" label="问题" min-width="260" />
      <el-table-column label="反馈" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.feedback === 1" type="success" size="small">有用</el-tag>
          <el-tooltip v-else-if="row.feedback === -1" :content="row.feedbackComment || '未填写原因'">
            <el-tag type="danger" size="small">没用</el-tag>
          </el-tooltip>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="会话" width="110">
        <template #default="{ row }">
          <el-button v-if="row.sessionId" link size="small" @click="showSession(row.sessionId)">
            {{ sessionCount(row.sessionId) }} 轮
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="来源数" width="80">
        <template #default="{ row }">{{ sources(row).length }}</template>
      </el-table-column>
      <el-table-column v-if="admin" prop="username" label="提问人" width="90" />
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ (row.createTime || "").replace("T", " ") }}</template>
      </el-table-column>
    </el-table>

    <!-- 查看整段多轮对话 -->
    <el-dialog v-model="sessionVisible" title="完整对话" width="760px">
      <div v-for="r in sessionRows" :key="r.id" class="round">
        <div class="q">问：{{ r.question }}</div>
        <div v-if="r.rewrittenQuestion" class="meta">改写：{{ r.rewrittenQuestion }}</div>
        <div class="a" v-html="md(r.answer)" />
      </div>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { marked } from 'marked'
import api from '../api'
import { isAdmin } from '../kb'

// 普通用户只能看到自己的问答记录（后端已限制）；管理员看到全部，并显示提问人
const admin = isAdmin()

const list = ref([])
const loading = ref(false)
const filter = ref('')
const stats = ref({ total: 0, up: 0, down: 0, none: 0 })
const sessionVisible = ref(false)
const sessionRows = ref([])
const allRows = ref([])     // 未筛选的数据，用于统计每个会话的轮数

const md = t => marked.parse(t || '')
const satisfaction = computed(() => Math.round(stats.value.up * 100 / (stats.value.up + stats.value.down)))

/** sources 字段是 JSON 字符串，解析失败时返回空数组 */
function sources(row) {
  try {
    return JSON.parse(row.sources || '[]')
  } catch {
    return []
  }
}

function loc(s) {
  if (s.sheet_name) return `工作表「${s.sheet_name}」`
  if (/\.pptx$/i.test(s.file_name)) return `第${s.page}张幻灯片`
  return `第${s.page}页`
}

function sessionCount(id) {
  return allRows.value.filter(r => r.sessionId === id).length || 1
}

async function showSession(id) {
  const rows = await api.get('/qa/history', { params: { sessionId: id, limit: 50 } })
  sessionRows.value = rows.reverse()      // 按时间正序显示
  sessionVisible.value = true
}

async function load() {
  loading.value = true
  try {
    const params = filter.value ? { feedback: filter.value, limit: 200 } : { limit: 200 }
    const [rows, s, all] = await Promise.all([
      api.get('/qa/history', { params }),
      api.get('/qa/feedback/stats'),
      filter.value ? api.get('/qa/history', { params: { limit: 500 } }) : null,
    ])
    list.value = rows
    stats.value = s
    allRows.value = all || rows
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.hd { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.stats { display: flex; gap: 14px; font-size: 13px; color: #606266; margin-left: auto; }
.up { color: #67c23a; }
.down { color: #f56c6c; }
.detail { padding: 0 20px; }
.meta { font-size: 12px; color: #909399; }
.muted { color: #c0c4cc; }
.round { border-bottom: 1px solid #f0f0f0; padding: 8px 0; }
.q { font-weight: 500; }
</style>
