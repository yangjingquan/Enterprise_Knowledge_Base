// Markdown 渲染 + 代码高亮（只注册常用语言，控制体积）
import { marked } from 'marked'
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

export const md = t => marked.parse(t || '')
