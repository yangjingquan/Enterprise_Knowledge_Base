import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from './router'
const api = axios.create({ baseURL: '/api', timeout: 120000 })
api.interceptors.request.use(c => { const t = localStorage.getItem('token'); if (t) c.headers.Authorization = `Bearer ${t}`; return c })
api.interceptors.response.use(r => r.data, e => {
  if (e.response?.status === 401 && router.currentRoute.value.path !== '/login') { localStorage.clear(); router.push('/login') }
  ElMessage.error(e.response?.data?.error || e.message); return Promise.reject(e)
})
export default api
