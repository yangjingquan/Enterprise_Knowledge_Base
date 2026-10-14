<!-- 页面标题栏：图标 + 标题 + 副标题，右侧放操作按钮 -->
<template>
  <div class="ph">
    <div class="ph-left">
      <div class="ph-icon"><el-icon><component :is="icon" /></el-icon></div>
      <div>
        <h2>{{ title }}</h2>
        <p v-if="desc">{{ desc }}</p>
      </div>
    </div>
    <div class="ph-right"><slot /></div>
  </div>
</template>
<script setup>
defineProps({ title: String, desc: String, icon: { type: String, default: 'Document' } })
</script>
<style scoped>
.ph { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
.ph-left { display: flex; align-items: center; gap: 14px; }
.ph-icon { width: 44px; height: 44px; border-radius: 12px; display: grid; place-items: center; font-size: 22px; color: #fff;
  background: linear-gradient(135deg, var(--brand), var(--brand-2)); box-shadow: 0 6px 16px rgba(79, 70, 229, .28); }
h2 { margin: 0; font-size: 20px; font-weight: 650; color: var(--text-1); }
p { margin: 4px 0 0; color: var(--text-3); font-size: 13px; }
.ph-right { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
</style>
