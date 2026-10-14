<!--
  引用来源查看器（对话框）。
  - PDF：用 pdf.js 渲染被引用的那一页，并把引用片段所在的文字行高亮显示，可前后翻页；
  - Excel / PPT / Word / 网页 / TXT：显示被引用的文本块及其前后文，引用块高亮；
    网页导入的文档额外提供“打开原网页”链接。
  原文件通过带 token 的请求以 blob 方式获取，未登录无法访问。
-->
<template>
  <el-dialog v-model="visible" :title="title" width="960px" top="4vh" destroy-on-close @closed="cleanup">
    <div v-loading="loading" class="viewer">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

      <!-- PDF：按页渲染 -->
      <template v-if="isPdf && !error">
        <div class="toolbar">
          <el-button size="small" :disabled="page <= 1" @click="go(page - 1)">上一页</el-button>
          <span>第 {{ page }} / {{ pageCount }} 页</span>
          <el-button size="small" :disabled="page >= pageCount" @click="go(page + 1)">下一页</el-button>
          <el-tag v-if="page === source.page" size="small" type="warning">
            {{ hitCount ? '黄色高亮为引用内容' : '引用页（未能定位到具体文字）' }}
          </el-tag>
          <el-button size="small" text type="primary" @click="download">下载原文件</el-button>
        </div>
        <div ref="pageBox" class="page-box">
          <div class="page" :style="{ width: pageSize.w + 'px', height: pageSize.h + 'px' }">
            <canvas ref="canvas" />
            <div
              v-for="(r, i) in rects"
              :key="i"
              class="hl"
              :style="{ left: r.x + 'px', top: r.y + 'px', width: r.w + 'px', height: r.h + 'px' }"
            />
          </div>
        </div>
      </template>

      <!-- 其他格式：显示上下文 -->
      <template v-if="!isPdf && !error">
        <div class="toolbar">
          <span>{{ locationText }}</span>
          <a v-if="doc?.sourceUrl" :href="doc.sourceUrl" target="_blank" rel="noopener">打开原网页</a>
          <el-button v-else size="small" text type="primary" @click="download">下载原文件</el-button>
        </div>
        <div class="context">
          <div
            v-for="c in contextChunks"
            :key="c.chunk_index"
            :class="['chunk', { cited: c.chunk_index === source.chunk_index }]"
            :ref="el => { if (c.chunk_index === source.chunk_index) citedEl = el }"
          >
            <div class="chunk-meta">
              {{ chunkLabel(c) }}
              <el-tag v-if="c.chunk_index === source.chunk_index" size="small" type="warning">引用片段</el-tag>
            </div>
            <div class="md markdown" v-html="md(c.text)" />
          </div>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, nextTick } from 'vue'
import { md } from '../markdown'
import * as pdfjsLib from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import api from '../api'
import { matchItems } from '../textMatch'

// pdf.js 在 Web Worker 中解析 PDF，避免阻塞页面
pdfjsLib.GlobalWorkerOptions.workerSrc = workerUrl

const visible = ref(false)
const loading = ref(false)
const error = ref('')
const source = ref({})         // 当前查看的引用来源
const doc = ref(null)          // 来源所属文档（含 fileType、sourceUrl）
const page = ref(1)
const pageCount = ref(0)
const pageSize = ref({ w: 0, h: 0 })
const rects = ref([])          // 高亮矩形
const hitCount = ref(0)
const contextChunks = ref([])
const canvas = ref()
const pageBox = ref()
let citedEl = null
let pdfDoc = null
let blobUrl = null
let fileBlob = null

// breaks: true 让单个换行也显示为换行（Excel 每行一条记录）
const isPdf = computed(() => (doc.value?.fileType || '').toLowerCase() === 'pdf')
const title = computed(() => `原文查看 · ${source.value.file_name || ''}`)
const locationText = computed(() => {
  const s = source.value
  if (s.sheet_name) return `工作表「${s.sheet_name}」`
  if ((doc.value?.fileType || '') === 'pptx') return `第 ${s.page} 张幻灯片${s.slide_title ? '：' + s.slide_title : ''}`
  if ((doc.value?.fileType || '') === 'url') return '网页正文'
  return `第 ${s.page} 页`
})

function chunkLabel(c) {
  if (c.sheet_name) return `工作表「${c.sheet_name}」· 片段 #${c.chunk_index + 1}`
  if ((doc.value?.fileType || '') === 'pptx') return `第 ${c.page} 张幻灯片 · 片段 #${c.chunk_index + 1}`
  return `片段 #${c.chunk_index + 1}`
}

/** 对外方法：打开查看器。s 为回答中的一条来源，d 为对应文档记录 */
async function open(s, d) {
  source.value = s
  doc.value = d || { fileType: (s.file_name.split('.').pop() || '').toLowerCase() }
  error.value = ''
  rects.value = []
  contextChunks.value = []
  visible.value = true
  loading.value = true
  try {
    if (isPdf.value) {
      await loadPdf()
      await go(s.page || 1)
    } else {
      await loadContext()
    }
  } catch (e) {
    error.value = e?.response?.status === 404 ? '原文件不存在或已被删除' : '原文加载失败：' + (e.message || e)
  } finally {
    loading.value = false
  }
}

/** 带 token 获取原文件（blob），交给 pdf.js 解析 */
async function loadPdf() {
  fileBlob = await api.get(`/documents/${source.value.document_id}/file`, { responseType: 'blob' })
  const data = new Uint8Array(await fileBlob.arrayBuffer())
  pdfDoc = await pdfjsLib.getDocument({
    data,
    cMapUrl: '/pdfjs/cmaps/',            // 中文字体字符映射
    cMapPacked: true,
    standardFontDataUrl: '/pdfjs/standard_fonts/',
  }).promise
  pageCount.value = pdfDoc.numPages
}

/** 渲染第 n 页；如果是引用页，计算高亮位置 */
async function go(n) {
  page.value = Math.min(Math.max(1, n), pageCount.value)
  const pdfPage = await pdfDoc.getPage(page.value)
  // 按对话框宽度自适应缩放
  const base = pdfPage.getViewport({ scale: 1 })
  const scale = Math.min(1.6, 900 / base.width)
  const viewport = pdfPage.getViewport({ scale })
  pageSize.value = { w: viewport.width, h: viewport.height }
  await nextTick()

  // 高清屏按设备像素比放大画布，避免文字模糊
  const ratio = window.devicePixelRatio || 1
  const cv = canvas.value
  cv.width = viewport.width * ratio
  cv.height = viewport.height * ratio
  cv.style.width = viewport.width + 'px'
  cv.style.height = viewport.height + 'px'
  const ctx = cv.getContext('2d')
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
  await pdfPage.render({ canvasContext: ctx, viewport }).promise

  rects.value = page.value === source.value.page ? await highlight(pdfPage, viewport) : []
  hitCount.value = rects.value.length
  // 自动滚动到第一处高亮
  await nextTick()
  if (rects.value.length && pageBox.value) {
    pageBox.value.scrollTop = Math.max(0, rects.value[0].y - 80)
  } else if (pageBox.value) {
    pageBox.value.scrollTop = 0
  }
}

/**
 * 计算高亮矩形：用 matchItems 找出属于引用片段的文字段，逐段生成矩形。
 * pdf.js 给出的坐标是 PDF 坐标系（原点在左下角），需用 viewport.transform 转成画布坐标。
 */
async function highlight(pdfPage, viewport) {
  const content = await pdfPage.getTextContent()
  const out = []
  for (const idx of matchItems(content.items, source.value.text)) {
    const item = content.items[idx]
    const tx = pdfjsLib.Util.transform(viewport.transform, item.transform)
    const h = Math.hypot(tx[2], tx[3])          // 字号（像素）
    out.push({ x: tx[4], y: tx[5] - h, w: item.width * viewport.scale, h: h * 1.2 })
  }
  return out
}

/** 非 PDF：获取引用块前后各 2 块的内容 */
async function loadContext() {
  const s = source.value
  if (s.chunk_index === undefined || s.chunk_index === null) {
    // 老数据没有块序号，只能显示片段本身
    contextChunks.value = [{ chunk_index: -1, page: s.page, text: s.text }]
    source.value = { ...s, chunk_index: -1 }
    return
  }
  contextChunks.value = await api.get(`/documents/${s.document_id}/context`, {
    params: { chunkIndex: s.chunk_index, window: 2 },
  })
  await nextTick()
  citedEl?.scrollIntoView({ block: 'center' })
}

/** 下载原文件（复用已获取的 blob，或重新获取） */
async function download() {
  const blob = fileBlob || await api.get(`/documents/${source.value.document_id}/file`, { responseType: 'blob' })
  blobUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = blobUrl
  a.download = doc.value?.fileType === 'url' ? `${source.value.file_name}.html` : source.value.file_name
  a.click()
}

/** 关闭时释放 PDF 对象和 blob 地址，避免内存泄漏 */
function cleanup() {
  pdfDoc?.destroy()
  pdfDoc = null
  fileBlob = null
  if (blobUrl) URL.revokeObjectURL(blobUrl)
  blobUrl = null
}

defineExpose({ open })
</script>

<style scoped>
.viewer { min-height: 300px; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; padding: 8px 12px; background: #f8f9fc; border: 1px solid var(--border); border-radius: 10px; font-size: 13px; color: var(--text-2); }
.toolbar a { color: var(--brand); }
.page-box { max-height: 74vh; overflow: auto; background: #eef0f5; padding: 16px; display: flex; justify-content: center; border-radius: 10px; }
.page { position: relative; box-shadow: 0 6px 24px rgba(15, 23, 42, .18); background: #fff; border-radius: 2px; }
.page canvas { display: block; }
.hl { position: absolute; background: rgba(255, 213, 0, .38); border-bottom: 2px solid #faad14; pointer-events: none; mix-blend-mode: multiply; }
.context { max-height: 72vh; overflow: auto; padding-right: 4px; }
.chunk { border: 1px solid var(--border); border-radius: 10px; padding: 10px 14px; margin-bottom: 10px; color: var(--text-3); background: #fff; }
.chunk.cited { border-color: #fbbf24; background: #fffbeb; color: var(--text-1); box-shadow: 0 0 0 3px rgba(251, 191, 36, .15); }
.chunk-meta { font-size: 12px; color: var(--text-3); margin-bottom: 6px; display: flex; gap: 8px; align-items: center; }
.md { font-size: 13.5px; }
</style>
