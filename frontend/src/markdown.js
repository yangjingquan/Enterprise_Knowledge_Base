// Markdown 渲染 + 代码高亮（只注册常用语言，控制体积）
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import python from 'highlight.js/lib/languages/python'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import sql from 'highlight.js/lib/languages/sql'
import bash from 'highlight.js/lib/languages/bash'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import 'highlight.js/styles/github.css'

Object.entries({ java, python, javascript, json, sql, bash, xml, yaml }).forEach(([n, l]) => hljs.registerLanguage(n, l))

const renderer = new marked.Renderer()
renderer.code = (code, lang) => {
  const l = (lang || '').trim()
  const html = l && hljs.getLanguage(l) ? hljs.highlight(code, { language: l }).value : hljs.highlightAuto(code).value
  return `<pre class="code"><div class="code-lang">${l || 'code'}</div><code class="hljs">${html}</code></pre>`
}
marked.setOptions({ renderer, breaks: true, gfm: true })

// 大模型回答、文档片段、评测报告都可能夹带 HTML：渲染后统一用 DOMPurify 净化，
// 只保留 Markdown 常用标签（表格、代码、列表、链接等），去掉 script / on* 事件 / javascript: 链接 / iframe 等
const PURIFY = {
  USE_PROFILES: { html: true },
  FORBID_TAGS: ['style', 'form', 'input', 'button', 'textarea', 'select', 'iframe', 'object', 'embed', 'svg', 'math'],
  FORBID_ATTR: ['style'],
}
DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'A' && node.getAttribute('href')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})
export const sanitize = html => DOMPurify.sanitize(html || '', PURIFY)
export const md = t => sanitize(marked.parse(t || ''))
