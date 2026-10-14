<!--
  效果评测（仅管理员）：运行评测集（60 题），对比三种检索模式的命中率和问答正确率，查看 Markdown 报告。
  评测在后台运行（约 5~10 分钟），页面每 5 秒刷新一次进度。
-->
<template>
  <el-card>
    <template #header>
      <div class="hd">
        <b>效果评测</b>
        <div class="actions">
          <el-checkbox v-model="judge">大模型裁判（多消耗 API 调用）</el-checkbox>
          <el-button type="primary" :loading="status.running" @click="run">
            {{ status.running ? '评测中…' : '运行评测' }}
          </el-button>
        </div>
      </div>
    </template>
    <el-alert v-if="status.running" :title="`进度：${status.progress}`" type="info" :closable="false" show-icon />
    <el-alert v-if="status.error" :title="`评测失败：${status.error}`" type="error" :closable="false" show-icon />
    <div v-if="status.report" class="report" v-html="md(status.report)" />
    <el-empty v-else-if="!status.running" description="还没有评测报告，请先上传 demo-data 中的全部示例文档，再点击“运行评测”" />
  </el-card>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { marked } from 'marked'
import { ElMessage } from 'element-plus'
import api from '../api'

const status = ref({ running: false, report: '' })
const judge = ref(false)
const md = t => marked.parse(t || '')
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
.hd { display: flex; justify-content: space-between; align-items: center; }
.actions { display: flex; gap: 12px; align-items: center; }
.report :deep(table) { border-collapse: collapse; margin: 8px 0; font-size: 13px; }
.report :deep(td), .report :deep(th) { border: 1px solid #ddd; padding: 4px 8px; }
.report :deep(th) { background: #f5f7fa; }
</style>
