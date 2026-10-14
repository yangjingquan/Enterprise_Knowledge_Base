<template>
  <!-- 全局中文语言包 -->
  <el-config-provider :locale="zhCn"><router-view /></el-config-provider>
</template>
<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
