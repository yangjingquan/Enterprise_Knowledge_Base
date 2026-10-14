// 知识库相关的公共方法
import api from './api'

/** 当前用户是否为系统管理员 */
export const isAdmin = () => localStorage.getItem('role') === 'admin'

/** 当前用户可见的知识库：[{id, name, visibility, myRole, docCount}] */
export const fetchMyKbs = () => api.get('/kbs')

/** 角色中文名 */
export const ROLE_NAMES = { admin: '管理员', editor: '编辑', viewer: '查看' }

/** 能否在该知识库上传 / 删除文档 */
export const canEdit = kb => kb && (kb.myRole === 'admin' || kb.myRole === 'editor')
