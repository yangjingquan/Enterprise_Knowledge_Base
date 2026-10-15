<!--
  问数结果图表：按大模型给出的 chart 建议（line / bar / pie）渲染，列名不合法或不适合画图时自动降级。
  ECharts 按需引入并异步加载，不影响首屏体积。
-->
<template>
  <div v-if="option" ref="el" class="chart" />
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { buildOption } from './chartOption'

const props = defineProps({ table: { type: Object, required: true }, chart: { type: Object, default: null } })
const el = ref()
let inst = null
let ro = null
const option = computed(() => buildOption(props.table, props.chart))

async function render() {
  if (!option.value) return
  await nextTick()
  if (!el.value) return
  const echarts = await import('./echartsCore')
  if (!inst) {
    inst = echarts.init(el.value)
    ro = new ResizeObserver(() => inst && inst.resize())
    ro.observe(el.value)
  }
  inst.setOption(option.value, true)
}
onMounted(render)
watch(option, render)
onBeforeUnmount(() => { ro?.disconnect(); inst?.dispose(); inst = null })
defineExpose({ hasChart: computed(() => !!option.value) })
</script>

<style scoped>
.chart { width: 100%; height: 300px; }
</style>
