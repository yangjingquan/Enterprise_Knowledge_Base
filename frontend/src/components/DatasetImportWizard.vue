<!--
  数据集导入确认向导：
  1. 块画布（SheetBlockCanvas）显示每个 sheet 识别出的数据块 / 备注块 / 忽略块，可拖动框选新范围；
  2. 每个块可改名、改范围（如 A5:H120）和表头行、改类型（导入 / 备注 / 忽略）、与下一块合并；
  3. 数据块可改列名、类型、是否导入、是否剔除合计行；交叉表块显示"原样 vs 长表"对比（PivotPreview）。
  备注块的文字作为数据集口径说明提供给大模型，不进入文档问答。
-->
<template>
  <el-dialog :model-value="true" title="确认导入方案" width="1200px" top="3vh" class="wizard" @close="emit('close')">
    <div v-loading="loading" class="body">
      <template v-if="plan">
        <el-alert v-for="w in plan.warnings" :key="w" type="warning" :title="w" show-icon :closable="false" class="warn" />
        <el-tabs v-model="sheetTab">
          <el-tab-pane v-for="(s, si) in plan.sheets" :key="s.name" :name="String(si)">
            <template #label>
              <span :class="{ skipped: s.skipped }">{{ s.name }}</span>
              <el-tag v-if="s.skipped" size="small" type="info" class="ml">{{ s.skipped }}</el-tag>
              <el-tag v-else size="small" class="ml">{{ s.blocks.filter(b => b.kind === 'data').length }} 表</el-tag>
            </template>

            <div v-if="s.skipped" class="muted pad">该 sheet 不会导入</div>
            <div v-else>
              <SheetBlockCanvas :sheet="s" :active="active[si] ?? -1"
                @select-block="bi => select(si, bi)" @apply="r => applyRange(s, si, r)" @add="r => addBlock(s, si, r)" />

              <div v-for="(b, bi) in s.blocks" :key="b.id || bi" :id="`blk-${si}-${bi}`" class="block"
                :class="[b.kind, { active: active[si] === bi }]" @click="active[si] = bi">
                <div class="block-head">
                  <el-tag size="small" :type="KIND_TAG[b.kind]">{{ KINDS[b.kind] }}</el-tag>
                  <el-input v-if="b.kind === 'data'" v-model="b.name" class="bname" placeholder="表名"><template #prepend>表名</template></el-input>
                  <el-input v-model="b.range" class="brange" placeholder="A5:H120"><template #prepend>范围</template></el-input>
                  <el-input v-if="b.kind === 'data'" v-model="b.headerText" class="bheader" placeholder="如 4 或 3,4"><template #prepend>表头行</template></el-input>
                  <el-button size="small" icon="Refresh" :loading="b.refreshing" @click.stop="refresh(s, b)">重新识别</el-button>
                  <el-radio-group :model-value="b.kind" size="small" @change="k => setKind(s, b, k)">
                    <el-radio-button value="data">导入</el-radio-button>
                    <el-radio-button value="note">备注</el-radio-button>
                    <el-radio-button value="ignore">忽略</el-radio-button>
                  </el-radio-group>
                  <el-button v-if="bi < s.blocks.length - 1" size="small" link @click.stop="mergeNext(s, si, bi)">与下一块合并</el-button>
                  <el-switch v-if="b.kind === 'data' && !b.pivot?.enabled" v-model="b.drop_totals" size="small" active-text="剔除合计行" />
                </div>

                <div v-if="b.kind === 'note'" class="note">
                  <el-input v-model="b.note_text" type="textarea" :rows="3" maxlength="1000"
                    placeholder="备注文字会作为数据口径说明提供给大模型" />
                </div>
                <template v-else-if="b.kind === 'data' && b.columns">
                  <div class="block-info muted">
                    约 {{ (b.est_rows ?? 0).toLocaleString() }} 行
                    <span v-if="b.totals_found">· 发现 {{ b.totals_found }} 行合计/小计{{ b.drop_totals ? '（将剔除）' : '' }}</span>
                  </div>
                  <el-collapse :model-value="b.pivot?.enabled ? [] : ['cols']">
                    <el-collapse-item name="cols" :title="`列设置（${b.columns.filter(c => c.include).length}/${b.columns.length} 列导入）`">
                      <el-table :data="b.columns" size="small" max-height="280" class="cols">
                        <el-table-column label="导入" width="60" align="center">
                          <template #default="{ row }"><el-checkbox v-model="row.include" /></template>
                        </el-table-column>
                        <el-table-column prop="letter" label="列" width="50" />
                        <el-table-column label="列名" min-width="200">
                          <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
                        </el-table-column>
                        <el-table-column label="类型" width="150">
                          <template #default="{ row }">
                            <el-select v-model="row.type" size="small">
                              <el-option v-for="(t, k) in TYPES" :key="k" :label="t" :value="k" />
                            </el-select>
                          </template>
                        </el-table-column>
                        <el-table-column label="识别说明" min-width="200">
                          <template #default="{ row }">
                            <el-tag v-if="row.is_percent" size="small" type="success">百分比→小数</el-tag>
                            <el-tag v-if="row.missing_year" size="small" type="warning">日期缺年份</el-tag>
                            <el-tag v-if="row.bad" size="small" type="danger">{{ row.bad }} 个值无法解析</el-tag>
                            <el-tag v-if="row.type !== row.auto_type" size="small" type="info">已手动修改</el-tag>
                          </template>
                        </el-table-column>
                      </el-table>
                    </el-collapse-item>
                  </el-collapse>
                  <PivotPreview v-if="b.pivot" :block="b" />
                  <template v-if="!b.pivot?.enabled">
                    <div class="pv-title">转换后预览（前 {{ b.preview.length }} 行）</div>
                    <el-table :data="b.preview" size="small" max-height="240" border class="pv">
                      <el-table-column v-for="(c, i) in b.columns" :key="c.col" :label="c.name" min-width="110"
                        :class-name="c.include ? '' : 'excluded'">
                        <template #default="{ row }">{{ fmt(row[i], c) }}</template>
                      </el-table-column>
                    </el-table>
                  </template>
                </template>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
    <template #footer>
      <span class="muted foot">共 {{ dataBlocks }} 张表将被导入，{{ noteBlocks }} 段备注作为口径说明</span>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!dataBlocks" @click="confirm">确认导入</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import SheetBlockCanvas from './SheetBlockCanvas.vue'
import PivotPreview from './PivotPreview.vue'
import { parseRange, unionRange } from './rangeUtil'

const props = defineProps({ id: { type: Number, required: true } })
const emit = defineEmits(['close', 'done'])
const TYPES = { VARCHAR: '文本', BIGINT: '整数', DOUBLE: '小数', DATE: '日期', TIMESTAMP: '日期时间' }
const KINDS = { data: '数据块', note: '备注', ignore: '忽略' }
const KIND_TAG = { data: 'primary', note: 'info', ignore: 'info' }

const plan = ref(null)
const loading = ref(true)
const saving = ref(false)
const sheetTab = ref('0')
const active = reactive({})

const all = () => (plan.value?.sheets || []).flatMap(s => s.blocks)
const dataBlocks = computed(() => all().filter(b => b.kind === 'data').length)
const noteBlocks = computed(() => all().filter(b => b.kind === 'note').length)

function fmt(v, c) {
  if (v === null || v === undefined) return ''
  if (c.is_percent && typeof v === 'number') return (v * 100).toFixed(2).replace(/\.?0+$/, '') + '%'
  return v
}

function decorate(b) {
  b.headerText = (b.header_rows || []).join(',')
  b.refreshing = false
  if (b.drop_totals === undefined) b.drop_totals = true
  return b
}

function select(si, bi) {
  active[si] = bi
  nextTick(() => document.getElementById(`blk-${si}-${bi}`)?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }))
}

async function refresh(s, b) {
  if (!parseRange(b.range)) { ElMessage.error('范围格式应为 A5:H120'); return }
  if (b.kind === 'note') return
  const txt = (b.headerText || '').trim()
  const hr = txt === '' ? null : txt.split(/[,，\s]+/).filter(Boolean).map(Number)
  if (hr && hr.some(isNaN)) { ElMessage.error('表头行应为数字，如 4 或 3,4'); return }
  b.refreshing = true
  try {
    const { headerText, refreshing, ...block } = b
    block.kind = 'data'
    const nb = await api.post(`/datasets/${props.id}/plan/block`, { sheet: s.name, range: b.range, header_rows: hr, block })
    Object.assign(b, decorate({ ...nb, name: b.name || nb.name || s.name, kind: 'data', manual: true }))
  } finally {
    b.refreshing = false
  }
}

async function setKind(s, b, k) {
  b.kind = k
  if (k === 'data' && !b.columns) await refresh(s, b)
  if (k === 'data' && !b.name) b.name = s.name
}

async function applyRange(s, si, r) {
  const bi = active[si]
  if (bi === undefined || bi < 0) { ElMessage.info('请先点击选中一个块，或选择"新增为数据块"'); return }
  const b = s.blocks[bi]
  b.range = r
  b.headerText = ''
  await refresh(s, b)
}

async function addBlock(s, si, r) {
  const b = decorate({ id: `m${Date.now()}`, sheet: s.name, range: r, kind: 'data', name: `${s.name}_块${s.blocks.length + 1}`, manual: true })
  s.blocks.push(b)
  active[si] = s.blocks.length - 1
  await refresh(s, b)
}

async function mergeNext(s, si, bi) {
  const a = s.blocks[bi], b = s.blocks[bi + 1]
  a.range = unionRange(a.range, b.range)
  s.blocks.splice(bi + 1, 1)
  if (a.kind === 'note') {
    a.note_text = [a.note_text, b.note_text].filter(Boolean).join('\n')
  } else {
    a.kind = 'data'
    a.headerText = ''
    await refresh(s, a)
  }
  active[si] = bi
}

async function confirm() {
  saving.value = true
  try {
    const clean = JSON.parse(JSON.stringify(plan.value))
    for (const s of clean.sheets) {
      for (const b of s.blocks) { delete b.headerText; delete b.refreshing }
      // 备注随用户修改重新汇总，作为该 sheet 下各表的口径说明
      s.notes = s.blocks.filter(b => b.kind === 'note').map(b => b.note_text || '').join('\n').slice(0, 1000)
    }
    await api.put(`/datasets/${props.id}/plan`, clean)
    emit('done')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    const p = await api.get(`/datasets/${props.id}/plan`)
    p.sheets.forEach(s => s.blocks.forEach(decorate))
    plan.value = p
    sheetTab.value = String(Math.max(0, p.sheets.findIndex(s => !s.skipped)))
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.body { min-height: 300px; }
.warn { margin-bottom: 8px; }
.ml { margin-left: 6px; }
.skipped { color: var(--text-3); }
.pad { padding: 24px; }
.block { border: 1px solid #c7d2fe; border-left: 4px solid var(--brand); border-radius: 10px; padding: 10px 12px; margin-bottom: 12px; }
.block.active { box-shadow: 0 0 0 2px #fcd34d; }
.block.note { border-color: #cbd5e1; border-left-color: #94a3b8; background: #f8fafc; }
.block.ignore { border: 1px dashed #cbd5e1; border-left: 4px dashed #cbd5e1; opacity: .75; }
.block-head { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.bname { width: 280px; }
.brange { width: 170px; }
.bheader { width: 160px; }
.block-info { margin: 6px 0; font-size: 12px; }
.note { margin-top: 8px; }
.pv-title { font-size: 12px; color: var(--text-3); margin: 10px 0 6px; }
.foot { margin-right: 12px; font-size: 13px; }
:deep(.excluded) { opacity: .35; }
@media (max-width: 767px) {
  .block { padding: 10px 8px; }
  .bname, .brange, .bheader { width: 100%; }
  .foot { display: block; margin: 0 0 8px; }
}
</style>
