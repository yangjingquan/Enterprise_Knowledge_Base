<!--
  效果评测（仅管理员）：运行评测集（60 题），对比三种检索模式的命中率和问答正确率，查看 Markdown 报告。
  评测在后台运行（约 5~10 分钟），页面每 5 秒刷新一次进度。
-->
<template>
  <div class="page">
    <PageHeader title="效果评测" icon="DataAnalysis" desc="用 60 道评测题对比三种检索模式的命中率和问答正确率">
      <el-checkbox v-model="judge" border>大模型裁判（多消耗 API 调用）</el-checkbox>
      <el-button type="primary" icon="Histogram" :loading="status.running" @click="run">
        {{ status.running ? '评测中…' : '运行评测' }}
      </el-button>
    </PageHeader>

    <div v-if="status.running" class="panel panel-pad running">
      <div class="run-head"><el-icon class="is-loading"><Refresh /></el-icon><b>评测进行中</b><span class="muted">约 5~10 分钟，页面每 5 秒自动刷新</span></div>
      <el-progress :percentage="percent" :stroke-width="10" striped striped-flow :format="() => status.progress" />
    </div>
    <el-alert v-if="status.error" :title="`评测失败：${status.error}`" type="error" :closable="false" show-icon class="gap" />

    <div v-if="status.report" class="panel report-wrap">
      <div class="report-head"><el-icon><Tickets /></el-icon>评测报告</div>
      <div :class="['report', 'markdown', { compact: isCompact }]" v-html="reportHtml" />
    </div>
    <div v-else-if="!status.running" class="panel panel-pad">
      <el-empty description="还没有评测报告，请先上传 demo-data 中的全部示例文档，再点击“运行评测”" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { md } from '../markdown'
import PageHeader from '../components/PageHeader.vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { isCompact } from '../responsive'

const status = ref({ running: false, report: '' })
const judge = ref(false)
/** 从进度文字（如“12/60”）中解析百分比 */
const percent = computed(() => {
  const m = String(status.value.progress || '').match(/(\d+)\s*\/\s*(\d+)/)
  return m ? Math.round(m[1] * 100 / m[2]) : 50
})
/**
 * 窄屏（手机 / 平板竖屏）把报告里的表格改成卡片：每行一张卡片，第一列作标题，
 * 其余列显示为"列名：值"，避免多列挤在一起出现一个字一行。桌面仍是原表格。
 */
function cardify(html) {
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html')
  doc.querySelectorAll('table').forEach(t => {
    const heads = [...t.querySelectorAll('thead th')].map(th => th.textContent.trim())
    const wrap = doc.createElement('div')
    wrap.className = 'md-cards'
    t.querySelectorAll('tbody tr').forEach(tr => {
      const tds = [...tr.children]
      const card = doc.createElement('div')
      card.className = 'md-card'
      const title = doc.createElement('div')
      title.className = 'md-card-title'
      title.innerHTML = `<span class="k">${heads[0] || ''}</span> ${tds[0]?.innerHTML || ''}`
      card.appendChild(title)
      const grid = doc.createElement('div')
      grid.className = 'md-card-grid' + (tds.length > 4 ? ' many' : '')
      tds.slice(1).forEach((td, i) => {
        const cell = doc.createElement('div')
        cell.innerHTML = `<span class="k">${heads[i + 1] || ''}</span><span class="v">${td.innerHTML}</span>`
        grid.appendChild(cell)
      })
      card.appendChild(grid)
      wrap.appendChild(card)
    })
    t.replaceWith(wrap)
  })
  return doc.body.firstElementChild.innerHTML
}
const reportHtml = computed(() => {
  const html = md(status.value.report || '')
  return isCompact.value ? cardify(html) : html
})
let timer

async function refresh() {
  status.value = await api.get('/admin/eval/status')
}

async function run() {
  const r = await api.post('/admin/eval/run', { judge: judge.value })
  ElMessage[r.started ? 'success' : 'warning'](r.started ? '评测已开始' : r.message)
  refresh()
}

onMounted(() => {
  refresh()
  timer = setInterval(() => status.value.running && refresh(), 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.running { margin-bottom: 16px; }
.run-head { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; color: var(--brand); }
.run-head .muted { font-size: 12px; margin-left: 8px; }
.gap { margin-bottom: 16px; }
.report-wrap { overflow: hidden; }
.report-head { padding: 14px 24px; border-bottom: 1px solid var(--border); font-weight: 600; display: flex; align-items: center; gap: 6px; background: #fafbfd; }
.report { padding: 8px 28px 24px; }
.report :deep(table) { display: table; width: 100%; }
.report.compact :deep(.md-cards) { display: grid; gap: 8px; margin: 10px 0 16px; }
.report.compact :deep(.md-card) { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; background: #fff; }
.report.compact :deep(.md-card-title) { font-weight: 600; margin-bottom: 6px; color: var(--text-1); word-break: break-word; }
.report.compact :deep(.md-card-title .k) { font-weight: 400; font-size: 12px; color: var(--text-3); margin-right: 4px; }
.report.compact :deep(.md-card-grid) { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 6px 12px; }
.report.compact :deep(.md-card-grid > div) { display: flex; flex-direction: column; min-width: 0; }
.report.compact :deep(.md-card-grid .k) { font-size: 12px; color: var(--text-3); }
.report.compact :deep(.md-card-grid .v) { font-size: 14px; color: var(--text-1); word-break: break-word; }
@media (max-width: 767px) {
  .report { padding: 4px 14px 16px; }
  .report-head { padding: 12px 14px; }
  .report :deep(h1) { font-size: 19px; }
  .report :deep(h2) { font-size: 16px; }
}
.report :deep(h1) { font-size: 22px; }
.report :deep(h2) { font-size: 17px; padding-left: 10px; border-left: 4px solid var(--brand); margin-top: 24px; }
</style>
