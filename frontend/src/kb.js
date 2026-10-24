// 知识库 / 角色相关的公共方法
import api from './api'

/** 超级管理员 */
export const isSuperAdmin = () => localStorage.getItem('role') === 'admin'
/** @deprecated 同 isSuperAdmin */
export const isAdmin = isSuperAdmin

/** 客户管理员 */
export const isBrokerAdmin = () => localStorage.getItem('role') === 'broker_admin'

/** 可进入系统管理（客户管理员：用户/知识库/评测） */
export const canManageBroker = () => isBrokerAdmin()

/** 当前用户可见的知识库 */
export const fetchMyKbs = () => api.get('/kbs')

export const ROLE_NAMES = { admin: '管理员', editor: '编辑', viewer: '查看', broker_admin: '客户管理员' }

export const canEdit = kb => kb && (kb.myRole === 'admin' || kb.myRole === 'editor')
