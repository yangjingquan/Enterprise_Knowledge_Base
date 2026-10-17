// 响应式断点：phone < 768 ≤ pad < 1200 ≤ desktop（与 styles/theme.css 中的 @media 保持一致）
// 横屏手机（宽度可能 > 768，但高度很矮）也按手机处理。
import { ref, computed } from 'vue'

export const PHONE_MAX = 767
export const PAD_MAX = 1199
export const COMPACT_MAX = 1023

const width = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)
const height = ref(typeof window === 'undefined' ? 900 : window.innerHeight)
if (typeof window !== 'undefined') {
  window.addEventListener('resize', () => { width.value = window.innerWidth; height.value = window.innerHeight }, { passive: true })
}

/** 触屏设备（手机、平板）：用于切换"点两下框选"等交互 */
export const isTouch = typeof window !== 'undefined' && !!window.matchMedia?.('(pointer: coarse)').matches

export const vw = width
export const isPhone = computed(() => width.value <= PHONE_MAX || (isTouch && height.value <= 500))
export const isPad = computed(() => !isPhone.value && width.value <= PAD_MAX)
/** 手机或平板：列表只显示关键列，次要信息合并到主列 */
export const isNarrow = computed(() => isPhone.value || width.value <= PAD_MAX)
/** 手机或平板竖屏：面包屑只显示页面标题、报告表格改卡片 */
export const isCompact = computed(() => isPhone.value || width.value <= COMPACT_MAX)

/** 列表摘要里的短时间：2026-10-17T15:04:05 → 10-17 15:04 */
export const shortTime = t => (t || '').replace('T', ' ').slice(5, 16)
