import { createApp } from 'vue'
// 命令式组件（ElMessage / ElMessageBox / v-loading）的样式需手动引入，其余组件由插件按需引入
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
import { ElLoading } from 'element-plus'
import './styles/theme.css'
import { icons } from './icons'
import App from './App.vue'
import router from './router'

const app = createApp(App)
// 只注册用到的图标，避免把整个图标库打进包里
for (const [k, c] of Object.entries(icons)) app.component(k, c)
app.use(ElLoading).use(router).mount('#app')
