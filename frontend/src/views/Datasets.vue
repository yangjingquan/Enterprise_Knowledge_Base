<!--
  数据集页面（问数）：上传 Excel / CSV → 确认导入方案 → 按表结构入库，供"数据分析"提问。
  数据集不进入文档问答。权限与知识库一致：编辑者可上传、确认、删除，查看者可浏览和预览。
-->
<template>
  <div class="page">
    <PageHeader title="数据集" icon="Grid" desc="上传 Excel / CSV 业务数据，按表格结构入库，提问时精确统计计算">
      <el-button icon="Refresh" @click="load()">刷新</el-button>
      <el-button v-if="editableKbs.length" type="primary" icon="Upload" @click="dialog = true">上传数据</el-button>
    </PageHeader>

    <div class="stats">
      <StatCard icon="Grid" :value="list.length" label="数据集" />
      <StatCard icon="CircleCheck" :value="count('ready')" label="可分析" color="#16a34a" bg="#dcfce7" />
      <StatCard icon="Warning" :value="count('awaiting_confirm')" label="待确认导入" color="#d97706" bg="#fef3c7" />
      <StatCard icon="Tickets" :value="totalRows.toLocaleString()" label="数据总行数" color="#0284c7" bg="#e0f2fe" />
    </div>

    <div class="panel panel-pad">
      <div class="filter toolbar-row">
        <el-input v-model="keyword" placeholder="搜索数据集" prefix-icon="Search" clearable class="kw" />
        <el-select v-model="filterKb" class="kb" placeholder="全部知识库" clearable @change="load()">
          <template #prefix><el-icon><Collection /></el-icon></template>
          <el-option v-for="k in kbs" :key="k.id" :label="k.name" :value="k.id" />
        </el-select>
      </div>
      <el-table :data="shown" v-loading="loading" class="tbl">
        <el-table-column label="数据集" :min-width="isNarrow ? 180 : 280">
          <template #default="{ row }">
            <div class="fname">
              <span class="ficon" :style="{ background: row.fileType === 'csv' ? '#0891b2' : '#16a34a' }">{{ row.fileType.toUpperCase() }}</span>
              <div class="fmain">
                <a class="ftitle link" @click="openRow(row)">{{ row.name }}</a>
                <span v-if="!isNarrow" class="fid">{{ row.fileName }}</span>
                <div v-else class="meta-line">
                  <span v-if="isPhone" :class="['status', row.status]"><i />{{ STATUS[row.status] || row.status }}</span>
                  <span>{{ kbName(row.kbId) }}</span>
                  <span>{{ row.tableCount || 0 }} 表 / {{ (row.rowCount || 0).toLocaleString() }} 行</span>
                  <span>{{ shortTime(row.createTime) }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="知识库" width="150">
          <template #default="{ row }"><el-tag effect="plain" type="info">{{ kbName(row.kbId) }}</el-tag></template>
        </el-table-column>
        <el-table-column v-if="!isPhone" label="状态" width="130">
          <template #default="{ row }">
            <el-tooltip :disabled="row.status !== 'failed'" :content="row.errorMsg || ''">
              <span :class="['status', row.status]"><i />{{ STATUS[row.status] || row.status }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="表 / 行" width="140" align="center">
          <template #default="{ row }">{{ row.tableCount || 0 }} / {{ (row.rowCount || 0).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="上传时间" width="170">
          <template #default="{ row }"><span class="muted">{{ (row.createTime || '').replace('T', ' ') }}</span></template>
        </el-table-column>
        <el-table-column :label="isPhone ? '' : '操作'" :width="isPhone ? 56 : 190" fixed="right">
          <template #default="{ row }">
            <!-- 手机：操作收进"⋯"菜单 -->
            <el-dropdown v-if="isPhone" trigger="click" @command="c => rowCommand(c, row)">
              <el-button link class="more" aria-label="更多操作"><el-icon :size="18"><MoreFilled /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="row.status === 'awaiting_confirm' && canEditKb(row.kbId)" command="confirm" icon="Check">确认导入</el-dropdown-item>
                  <el-dropdown-item v-else-if="row.status === 'ready'" command="view" icon="View">查看</el-dropdown-item>
                  <el-dropdown-item v-if="['ready', 'failed'].includes(row.status) && canEditKb(row.kbId) && row.tableCount !== undefined" command="reimport" icon="Setting">重新导入</el-dropdown-item>
                  <el-dropdown-item v-if="canEditKb(row.kbId)" command="delete" icon="Delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <template v-else>
            <el-button v-if="row.status === 'awaiting_confirm' && canEditKb(row.kbId)" link type="warning" icon="Check" @click="wizardId = row.id">确认导入</el-button>
            <el-button v-else-if="row.status === 'ready'" link type="primary" icon="View" @click="detailId = row.id">查看</el-button>
            <el-button v-if="['ready', 'failed'].includes(row.status) && canEditKb(row.kbId) && row.tableCount !== undefined" link icon="Setting" @click="wizardId = row.id">重新导入</el-button>
            <el-popconfirm v-if="canEditKb(row.kbId)" title="确定删除该数据集及其全部数据？" width="240" @confirm="remove(row.id)">
              <template #reference><el-button link type="danger" icon="Delete">删除</el-button></template>
            </el-popconfirm>
            </template>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无数据集" :image-size="90">
            <el-button v-if="editableKbs.length" type="primary" @click="dialog = true">上传第一份数据</el-button>
          </el-empty>
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="上传数据" width="560px">
      <el-form label-position="top">
        <el-form-item label="目标知识库（仅显示你有编辑权限的）">
          <el-select v-model="targetKb" placeholder="上传到知识库" style="width:100%">
            <el-option v-for="k in editableKbs" :key="k.id" :label="k.name" :value="k.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-upload drag :show-file-list="false" :http-request="upload" accept=".xlsx,.xls,.csv" :before-upload="check">
        <el-icon class="up-icon"><Upload /></el-icon>
        <div class="up-text">将文件拖到此处，或 <em>点击选择</em></div>
        <div class="up-types"><span>Excel .xlsx</span><span>Excel .xls</span><span>CSV</span></div>
      </el-upload>
      <div v-if="uploading" class="uploading"><el-icon class="is-loading"><Refresh /></el-icon>正在上传…</div>
      <el-alert type="info" :closable="false" show-icon class="tip-alert"
        title="单文件不超过 50MB、单表不超过 100 万行；上传后系统自动识别表头和类型，请确认后导入" />
    </el-dialog>

    <DatasetImportWizard v-if="wizardId" :id="wizardId" @close="wizardId = null" @done="onImported" />
    <DatasetDetail v-if="detailId" :id="detailId" @close="detailId = null" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { isPhone, isNarrow, shortTime } from '../responsive'
import { fetchMyKbs, canEdit } from '../kb'
import PageHeader from '../components/PageHeader.vue'
import StatCard from '../components/StatCard.vue'
import DatasetImportWizard from '../components/DatasetImportWizard.vue'
import DatasetDetail from '../components/DatasetDetail.vue'

const STATUS = { pending: '等待处理', parsing: '识别中', awaiting_confirm: '待确认', importing: '导入中', ready: '可分析', failed: '失败' }
const list = ref([])
const kbs = ref([])
const loading = ref(false)
const uploading = ref(false)
const dialog = ref(false)
const targetKb = ref(null)
const filterKb = ref(null)
const keyword = ref('')
const wizardId = ref(null)
const detailId = ref(null)
let timer

const editableKbs = computed(() => kbs.value.filter(canEdit))
const kbName = id => kbs.value.find(k => k.id === id)?.name || '-'
const canEditKb = id => editableKbs.value.some(k => k.id === id)
const count = st => list.value.filter(d => d.status === st).length
const totalRows = computed(() => list.value.reduce((a, d) => a + (d.status === 'ready' ? d.rowCount || 0 : 0), 0))
const shown = computed(() => list.value.filter(d => !keyword.value ||
  (d.name + d.fileName).toLowerCase().includes(keyword.value.toLowerCase())))

async function load(quiet) {
  if (!quiet) loading.value = true
  try {
    list.value = await api.get('/datasets', { params: { kbId: filterKb.value || undefined } })
  } finally {
    loading.value = false
  }
}

function check(f) {
  if (!targetKb.value) { ElMessage.error('请先选择知识库'); return false }
  const ext = f.name.split('.').pop().toLowerCase()
  if (!['xlsx', 'xls', 'csv'].includes(ext)) { ElMessage.error('仅支持 xlsx / xls / csv'); return false }
  if (f.size > 50 * 1024 * 1024) { ElMessage.error('文件不能超过 50MB'); return false }
  return true
}

async function upload({ file }) {
  uploading.value = true
  const fd = new FormData()
  fd.append('file', file)
  fd.append('kbId', targetKb.value)
  try {
    const ds = await api.post('/datasets/upload', fd)
    dialog.value = false
    ElMessage.success('上传成功，正在识别表格结构…')
    await load(true)
    waitForPlan(ds.id)
  } finally {
    uploading.value = false
  }
}

/** 识别完成后自动打开确认向导 */
async function waitForPlan(id) {
  for (let i = 0; i < 120; i++) {
    await new Promise(r => setTimeout(r, 1000))
    const d = list.value.find(x => x.id === id)
    if (d?.status === 'awaiting_confirm') { wizardId.value = id; return }
    if (d?.status === 'failed') { ElMessage.error('识别失败：' + (d.errorMsg || '')); return }
  }
}

function openRow(row) {
  if (row.status === 'ready') detailId.value = row.id
  else if (row.status === 'awaiting_confirm' && canEditKb(row.kbId)) wizardId.value = row.id
}

/** 手机"⋯"菜单 */
async function rowCommand(c, row) {
  if (c === 'view') detailId.value = row.id
  else if (c === 'confirm' || c === 'reimport') wizardId.value = row.id
  else if (c === 'delete') {
    await ElMessageBox.confirm('确定删除该数据集及其全部数据？', '删除数据集', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    remove(row.id)
  }
}

function onImported() {
  wizardId.value = null
  ElMessage.success('已开始导入，完成后即可在智能问答中分析')
  load(true)
}

async function remove(id) {
  await api.delete(`/datasets/${id}`)
  ElMessage.success('已删除')
  load(true)
}

onMounted(async () => {
  kbs.value = await fetchMyKbs()
  targetKb.value = kbs.value.filter(canEdit)[0]?.id ?? null
  await load()
  // 有处理中的数据集时自动刷新状态
  timer = setInterval(() => {
    if (list.value.some(d => ['pending', 'parsing', 'importing'].includes(d.status))) load(true)
  }, 2000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 16px; }
.filter { margin-bottom: 16px; }
.kw { width: 240px; }
.kb { width: 200px; }
.fname { display: flex; align-items: center; gap: 12px; }
.ficon { width: 38px; height: 38px; border-radius: 9px; color: #fff; font-size: 11px; font-weight: 700; display: grid; place-items: center; flex-shrink: 0; }
.fmain { min-width: 0; flex: 1; display: flex; flex-direction: column; }
.ftitle { font-weight: 600; color: var(--text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.link { cursor: pointer; }
.link:hover { color: var(--brand); }
.fid { font-size: 12px; color: var(--text-3); }
.status { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; padding: 2px 10px; border-radius: 20px; }
.status i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.status.ready { color: #16a34a; background: #dcfce7; }
.status.pending, .status.parsing, .status.importing { color: #64748b; background: #f1f5f9; }
.status.parsing i, .status.importing i { animation: pulse 1s infinite; }
.status.awaiting_confirm { color: #d97706; background: #fef3c7; }
.status.failed { color: #dc2626; background: #fee2e2; }
@keyframes pulse { 50% { opacity: .3; } }
.up-icon { font-size: 44px; color: var(--brand); margin: 8px 0; }
.up-text { color: var(--text-2); }
.up-text em { color: var(--brand); font-style: normal; }
.up-types { display: flex; gap: 6px; justify-content: center; margin-top: 12px; }
.up-types span { font-size: 12px; padding: 2px 8px; border-radius: 6px; background: #f1f2f6; color: var(--text-2); }
.uploading { margin-top: 10px; color: var(--brand); display: flex; align-items: center; gap: 6px; font-size: 13px; }
.tip-alert { margin-top: 12px; }
@media (max-width: 1100px) { .stats { grid-template-columns: repeat(2, 1fr); } }
</style>
