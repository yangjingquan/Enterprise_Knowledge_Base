// 响应式断点：phone < 768 ≤ pad < 1200 ≤ desktop（与 styles/theme.css 中的 @media 保持一致）
import { ref, computed } from 'vue'

export const PHONE_MAX = 767
export const PAD_MAX = 1199

const width = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)
if (typeof window !== 'undefined') {
  window.addEventListener('resize', () => { width.value = window.innerWidth }, { passive: true })
}

export const vw = width
export const isPhone = computed(() => width.value <= PHONE_MAX)
export const isPad = computed(() => width.value > PHONE_MAX && width.value <= PAD_MAX)
/** 触屏设备（手机、平板）：用于切换"点两下框选"等交互 */
export const isTouch = typeof window !== 'undefined' && window.matchMedia?.('(pointer: coarse)').matches
