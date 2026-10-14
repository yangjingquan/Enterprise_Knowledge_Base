// 构建前把 pdf.js 的中文字符映射表（cmaps）和标准字体复制到 public/pdfjs，
// 部分中文 PDF 没有内嵌完整字体，缺少 cmaps 时会显示成空白或乱码。
import { cpSync, mkdirSync } from 'node:fs'

const src = 'node_modules/pdfjs-dist'
const dest = 'public/pdfjs'
mkdirSync(dest, { recursive: true })
cpSync(`${src}/cmaps`, `${dest}/cmaps`, { recursive: true })
cpSync(`${src}/standard_fonts`, `${dest}/standard_fonts`, { recursive: true })
console.log('pdf.js 资源已复制到', dest)
