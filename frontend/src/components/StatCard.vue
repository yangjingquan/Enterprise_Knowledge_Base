<!-- 统计卡片：图标 + 数值 + 标签 -->
<template>
  <div class="stat panel">
    <div class="icon" :style="{ background: bg, color }"><el-icon><component :is="icon" /></el-icon></div>
    <div>
      <div class="v">{{ value }}<small v-if="unit">{{ unit }}</small></div>
      <div class="l">{{ label }}</div>
    </div>
  </div>
</template>
<script setup>
defineProps({ icon: String, value: [String, Number], unit: String, label: String,
  color: { type: String, default: '#4f46e5' }, bg: { type: String, default: '#eef2ff' } })
</script>
<style scoped>
.stat { display: flex; align-items: center; gap: 14px; padding: 18px 20px; transition: transform .2s, box-shadow .2s; }
.stat:hover { transform: translateY(-2px); box-shadow: var(--shadow); }
.icon { width: 46px; height: 46px; border-radius: 12px; display: grid; place-items: center; font-size: 22px; flex-shrink: 0; }
.v { font-size: 24px; font-weight: 700; color: var(--text-1); line-height: 1.2; }
.v small { font-size: 13px; color: var(--text-3); margin-left: 2px; font-weight: 500; }
.l { font-size: 13px; color: var(--text-3); margin-top: 2px; }
@media (max-width: 767px) {
  .stat { padding: 12px; gap: 10px; min-width: 0; }
  .stat > div:last-child { min-width: 0; }
  .icon { width: 36px; height: 36px; font-size: 18px; border-radius: 10px; }
  .v { font-size: 20px; }
  .l { font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
}
</style>
