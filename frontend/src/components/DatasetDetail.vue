<!--
  数据集详情抽屉：表、列（中文名、类型、画像、描述、同义词），数据预览。
  列描述和同义词会提供给大模型，帮助理解"GMV=成交金额"这类口径；有编辑权限时可修改。
-->
<template>
  <el-drawer :model-value="true" size="78%" :title="detail?.dataset?.name || '数据集'" @close="emit('close')">
    <div v-loading="loading">
      <template v-if="detail">
        <el-form label-width="80px" class="ds-form">
          <el-form-item label="数据说明">
            <el-input v-model="detail.dataset.description" type="textarea" :rows="2" :disabled="!detail.canEdit"
              placeholder="这份数据是什么、统计口径（会提供给大模型）" maxlength="1000" />
          </el-form-item>
        </el-form>
        <el-alert v-if="detail.notes?.length" type="info" :closable="false" class="notes" title="表格中的口径说明（提供给大模型理解数据，不进入文档问答）">
          <div v-for="n in detail.notes" :key="n.id" class="note-line"><b>{{ n.sheetName }}</b>：{{ n.noteText }}</div>
        </el-alert>
        <el-tabs v-model="tab" @tab-change="loadPreview">
          <el-tab-pane v-for="t in detail.tables" :key="t.table.physicalName" :name="t.table.physicalName"
            :label="`${t.table.displayName}（${t.table.rowCount.toLocaleString()} 行）`">
            <div class="tbl-meta">
              <el-input v-model="t.table.displayName" :disabled="!detail.canEdit" class="tname"><template #prepend>表名</template></el-input>
              <el-input v-model="t.table.description" :disabled="!detail.canEdit" placeholder="表用途说明" class="tdesc" />
              <span class="muted">来源：{{ t.table.sheetName }}</span>
              <el-tag v-if="t.table.transform" size="small" type="warning">由交叉表转换</el-tag>
            </div>
            <el-table :data="t.columns" size="small" max-height="360">
              <el-table-column prop="physicalName" label="字段" width="70" />
              <el-table-column label="列名" min-width="160">
                <template #default="{ row }"><el-input v-model="row.displayName" size="small" :disabled="!detail.canEdit" /></template>
              </el-table-column>
              <el-table-column label="类型" width="110">
                <template #default="{ row }">{{ TYPES[row.dataType] }}<el-tag v-if="row.isPercent" size="small" class="ml">%</el-tag></template>
              </el-table-column>
              <el-table-column label="数据概况" min-width="240">
                <template #default="{ row }"><span class="muted prof">{{ profText(row) }}</span></template>
              </el-table-column>
              <el-table-column label="描述" min-width="160">
                <template #default="{ row }"><el-input v-model="row.description" size="small" :disabled="!detail.canEdit" placeholder="如：含税金额" /></template>
              </el-table-column>
              <el-table-column label="同义词" min-width="150">
                <template #default="{ row }"><el-input v-model="row.synonyms" size="small" :disabled="!detail.canEdit" placeholder="如：GMV,销售额" /></template>
              </el-table-column>
            </el-table>
            <div class="pv-title">数据预览（前 50 行）</div>
            <el-table :data="preview[t.table.physicalName]?.rows || []" size="small" border max-height="360">
              <el-table-column v-for="(c, i) in t.columns" :key="c.physicalName" :label="c.displayName" min-width="120">
                <template #default="{ row }">{{ fmt(row[i], c) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
    <template v-if="detail?.canEdit" #footer>
      <el-button @click="emit('close')">关闭</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存说明</el-button>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const props = defineProps({ id: { type: Number, required: true } })
const emit = defineEmits(['close'])
const TYPES = { VARCHAR: '文本', BIGINT: '整数', DOUBLE: '小数', DATE: '日期', TIMESTAMP: '日期时间' }
const detail = ref(null)
const loading = ref(true)
const saving = ref(false)
const tab = ref('')
const preview = ref({})

function profText(c) {
  let p = {}
  try { p = JSON.parse(c.profile || '{}') } catch { /* ignore */ }
  const parts = [`非空 ${p.non_null ?? '-'}`, `去重 ${p.distinct ?? '-'}`]
  if (p.min !== undefined && p.min !== null) parts.push(`${p.min} ~ ${p.max}`)
  if (p.top) parts.push('常见：' + p.top.slice(0, 5).map(x => x[0]).join('、'))
  return parts.join(' · ')
}
function fmt(v, c) {
  if (v === null || v === undefined) return ''
  if (c.isPercent && typeof v === 'number') return (v * 100).toFixed(2).replace(/\.?0+$/, '') + '%'
  return v
}

async function loadPreview(name) {
  if (!name || preview.value[name]) return
  preview.value[name] = await api.get(`/datasets/${props.id}/preview`, { params: { table: name, limit: 50 } })
}

async function save() {
  saving.value = true
  try {
    const d = detail.value
    await api.put(`/datasets/${props.id}/columns`, {
      description: d.dataset.description,
      tables: d.tables.map(t => ({
        id: t.table.id, displayName: t.table.displayName, description: t.table.description,
        columns: t.columns.map(c => ({ id: c.id, displayName: c.displayName, description: c.description, synonyms: c.synonyms })),
      })),
    })
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    detail.value = await api.get(`/datasets/${props.id}`)
    tab.value = detail.value.tables[0]?.table.physicalName || ''
    loadPreview(tab.value)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.ds-form { margin-bottom: 4px; }
.notes { margin-bottom: 10px; }
.note-line { white-space: pre-wrap; font-size: 12px; }
.tbl-meta { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
.tname { width: 280px; }
.tdesc { flex: 1; }
.ml { margin-left: 4px; }
.prof { font-size: 12px; }
.pv-title { font-size: 12px; color: var(--text-3); margin: 14px 0 6px; }
</style>
