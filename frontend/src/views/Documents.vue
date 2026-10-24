<!--
  文档管理页面：上传文件、导入网页、查看处理状态、删除。
  支持 PDF / Word / Excel / PPT / TXT / Markdown，以及通过网址导入网页正文。
-->
<template>
  <div class="page">
    <PageHeader title="文档管理" icon="Document" desc="上传企业文档，系统自动解析、切分、向量化，供智能问答检索">
      <el-button icon="Refresh" @click="load()">刷新</el-button>
      <el-button v-if="editableKbs.length" type="primary" icon="Upload" @click="dialog = true">上传 / 导入</el-button>
    </PageHeader>

    <!-- 统计 -->
    <div class="stats">
      <StatCard icon="Files" :value="docs.length" label="文档总数" />
      <StatCard icon="CircleCheck" :value="count('ready')" label="已就绪" color="#16a34a" bg="#dcfce7" />
      <StatCard icon="Clock" :value="count('pending') + count('processing')" label="处理中" color="#d97706" bg="#fef3c7" />
      <StatCard icon="Tickets" :value="totalChunks" label="切片总数" color="#0284c7" bg="#e0f2fe" />
    </div>

    <div class="panel panel-pad">
      <div class="filter toolbar-row">
        <el-input v-model="keyword" placeholder="搜索文件名" prefix-icon="Search" clearable class="kw" />
        <!-- 按知识库筛选列表 -->
        <el-select v-model="filterKb" class="kb" placeholder="全部知识库" clearable @change="load()">
          <template #prefix><el-icon><Collection /></el-icon></template>
          <el-option v-for="k in kbs" :key="k.id" :label="k.name" :value="k.id" />
        </el-select>
        <el-radio-group v-model="filterStatus">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="ready">就绪</el-radio-button>
          <el-radio-button value="processing">处理中</el-radio-button>
          <el-radio-button value="failed">失败</el-radio-button>
        </el-radio-group>
        <span v-if="!editableKbs.length" class="readonly"><el-icon><Lock /></el-icon>你对当前可见的知识库只有查看权限，如需上传请联系管理员授权</span>
      </div>

      <el-table :data="shown" v-loading="loading" class="tbl">
        <el-table-column label="文件名 / 网页标题" :min-width="isNarrow ? 180 : 300">
          <template #default="{ row }">
            <div class="fname">
              <span class="ficon" :style="{ background: TYPE_COLORS[row.fileType] || '#64748b' }">{{ SHORT[row.fileType] || (row.fileType || '').toUpperCase().slice(0, 4) }}</span>
              <div class="fmain">
                <div class="ftitle" :title="row.fileName">{{ row.fileName }}</div>
                <a v-if="row.sourceUrl" :href="row.sourceUrl" target="_blank" rel="noopener" class="src-url">{{ row.sourceUrl }}</a>
                <span v-else-if="!isNarrow" class="fid">ID {{ row.id }}</span>
                <!-- 手机 / 平板：次要列合并为一行摘要 -->
                <div v-if="isNarrow" class="meta-line">
                  <span v-if="isPhone">{{ kbName(row.kbId) }}</span>
                  <span :class="['status', row.status]"><i />{{ tag[row.status]?.[1] }}</span>
                  <span>{{ row.chunkCount ?? 0 }} 切片</span>
                  <span v-if="row.status === 'failed' && row.errorMsg" class="err-line" data-test="error-msg">{{ row.errorMsg }}</span>
                  <span>{{ shortTime(row.createTime) }}</span>
                </div>
              </div>
              <!-- 扫描件经 OCR 识别 -->
              <el-tooltip v-if="row.ocrPages > 0" :content="`${row.ocrPages} 页为扫描件，已通过 OCR 识别文字`">
                <el-tag size="small" type="warning" effect="light" round>OCR</el-tag>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="!isPhone" label="知识库" width="170">
          <template #default="{ row }">
            <el-select v-if="canEditKb(row.kbId) && editableKbs.length > 1" :model-value="row.kbId" size="small" @change="v => move(row, v)">
              <el-option v-for="k in editableKbs" :key="k.id" :label="k.name" :value="k.id" />
            </el-select>
            <el-tag v-else effect="plain" type="info">{{ kbName(row.kbId) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" label="状态" width="110">
          <template #default="{ row }">
            <!-- 失败时悬停查看原因 -->
            <el-tooltip v-if="row.status === 'failed'" :content="row.errorMsg || '处理失败（无详细原因）'" placement="top">
              <span :class="['status', row.status]" data-test="failed-status"><i />{{ tag[row.status]?.[1] }}</span>
            </el-tooltip>
            <span v-else :class="['status', row.status]"><i />{{ tag[row.status]?.[1] }}</span>
            <div v-if="row.status === 'failed' && row.errorMsg" class="err-line" data-test="error-msg">{{ row.errorMsg }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" prop="chunkCount" label="切片数" width="90" align="center" />
        <el-table-column v-if="!isNarrow" label="上传时间" width="170">
          <template #default="{ row }"><span class="muted">{{ (row.createTime || '').replace('T', ' ') }}</span></template>
        </el-table-column>
        <el-table-column :label="isPhone ? '' : '操作'" :width="isPhone ? 84 : 140" fixed="right">
          <template #default="{ row }">
            <!-- 处理失败：用已保存的原文件重新解析 -->
            <el-button v-if="row.status === 'failed' && canEditKb(row.kbId)" link type="primary" icon="Refresh"
                       :aria-label="'重试'" data-test="retry" @click="retry(row)">{{ isPhone ? '' : '重试' }}</el-button>
            <el-popconfirm v-if="canEditKb(row.kbId)" title="确定删除该文档及其向量数据？" width="240" @confirm="remove(row.id)">
              <template #reference>
                <el-button link type="danger" icon="Delete" :aria-label="'删除'">{{ isPhone ? '' : '删除' }}</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无文档" :image-size="90">
            <el-button v-if="editableKbs.length" type="primary" @click="dialog = true">上传第一份文档</el-button>
          </el-empty>
        </template>
      </el-table>
    </div>

    <!-- 上传 / 导入 对话框 -->
    <el-dialog v-model="dialog" title="上传文档 / 导入网页" width="620px">
      <el-form label-position="top">
        <el-form-item label="目标知识库（仅显示你有编辑权限的）">
          <el-select v-model="targetKb" placeholder="上传到知识库" style="width:100%">
            <el-option v-for="k in editableKbs" :key="k.id" :label="k.name" :value="k.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-tabs v-model="tab">
        <el-tab-pane label="上传文件" name="file">
          <el-upload drag :show-file-list="false" :http-request="upload" :accept="ACCEPT" :before-upload="check" multiple>
            <el-icon class="up-icon"><Upload /></el-icon>
            <div class="up-text">将文件拖到此处，或 <em>点击选择</em></div>
            <div class="up-types">
              <span v-for="e in ['PDF', 'Word', 'Excel', 'PPT', 'TXT', 'Markdown']" :key="e">{{ e }}</span>
            </div>
          </el-upload>
          <div v-if="uploading > 0" class="uploading"><el-icon class="is-loading"><Refresh /></el-icon>正在上传 {{ uploading }} 个文件…</div>
        </el-tab-pane>
        <el-tab-pane label="导入网页" name="url">
          <div class="url-row">
            <el-input v-model="url" clearable size="large" placeholder="https://..." prefix-icon="Link" @keydown.enter.prevent="importUrl" />
            <el-button type="primary" size="large" :loading="importing" @click="importUrl">导入</el-button>
          </div>
          <el-alert v-if="urlError" type="error" :title="urlError" show-icon :closable="false" class="url-error" />
          <p class="tip">抓取网页正文入库；需要登录才能访问的网页无法导入。</p>
        </el-tab-pane>
      </el-tabs>
      <el-alert type="info" :closable="false" show-icon class="tip-alert"
        title="PDF 扫描件自动 OCR；单文件不超过 50MB；Excel 按工作表、PPT 按幻灯片标注出处" />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { isPhone, isNarrow, shortTime } from '../responsive'
import { fetchMyKbs, canEdit } from '../kb'
import PageHeader from '../components/PageHeader.vue'
import StatCard from '../components/StatCard.vue'

/** 允许上传的扩展名（与后端白名单一致） */
const EXTS = ['pdf', 'docx', 'xlsx', 'pptx', 'txt', 'md']
const ACCEPT = EXTS.map(e => '.' + e).join(',')
const TYPE_NAMES = { pdf: 'PDF', docx: 'Word', xlsx: 'Excel', pptx: 'PPT', txt: 'TXT', md: 'MD', url: '网页' }
const SHORT = { pdf: 'PDF', docx: 'DOC', xlsx: 'XLS', pptx: 'PPT', txt: 'TXT', md: 'MD', url: 'WEB' }
const TYPE_COLORS = { pdf: '#ef4444', docx: '#2563eb', xlsx: '#16a34a', pptx: '#ea580c', txt: '#64748b', md: '#0f172a', url: '#7c3aed' }

const docs = ref([])
const loading = ref(false)
const uploading = ref(0)      // 正在上传的文件数（支持多选）
const importing = ref(false)
const url = ref('')
const urlError = ref('')   // 网页导入失败原因（显示在弹窗内）
const kbs = ref([])
const filterKb = ref(null)
const targetKb = ref(null)
let timer
const dialog = ref(false)
const tab = ref('file')
const keyword = ref('')
const filterStatus = ref('')
const count = st => docs.value.filter(d => d.status === st).length
const totalChunks = computed(() => docs.value.reduce((a, d) => a + (d.chunkCount || 0), 0))
/** 前端筛选：文件名关键字 + 状态（处理中包含等待处理） */
const shown = computed(() => docs.value.filter(d =>
  (!keyword.value || (d.fileName || '').toLowerCase().includes(keyword.value.toLowerCase())) &&
  (!filterStatus.value || d.status === filterStatus.value || (filterStatus.value === 'processing' && d.status === 'pending'))))

const editableKbs = computed(() => kbs.value.filter(canEdit))
const kbName = id => kbs.value.find(k => k.id === id)?.name || '-'
const canEditKb = id => editableKbs.value.some(k => k.id === id)

const tag = {
  pending: ['info', '等待处理'],
  processing: ['warning', '处理中'],
  ready: ['success', '就绪'],
  failed: ['danger', '失败'],
}

async function load(quiet) {
  if (!quiet) loading.value = true
  try {
    docs.value = (await api.get('/documents', { params: { kbId: filterKb.value || undefined } })).reverse()
  } finally {
    loading.value = false
  }
}

/** 上传前校验类型和大小 */
function check(f) {
  if (!targetKb.value) {
    ElMessage.error('请先选择要上传到的知识库')
    return false
  }
  const ext = f.name.split('.').pop().toLowerCase()
  if (!EXTS.includes(ext)) {
    ElMessage.error(`不支持 .${ext} 文件`)
    return false
  }
  if (f.size > 50 * 1024 * 1024) {
    ElMessage.error('文件不能超过 50MB')
    return false
  }
  return true
}

let uploadFailed = false    // 本批（可多选）上传中是否有失败的文件

async function upload({ file }) {
  if (uploading.value === 0) uploadFailed = false
  uploading.value++
  const fd = new FormData()
  fd.append('file', file)
  fd.append('kbId', targetKb.value)
  try {
    await api.post('/documents/upload', fd)
    ElMessage.success(`${file.name} 上传成功，正在后台处理`)
  } catch (e) {
    uploadFailed = true   // 错误信息已由 api 拦截器弹出，弹窗保持打开便于重试
  } finally {
    uploading.value--
    if (uploading.value === 0) {
      load()
      // 整批都成功才自动关闭弹窗；有失败时保持打开
      if (!uploadFailed) closeDialog()
    }
  }
}

/** 关闭上传弹窗并重置表单 */
function closeDialog() {
  dialog.value = false
  url.value = ''
  urlError.value = ''
  tab.value = 'file'
}

/** 网页导入：后端会先下载网页，打不开会立即提示原因 */
async function importUrl() {
  const u = url.value.trim()
  if (!/^https?:\/\//i.test(u)) {
    ElMessage.warning('请输入以 http:// 或 https:// 开头的网址')
    return
  }
  if (!targetKb.value) {
    ElMessage.warning('请先选择要导入到的知识库')
    return
  }
  importing.value = true
  urlError.value = ''
  try {
    const d = await api.post('/documents/url', { url: u, kbId: targetKb.value }, { timeout: 90000 })
    ElMessage.success(`已导入「${d.title}」，正在后台处理`)
    closeDialog()
    load()
  } catch (e) {
    // 失败时弹窗保持打开，并在输入框下方显示原因
    urlError.value = e.response?.data?.error
      || (e.code === 'ECONNABORTED' ? '导入超时：网页下载太慢，请稍后重试' : '导入失败，请检查网址后重试')
  } finally {
    importing.value = false
  }
}

/** 移动文档到其他知识库 */
async function move(row, kbId) {
  await api.put(`/documents/${row.id}/kb`, { kbId })
  ElMessage.success(`已移动到「${kbName(kbId)}」`)
  load(true)
}

async function remove(id) {
  const r = await api.delete(`/documents/${id}`)
  if (r?.cleanupPending) ElMessage.warning('已删除，向量数据稍后由后台自动清理')
  else ElMessage.success('已删除')
  load()
}

/** 重新处理失败的文档（使用服务器上保存的原文件） */
async function retry(row) {
  await api.post(`/documents/${row.id}/retry`)
  ElMessage.success('已重新开始处理')
  load(true)
}

onMounted(async () => {
  kbs.value = await fetchMyKbs()
  targetKb.value = editableKbs.value[0]?.id ?? null
  load()
  // 有文档在处理中时，每 3 秒刷新一次状态
  timer = setInterval(() => {
    if (docs.value.some(d => ['pending', 'processing'].includes(d.status))) load(true)
  }, 3000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 16px; }
.filter { margin-bottom: 16px; }
.kw { width: 240px; }
.kb { width: 200px; }
.readonly { color: var(--text-3); font-size: 12px; display: flex; align-items: center; gap: 4px; margin-left: auto; }
.fname { display: flex; align-items: center; gap: 12px; }
.ficon { width: 38px; height: 38px; border-radius: 9px; color: #fff; font-size: 11px; font-weight: 700; display: grid; place-items: center; flex-shrink: 0; }
.fmain { min-width: 0; flex: 1; }
.ftitle { font-weight: 600; color: var(--text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fid { font-size: 12px; color: var(--text-3); }
.src-url { display: block; font-size: 12px; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; padding: 2px 10px; border-radius: 20px; }
.status i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.status.ready { color: #16a34a; background: #dcfce7; }
.status.pending { color: #64748b; background: #f1f5f9; }
.status.processing { color: #d97706; background: #fef3c7; }
.status.processing i { animation: pulse 1s infinite; }
.status.failed { color: #dc2626; background: #fee2e2; cursor: help; }
.err-line { margin-top: 4px; font-size: 12px; color: #dc2626; line-height: 1.4; word-break: break-all;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
@keyframes pulse { 50% { opacity: .3; } }
.up-icon { font-size: 44px; color: var(--brand); margin: 8px 0; }
.up-text { color: var(--text-2); }
.up-text em { color: var(--brand); font-style: normal; }
.up-types { display: flex; gap: 6px; justify-content: center; margin-top: 12px; }
.up-types span { font-size: 12px; padding: 2px 8px; border-radius: 6px; background: #f1f2f6; color: var(--text-2); }
.uploading { margin-top: 10px; color: var(--brand); display: flex; align-items: center; gap: 6px; font-size: 13px; }
.url-row { display: flex; gap: 10px; }
.url-error { margin-top: 10px; }
.tip { color: var(--text-3); font-size: 12px; }
.tip-alert { margin-top: 12px; }
@media (max-width: 1100px) { .stats { grid-template-columns: repeat(2, 1fr); } }
</style>