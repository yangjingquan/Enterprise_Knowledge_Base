<!--
  文档管理页面：上传文件、导入网页、查看处理状态、删除。
  支持 PDF / Word / Excel / PPT / TXT / Markdown，以及通过网址导入网页正文。
-->
<template>
  <el-card>
    <template #header>
      <div class="hd">
        <div class="left">
          <b>文档管理</b>
          <!-- 按知识库筛选列表 -->
          <el-select v-model="filterKb" size="small" class="kb" placeholder="全部知识库" clearable @change="load()">
            <el-option v-for="k in kbs" :key="k.id" :label="k.name" :value="k.id" />
          </el-select>
        </div>
        <div v-if="editableKbs.length" class="actions">
          <!-- 上传 / 导入的目标知识库：只能选有编辑权限的 -->
          <el-select v-model="targetKb" class="kb" placeholder="上传到知识库">
            <el-option v-for="k in editableKbs" :key="k.id" :label="`上传到：${k.name}`" :value="k.id" />
          </el-select>
          <el-input
            v-model="url"
            class="url"
            clearable
            placeholder="输入网页地址导入，例如 https://..."
            @keydown.enter.prevent="importUrl"
          />
          <el-button icon="Link" :loading="importing" @click="importUrl">导入网页</el-button>
          <el-upload :show-file-list="false" :http-request="upload" :accept="ACCEPT" :before-upload="check" multiple>
            <el-button type="primary" icon="Upload" :loading="uploading > 0">上传文档</el-button>
          </el-upload>
        </div>
        <span v-else class="readonly">你对当前可见的知识库只有查看权限，如需上传请联系管理员授权</span>
      </div>
    </template>

    <el-table :data="docs" v-loading="loading">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="文件名 / 网页标题">
        <template #default="{ row }">
          {{ row.fileName }}
          <a v-if="row.sourceUrl" :href="row.sourceUrl" target="_blank" rel="noopener" class="src-url">{{ row.sourceUrl }}</a>
        </template>
      </el-table-column>
      <el-table-column label="知识库" width="150">
        <template #default="{ row }">
          <el-select
            v-if="canEditKb(row.kbId) && editableKbs.length > 1"
            :model-value="row.kbId"
            size="small"
            @change="v => move(row, v)"
          >
            <el-option v-for="k in editableKbs" :key="k.id" :label="k.name" :value="k.id" />
          </el-select>
          <span v-else>{{ kbName(row.kbId) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ TYPE_NAMES[row.fileType] || row.fileType }}</el-tag>
          <!-- 扫描件经 OCR 识别 -->
          <el-tooltip v-if="row.ocrPages > 0" :content="`${row.ocrPages} 页为扫描件，已通过 OCR 识别文字`">
            <el-tag size="small" type="warning" style="margin-left:4px">OCR</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="tag[row.status]?.[0]">{{ tag[row.status]?.[1] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="切片数" width="90" />
      <el-table-column prop="createTime" label="上传时间" width="180" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-popconfirm
            v-if="canEditKb(row.kbId)" title="确定删除该文档及其向量数据？" @confirm="remove(row.id)">
            <template #reference>
              <el-button link type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
    <p class="tip">
      支持 PDF（含扫描件，自动 OCR）/ Word / Excel / PPT / TXT / Markdown，单文件不超过 20MB；也可以输入网址导入网页正文（需登录才能访问的网页无法导入）。
      上传后自动解析、切分、向量化。Excel 按工作表、PPT 按幻灯片标注出处。
    </p>
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { fetchMyKbs, canEdit } from '../kb'

/** 允许上传的扩展名（与后端白名单一致） */
const EXTS = ['pdf', 'docx', 'xlsx', 'pptx', 'txt', 'md']
const ACCEPT = EXTS.map(e => '.' + e).join(',')
const TYPE_NAMES = { pdf: 'PDF', docx: 'Word', xlsx: 'Excel', pptx: 'PPT', txt: 'TXT', md: 'Markdown', url: '网页' }

const docs = ref([])
const loading = ref(false)
const uploading = ref(0)      // 正在上传的文件数（支持多选）
const importing = ref(false)
const url = ref('')
const kbs = ref([])
const filterKb = ref(null)
const targetKb = ref(null)
let timer

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
  if (f.size > 20 * 1024 * 1024) {
    ElMessage.error('文件不能超过 20MB')
    return false
  }
  return true
}

async function upload({ file }) {
  uploading.value++
  const fd = new FormData()
  fd.append('file', file)
  fd.append('kbId', targetKb.value)
  try {
    await api.post('/documents/upload', fd)
    ElMessage.success(`${file.name} 上传成功，正在后台处理`)
    load()
  } finally {
    uploading.value--
  }
}

/** 网页导入：后端会先下载网页，打不开会立即提示原因 */
async function importUrl() {
  const u = url.value.trim()
  if (!/^https?:\/\//i.test(u)) {
    ElMessage.warning('请输入以 http:// 或 https:// 开头的网址')
    return
  }
  importing.value = true
  try {
    const d = await api.post('/documents/url', { url: u, kbId: targetKb.value }, { timeout: 60000 })
    ElMessage.success(`已导入「${d.title}」，正在后台处理`)
    url.value = ''
    load()
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
  await api.delete(`/documents/${id}`)
  ElMessage.success('已删除')
  load()
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
.hd { display: flex; justify-content: space-between; align-items: center; }
.left { display: flex; gap: 12px; align-items: center; }
.kb { width: 180px; }
.readonly { color: #999; font-size: 12px; }
.actions { display: flex; gap: 10px; align-items: center; }
.url { width: 340px; }
.src-url { display: block; font-size: 12px; color: #909399; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tip { color: #999; font-size: 12px; }
</style>
