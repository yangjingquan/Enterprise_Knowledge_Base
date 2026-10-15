<!--
  sheet 块画布：在原始单元格网格上用不同颜色框出识别出的块
  （数据=靛蓝、备注=灰、忽略=虚线），点击块选中；在网格上拖动可框选新范围（如 A5:H120）。
-->
<template>
  <div class="canvas">
    <div class="legend">
      <span><i class="lg data" />数据块</span><span><i class="lg head" />表头</span>
      <span><i class="lg note" />备注</span><span><i class="lg ignore" />忽略</span>
      <span class="muted">拖动鼠标框选范围 → {{ selection || '未框选' }}</span>
      <el-button v-if="selection" size="small" type="primary" plain @click="emit('apply', selection)">用作当前块范围</el-button>
      <el-button v-if="selection" size="small" plain @click="emit('add', selection)">新增为数据块</el-button>
      <span class="muted right">显示前 {{ rows.length }} 行 × {{ cols.length }} 列（共 {{ sheet.rows }} 行）</span>
    </div>
    <div class="scroll" @mouseleave="dragging = false">
      <table class="grid" @mouseup="dragging = false">
        <tr><th class="corner"></th><th v-for="c in cols" :key="c">{{ letter(c) }}</th></tr>
        <tr v-for="(r, ri) in rows" :key="ri">
          <th>{{ ri + 1 }}</th>
          <td v-for="c in cols" :key="c" :class="cls(ri, c)" :title="r[c]"
            @mousedown.prevent="start(ri, c)" @mouseover="move(ri, c)" @click="pick(ri, c)">{{ r[c] }}</td>
        </tr>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { letter, parseRange, formatRange } from './rangeUtil'

const props = defineProps({ sheet: { type: Object, required: true }, active: { type: Number, default: -1 } })
const emit = defineEmits(['select-block', 'apply', 'add'])

const rows = computed(() => props.sheet.grid || [])
const cols = computed(() => [...Array(Math.min(Math.max(1, ...rows.value.map(r => r.length)), 50)).keys()])
const anchor = ref(null)
const cursor = ref(null)
const dragging = ref(false)

/** 每个单元格属于哪个块（按块顺序，先到先得） */
const owner = computed(() => {
  const m = new Map()
  props.sheet.blocks.forEach((b, bi) => {
    const g = parseRange(b.range)
    if (!g) return
    const r2 = Math.min(g[2], rows.value.length - 1)
    for (let r = g[0]; r <= r2; r++) for (let c = g[1]; c <= Math.min(g[3], 49); c++) {
      const k = r * 100 + c
      if (!m.has(k)) m.set(k, bi)
    }
  })
  return m
})

const sel = computed(() => {
  if (!anchor.value || !cursor.value) return null
  const [a, b] = [anchor.value, cursor.value]
  return [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])]
})
const selection = computed(() => sel.value && (sel.value[0] !== sel.value[2] || sel.value[1] !== sel.value[3]) ? formatRange(...sel.value) : '')

function cls(r, c) {
  const out = []
  const s = sel.value
  if (s && r >= s[0] && r <= s[2] && c >= s[1] && c <= s[3]) out.push('sel')
  const bi = owner.value.get(r * 100 + c)
  if (bi === undefined) return out
  const b = props.sheet.blocks[bi]
  const g = parseRange(b.range)
  out.push(b.kind === 'data' && (b.header_rows || []).includes(r + 1) ? 'head' : b.kind)
  if (bi === props.active) out.push('active')
  if (r === g[0]) out.push('bt')
  if (r === g[2]) out.push('bb')
  if (c === g[1]) out.push('bl')
  if (c === g[3]) out.push('br')
  return out
}

function start(r, c) { anchor.value = [r, c]; cursor.value = [r, c]; dragging.value = true }
function move(r, c) { if (dragging.value) cursor.value = [r, c] }
function pick(r, c) {
  if (selection.value) return
  const bi = owner.value.get(r * 100 + c)
  if (bi !== undefined) emit('select-block', bi)
}
</script>

<style scoped>
.canvas { border: 1px solid var(--border, #e5e7eb); border-radius: 10px; margin-bottom: 14px; }
.legend { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; padding: 6px 10px; font-size: 12px; border-bottom: 1px solid var(--border, #e5e7eb); }
.legend span { display: inline-flex; align-items: center; gap: 4px; }
.right { margin-left: auto; }
.lg { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
.lg.data { background: #eef2ff; border: 1px solid #6366f1; }
.lg.head { background: #c7d2fe; }
.lg.note { background: #f1f5f9; border: 1px solid #94a3b8; }
.lg.ignore { border: 1px dashed #94a3b8; }
.scroll { max-height: 280px; overflow: auto; user-select: none; }
.grid { border-collapse: collapse; font-size: 12px; }
.grid th { background: #f8fafc; color: var(--text-3); font-weight: 500; padding: 2px 6px; position: sticky; top: 0; border: 1px solid #eef0f4; z-index: 1; }
.grid tr th:first-child { position: sticky; left: 0; }
.grid td { border: 1px solid #eef0f4; padding: 2px 6px; max-width: 110px; min-width: 40px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: crosshair; }
.grid td.data { background: #eef2ff; }
.grid td.head { background: #c7d2fe; font-weight: 600; }
.grid td.note { background: #f1f5f9; color: #475569; }
.grid td.ignore { color: #94a3b8; }
.grid td.active.data, .grid td.active.head { background-color: #e0e7ff; }
.grid td.bt { border-top: 2px solid #6366f1; }
.grid td.bb { border-bottom: 2px solid #6366f1; }
.grid td.bl { border-left: 2px solid #6366f1; }
.grid td.br { border-right: 2px solid #6366f1; }
.grid td.note.bt, .grid td.note.bb, .grid td.note.bl, .grid td.note.br { border-color: #94a3b8; }
.grid td.ignore.bt, .grid td.ignore.bb, .grid td.ignore.bl, .grid td.ignore.br { border-color: #94a3b8; border-style: dashed; }
.grid td.active.bt, .grid td.active.bb, .grid td.active.bl, .grid td.active.br { border-color: #f59e0b; }
.grid td.sel { outline: 2px solid #f59e0b; outline-offset: -2px; background: #fef3c7 !important; }
</style>
