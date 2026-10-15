<!--
  交叉表预览：左边原始宽表（前几行），右边逆透视后的长表，
  用户选择"转换为长表"或"保持原样"，并可修改维度列名（如"月份"）和数值列名（如"销售额"）。
-->
<template>
  <div class="pivot">
    <div class="ph">
      <el-tag type="warning" effect="light">识别为交叉表</el-tag>
      <el-radio-group v-model="p.enabled" size="small">
        <el-radio-button :value="true">转换为长表（推荐）</el-radio-button>
        <el-radio-button :value="false">保持原样</el-radio-button>
      </el-radio-group>
      <template v-if="p.enabled">
        <el-input v-model="p.var_name" size="small" class="nm"><template #prepend>维度列名</template></el-input>
        <el-input v-if="!p.metrics.length" v-model="p.value_name" size="small" class="nm"><template #prepend>数值列名</template></el-input>
        <span v-else class="muted">指标：{{ p.metrics.join('、') }}</span>
      </template>
    </div>
    <div class="cmp">
      <div class="side">
        <div class="cap">原样（{{ columns.length }} 列）</div>
        <el-table :data="wide.slice(0, 6)" size="small" border max-height="220">
          <el-table-column v-for="(c, i) in columns" :key="c.col" :label="c.name" min-width="90">
            <template #default="{ row }">{{ row[i] }}</template>
          </el-table-column>
        </el-table>
      </div>
      <div class="arrow"><el-icon><ArrowRight /></el-icon></div>
      <div class="side" :class="{ off: !p.enabled }">
        <div class="cap">转换后（每行一个{{ p.var_name }}，前 {{ (p.preview || []).length }} 行）</div>
        <el-table :data="p.preview || []" size="small" border max-height="220">
          <el-table-column v-for="(c, i) in outCols" :key="i" :label="c" min-width="90">
            <template #default="{ row }">{{ row[i] }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <div v-if="p.drop_cols?.length" class="muted tip">"合计"列不会导入（可由求和得到）</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ block: { type: Object, required: true } })
const p = computed(() => props.block.pivot)
const columns = computed(() => props.block.columns)
const wide = computed(() => props.block.preview || [])
/** 长表列名随用户修改实时变化 */
const outCols = computed(() => {
  const by = Object.fromEntries(columns.value.map(c => [c.col, c.name]))
  return [...p.value.id_cols.map(c => by[c]), p.value.var_name,
    ...(p.value.metrics.length ? p.value.metrics : [p.value.value_name])]
})
</script>

<style scoped>
.pivot { border: 1px dashed #f59e0b; border-radius: 10px; padding: 10px; margin-top: 10px; background: #fffbeb; }
.ph { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 8px; }
.nm { width: 200px; }
.cmp { display: flex; gap: 8px; align-items: stretch; }
.side { flex: 1; min-width: 0; }
.side.off { opacity: .4; }
.cap { font-size: 12px; color: var(--text-3); margin-bottom: 4px; }
.arrow { display: grid; place-items: center; color: #f59e0b; font-size: 20px; }
.tip { font-size: 12px; margin-top: 6px; }
</style>
