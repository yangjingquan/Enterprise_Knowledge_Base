<!--
  问数回答的附加信息：所用数据表（醒目展示，有歧义时可一键切换）、图表/表格切换、假设说明、SQL、导出 CSV。
  结论文字由父组件以 Markdown 渲染在上方。
-->
<template>
  <div class="da">
    <div class="da-head">
      <el-tag size="small" :type="mode === 'mixed' ? 'warning' : 'success'" effect="plain" round>
        <el-icon><Grid /></el-icon> {{ mode === 'mixed' ? '数据 + 文档' : '数据问答' }}
      </el-tag>
      <span v-if="a.table" class="da-meta">{{ a.table.rows.length }}{{ a.table.truncated ? '+' : '' }} 行 · {{ a.table.ms }} ms</span>
      <div class="da-tools">
        <el-radio-group v-if="hasChart" v-model="view" size="small">
          <el-radio-button value="chart">图表</el-radio-button>
          <el-radio-button value="table">表格</el-radio-button>
        </el-radio-group>
        <el-button v-if="historyId && a.table" size="small" icon="Download" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
      </div>
    </div>

    <!-- 使用的数据表：多个数据集都可能回答时醒目提示，并可一键改用其他数据集 -->
    <div v-if="a.dataset" :class="['da-src', { amb: alts.length }]">
      <el-icon><Coin /></el-icon>
      <span>使用数据：<b>{{ a.dataset.name }}</b><template v-if="a.tables?.length"> / <b>{{ a.tables.join('、') }}</b></template></span>
      <template v-if="alts.length">
        <span class="amb-tip">另有 {{ alts.length }} 个数据集也可能回答这个问题，结果不对可以改用：</span>
        <el-button v-for="x in alts" :key="x.dataset_id" size="small" round plain type="primary" :disabled="!switchable"
                   @click="emit('switch', x)">{{ x.dataset_name }}<template v-if="x.table"> / {{ x.table }}</template></el-button>
      </template>
    </div>
    <div v-if="a.assumptions" class="da-assume">假设：{{ a.assumptions }}</div>

    <DataChart v-if="hasChart && view === 'chart'" :table="a.table" :chart="a.chart" />
    <template v-else-if="a.table && a.table.rows.length">
      <el-table :data="rows" size="small" border max-height="320" class="da-table">
        <el-table-column v-for="(c, i) in a.table.columns" :key="i" :prop="'c' + i" :label="c" min-width="110" show-overflow-tooltip />
      </el-table>
    </template>
    <div v-if="a.table?.truncated" class="da-tip">页面最多展示 {{ a.table.rows.length }} 行，完整结果（≤1 万行）请导出 CSV</div>
    <el-collapse v-if="a.sql" class="da-sql">
      <el-collapse-item title="查看 SQL" name="sql"><pre>{{ a.sql }}</pre></el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import DataChart from './DataChart.vue'
import { buildOption } from './chartOption'

const props = defineProps({
  a: { type: Object, required: true },
  mode: { type: String, default: 'data' },
  historyId: { type: Number, default: null },
  switchable: { type: Boolean, default: true },
})
const emit = defineEmits(['switch'])
const view = ref('chart')
const exporting = ref(false)
const alts = computed(() => props.a.alternatives || [])
const hasChart = computed(() => !!(props.a.table && buildOption(props.a.table, props.a.chart)))
const rows = computed(() => (props.a.table?.rows || []).map(r => Object.fromEntries(r.map((v, i) => ['c' + i, v]))))

async function exportCsv() {
  exporting.value = true
  try {
    const res = await fetch(`/api/qa/${props.historyId}/export.csv`, { headers: { Authorization: `Bearer ${localStorage.getItem('token')}` } })
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
      throw new Error(err.error || err.message || `服务返回 ${res.status}`)
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `分析结果-${props.historyId}.csv`
    link.click()
    URL.revokeObjectURL(url)
    if (res.headers.get('X-Truncated') === 'true') ElMessage.warning('结果超过 1 万行，只导出了前 1 万行')
  } catch (e) {
    ElMessage.error(`导出失败：${e.message}`)
  } finally {
    exporting.value = false
  }
}
</script>

<style scoped>
.da { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--border); }
.da-head { display: flex; align-items: center; gap: 10px; font-size: 12px; color: var(--text-3); margin-bottom: 8px; }
.da-tools { margin-left: auto; display: flex; gap: 8px; align-items: center; }
.da-src { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; font-size: 13px; color: var(--text-1); background: var(--brand-soft, #eef2ff);
  border-radius: 8px; padding: 8px 12px; margin-bottom: 8px; }
.da-src.amb { background: #fff7ed; border: 1px solid #fed7aa; }
.amb-tip { color: #c2410c; font-size: 12px; width: 100%; margin-top: 2px; }
.da-assume { font-size: 12px; color: var(--text-2); background: #f8fafc; border-radius: 6px; padding: 6px 10px; margin-bottom: 8px; }
.da-tip { font-size: 12px; color: var(--text-3); margin-top: 4px; }
.da-sql { margin-top: 8px; border: none; }
@media (max-width: 767px) {
  .da-head { flex-wrap: wrap; }
  .da-tools { margin-left: 0; flex-wrap: wrap; }
}
.da-sql pre { margin: 0; white-space: pre-wrap; word-break: break-all; font-size: 12px; background: #0f172a; color: #e2e8f0; padding: 10px; border-radius: 6px; }
</style>
