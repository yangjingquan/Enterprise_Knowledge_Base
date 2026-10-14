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
      <div class="report markdown" v-html="md(status.report)" />
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

const status = ref({ running: false, report: '' })
const judge = ref(false)
/** 从进度文字（如“12/60”）中解析百分比 */
const percent = computed(() => {
  const m = String(status.value.progress || '').match(/(\d+)\s*\/\s*(\d+)/)
  return m ? Math.round(m[1] * 100 / m[2]) : 50
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
.report :deep(h1) { font-size: 22px; }
.report :deep(h2) { font-size: 17px; padding-left: 10px; border-left: 4px solid var(--brand); margin-top: 24px; }
</style>
