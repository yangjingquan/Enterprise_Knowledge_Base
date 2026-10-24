<!--
  问答历史页面：查看所有问答记录，按反馈筛选（有用 / 没用 / 未反馈），
  顶部显示反馈统计；展开行可看完整答案、改写后的问题、引用来源和点踩原因。
-->
<template>
  <div class="page">
    <PageHeader title="问答历史" icon="Clock" :desc="admin ? '查看本客户问答记录与反馈，发现知识库的薄弱环节' : '查看你的问答记录与反馈'">
      <el-button icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <div class="stats">
      <StatCard icon="ChatLineSquare" :value="stats.total" label="问答总数" />
      <StatCard icon="CircleCheck" :value="stats.up" label="👍 有用" color="#16a34a" bg="#dcfce7" />
      <StatCard icon="Warning" :value="stats.down" label="👎 没用" color="#dc2626" bg="#fee2e2" />
      <StatCard icon="TrendCharts" :value="stats.up + stats.down ? satisfaction : '—'" :unit="stats.up + stats.down ? '%' : ''"
        :label="`满意度（未反馈 ${stats.none} 条）`" color="#7c3aed" bg="#f3e8ff" />
    </div>

    <div class="panel panel-pad">
      <div class="toolbar-row filter">
        <el-input v-model="keyword" placeholder="搜索问题" prefix-icon="Search" clearable class="kw" />
        <el-radio-group v-model="filter" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="up">有用</el-radio-button>
          <el-radio-button value="down">没用</el-radio-button>
          <el-radio-button value="none">未反馈</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="shown" v-loading="loading" row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail">
              <div v-if="row.rewrittenQuestion" class="meta"><el-icon><Connection /></el-icon>多轮改写：{{ row.rewrittenQuestion }}</div>
              <el-alert v-if="row.feedbackComment" :title="`点踩原因：${row.feedbackComment}`" type="error" :closable="false" show-icon class="reason" />
              <div class="markdown answer" v-html="md(row.answer)" />
              <DataAnswer v-if="analysis(row)" :a="analysis(row)" :mode="row.mode" :history-id="row.id" :switchable="false" />
              <div v-if="sources(row).length" class="refs">
                <span class="ref-label">引用</span>
                <el-tag v-for="s in sources(row)" :key="s.index" effect="plain" class="ref">[{{ s.index }}] {{ s.file_name }} · {{ loc(s) }}</el-tag>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="问题" :min-width="isNarrow ? 180 : 300">
          <template #default="{ row }">
            <span class="question">{{ row.question }}</span>
            <!-- 手机 / 平板：次要列合并为摘要行 -->
            <div v-if="isNarrow" class="meta-line">
              <template v-if="isPhone">
                <el-tag v-if="row.feedback === 1" size="small" type="success" round>👍 有用</el-tag>
                <el-tag v-else-if="row.feedback === -1" size="small" type="danger" round>👎 没用</el-tag>
              </template>
              <el-tag size="small" effect="plain" :type="MODE[row.mode || 'doc'][1]">{{ MODE[row.mode || 'doc'][0] }}</el-tag>
              <span>来源 {{ sources(row).length }}</span>
              <span v-if="admin">{{ row.username }}</span>
              <span>{{ shortTime(row.createTime) }}</span>
              <el-button v-if="row.sessionId" link type="primary" size="small" @click.stop="showSession(row.sessionId)">{{ sessionCount(row.sessionId) }} 轮会话</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="!isPhone" label="反馈" width="96">
          <template #default="{ row }">
            <el-tag v-if="row.feedback === 1" type="success" round>👍 有用</el-tag>
            <el-tooltip v-else-if="row.feedback === -1" :content="row.feedbackComment || '未填写原因'">
              <el-tag type="danger" round>👎 没用</el-tag>
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="会话" width="100">
          <template #default="{ row }">
            <el-button v-if="row.sessionId" link type="primary" icon="ChatDotRound" @click="showSession(row.sessionId)">
              {{ sessionCount(row.sessionId) }} 轮
            </el-button>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="类型" width="90">
          <template #default="{ row }"><el-tag size="small" effect="plain" :type="MODE[row.mode || 'doc'][1]">{{ MODE[row.mode || 'doc'][0] }}</el-tag></template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="来源" width="70" align="center">
          <template #default="{ row }">{{ sources(row).length }}</template>
        </el-table-column>
        <el-table-column v-if="admin && !isNarrow" label="提问人" width="110">
          <template #default="{ row }">
            <div class="asker"><el-avatar :size="22">{{ (row.username || '?').slice(0, 1).toUpperCase() }}</el-avatar>{{ row.username }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="时间" width="170">
          <template #default="{ row }"><span class="muted">{{ (row.createTime || "").replace("T", " ") }}</span></template>
        </el-table-column>
        <template #empty><el-empty description="暂无问答记录" :image-size="90" /></template>
      </el-table>
    </div>

    <!-- 查看整段多轮对话 -->
    <el-drawer v-model="sessionVisible" title="完整对话" :size="isPhone ? '100%' : '640px'">
      <div v-for="r in sessionRows" :key="r.id" class="round">
        <div class="q-row"><span class="q">{{ r.question }}</span></div>
        <div v-if="r.rewrittenQuestion" class="meta"><el-icon><Connection /></el-icon>改写：{{ r.rewrittenQuestion }}</div>
        <div class="a markdown" v-html="md(r.answer)" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { md } from '../markdown'
import PageHeader from '../components/PageHeader.vue'
import StatCard from '../components/StatCard.vue'
import DataAnswer from '../components/DataAnswer.vue'
import api from '../api'
import { isPhone, isNarrow, shortTime } from '../responsive'
import { isBrokerAdmin } from '../kb'

// 普通用户只能看到自己的问答记录（后端已限制）；管理员看到全部，并显示提问人
const admin = isBrokerAdmin()  // 客户管理员可看本客户全部历史

const list = ref([])
const loading = ref(false)
const filter = ref('')
const stats = ref({ total: 0, up: 0, down: 0, none: 0 })
const sessionVisible = ref(false)
const sessionRows = ref([])
const allRows = ref([])     // 未筛选的数据，用于统计每个会话的轮数

const keyword = ref('')
const shown = computed(() => list.value.filter(r => !keyword.value || (r.question || '').includes(keyword.value)))
const satisfaction = computed(() => Math.round(stats.value.up * 100 / (stats.value.up + stats.value.down)))

const MODE = { doc: ['文档', 'info'], data: ['数据', 'success'], mixed: ['混合', 'warning'] }
/** analysis 字段是 JSON 字符串：{sql: {sql, assumptions, dataset, tables, alternatives}, table, chart}，展开成 DataAnswer 需要的结构 */
const parsed = new Map()
function analysis(row) {
  if (!row.analysis) return null
  if (!parsed.has(row.id)) {
    let a = null
    try {
      const raw = JSON.parse(row.analysis)
      a = { ...(raw.sql || {}), table: raw.table, chart: raw.chart }
      a.alternatives = []   // 历史回看不提供切换
    } catch { /* 忽略损坏数据 */ }
    parsed.set(row.id, a)
  }
  return parsed.get(row.id)
}

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
  if (s.section) return `章节「${s.section}」`
  if (/\.pptx$/i.test(s.file_name)) return `第${s.page}张幻灯片`
  if (/\.docx$/i.test(s.file_name)) return 'Word 文档'
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
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 16px; }
.filter { margin-bottom: 16px; }
.kw { width: 260px; }
.question { font-weight: 500; color: var(--text-1); }
.detail { padding: 8px 48px 16px; }
@media (max-width: 767px) { .detail { padding: 6px 4px 12px; } }
.meta { font-size: 12px; color: var(--text-3); display: flex; align-items: center; gap: 4px; margin-bottom: 8px; }
.reason { margin-bottom: 10px; }
.answer { background: #fafbfd; border: 1px solid var(--border); border-radius: 10px; padding: 12px 16px; }
.refs { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.ref-label { font-size: 12px; color: var(--text-3); }
.asker { display: flex; align-items: center; gap: 6px; }
.round { padding: 4px 0 20px; }
.q-row { display: flex; justify-content: flex-end; margin-bottom: 8px; }
.q { background: linear-gradient(135deg, #4f46e5, #6d5df6); color: #fff; padding: 8px 14px; border-radius: 14px 4px 14px 14px; max-width: 85%; }
.a { background: #fff; border: 1px solid var(--border); border-radius: 4px 14px 14px 14px; padding: 12px 16px; }
@media (max-width: 1100px) { .stats { grid-template-columns: repeat(2, 1fr); } }
</style>
