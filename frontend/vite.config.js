import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// Element Plus 按需引入：只打包页面实际用到的组件及其样式，减小体积
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()], dts: false }),
    Components({ resolvers: [ElementPlusResolver()], dts: false }),
  ],
  build: {
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        // 拆分第三方库，利于浏览器缓存
        manualChunks: { 'vue-vendor': ['vue', 'vue-router'], pdfjs: ['pdfjs-dist'], md: ['marked', 'highlight.js/lib/core'] },
      },
    },
  },
  server: { port: 5173, proxy: { '/api': process.env.API_URL || 'http://localhost:8080' } },
})
