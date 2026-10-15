<!--
  智能问答页面。
  - 多轮对话：每次提问带上本会话最近 3 轮问答，后端会把“那满20年呢？”这类追问改写成完整问题再检索；
    点“新对话”开始新的会话（清空上下文、生成新的会话 ID）；
  - 流式输出：答案逐字显示，可随时“停止生成”；
  - 引用来源：点击来源打开原文查看器（PDF 跳到对应页并高亮）；
  - 答案反馈：每条回答下方可点“有用 / 没用”，点“没用”可填写原因。
  当前会话保存在 sessionStorage 中，切换到其他页面再回来不会丢失。
-->
<template>
  <div class="chat">
    <!-- 左侧：检索范围设置 -->
    <aside class="side panel">
      <el-button type="primary" class="new-btn" icon="Plus" :disabled="loading" @click="newChat">新建对话</el-button>
      <div class="sec-title"><el-icon><Collection /></el-icon>知识库</div>
      <!-- 只列出当前用户有权限的知识库；不选表示在全部有权限的知识库中检索 -->
      <el-select v-model="kbId" class="kb-select" placeholder="全部有权限的知识库" clearable @change="docId = null">
        <el-option v-for="k in kbs" :key="k.id" :label="k.name" :value="k.id" />
      </el-select>
      <div class="sec-title"><el-icon><Grid /></el-icon>问答方式</div>
      <!-- 自动：由系统判断问文档还是问数据；指定文档时只做文档问答 -->
      <el-radio-group v-model="mode" size="small" class="mode-sw" :disabled="!!docId">
        <el-radio-button value="auto">自动</el-radio-button>
        <el-radio-button value="doc">文档</el-radio-button>
        <el-radio-button value="data">数据</el-radio-button>
        <el-radio-button value="mixed">混合</el-radio-button>
      </el-radio-group>
      <el-select v-if="mode !== 'doc' && scopedDatasets.length" v-model="datasetIds" multiple collapse-tags size="small"
                 class="kb-select ds-select" placeholder="全部数据集（可选）" clearable>
        <el-option v-for="d in scopedDatasets" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <div class="sec-title"><el-icon><Files /></el-icon>文档范围<span class="cnt">{{ scopedDocs.length }}</span></div>
      <el-scrollbar class="doc-scroll">
        <div :class="['doc', { on: docId === null }]" @click="docId = null">
          <el-icon><FolderOpened /></el-icon><span>该范围内全部文档</span>
        </div>
        <div v-for="d in scopedDocs" :key="d.id" :class="['doc', { on: docId === d.id }]" :title="d.fileName" @click="docId = d.id">
          <span class="ext" :style="{ background: extColor(d.fileType) }">{{ extLabel(d.fileType) }}</span>
          <span class="doc-name">{{ d.fileName }}</span>
        </div>
        <el-skeleton v-if="docsLoading" :rows="4" animated style="padding:8px" />
      </el-scrollbar>
    </aside>

    <!-- 右侧：对话区 -->
    <section class="main panel">
      <div class="top">
        <div class="top-title">
          <b>{{ rounds ? '当前对话' : '新对话' }}</b>
          <el-tag v-if="rounds" size="small" round effect="plain">{{ rounds }} 轮 · 追问会自动结合上文</el-tag>
        </div>
        <div class="scope">
          <el-icon><Search /></el-icon>
          检索范围：{{ scopeText }}
        </div>
      </div>

      <div ref="box" class="msgs">
        <div class="inner">
          <!-- 空状态：欢迎语 + 推荐问题 -->
          <div v-if="!msgs.length" class="welcome">
            <div class="w-logo"><el-icon><MagicStick /></el-icon></div>
            <h2>你好，{{ displayName }}，有什么可以帮你？</h2>
            <p>我会从企业知识库中检索资料并给出带出处的回答，支持连续追问，例如“那满20年呢？”</p>
            <div class="suggest">
              <div v-for="s in samples" :key="s.q" class="sg" @click="ask(s.q)">
                <span class="sg-icon">{{ s.icon }}</span>
                <div><b>{{ s.t }}</b><span>{{ s.q }}</span></div>
              </div>
            </div>
          </div>

          <div v-for="(m, i) in msgs" :key="i" :class="['msg', m.role]">
            <div v-if="m.role === 'ai'" class="av ai-av"><el-icon><MagicStick /></el-icon></div>
            <div class="body">
              <div v-if="m.role === 'user'" class="bubble">{{ m.text }}</div>
              <template v-else>
                <!-- 多轮时显示改写后的问题，便于用户确认系统理解是否正确 -->
                <div v-if="m.rewrite" class="rewrite"><el-icon><Connection /></el-icon>已结合上文理解为：{{ m.rewrite }}</div>
                <div class="ai-card">
                  <div class="markdown" v-html="md(m.text)" />
                  <span v-if="loading && streaming && i === msgs.length - 1" class="caret" />
                  <DataAnswer v-if="(m.mode === 'data' || m.mode === 'mixed') && m.analysis" :a="m.analysis" :mode="m.mode"
                              :history-id="m.historyId" :switchable="!loading" @switch="x => switchDataset(i, x)" />
                  <el-alert v-if="m.error" :title="m.error" :type="m.error.includes('已停止') ? 'info' : 'error'" :closable="false" show-icon class="err" />

                  <!-- 引用来源卡片：点击打开原文 -->
                  <div v-if="m.sources?.length" class="src">
                    <div class="src-title"><el-icon><Reading /></el-icon>引用来源 · {{ m.sources.length }} 条（点击查看原文）</div>
                    <div class="src-grid">
                      <div v-for="s in m.sources" :key="s.index" class="s" @click="openSource(s)">
                        <span class="s-idx">{{ s.index }}</span>
                        <div class="s-main">
                          <div class="s-name" :title="s.file_name">{{ s.file_name }}</div>
                          <div class="s-meta">
                            <span>{{ sourceLocation(s) }}</span>
                            <el-tag v-if="s.ocr" size="small" type="info" effect="plain">OCR</el-tag>
                            <el-tooltip :content="scoreDetail(s)" placement="top">
                              <span class="s-score">相关度 {{ s.score }}</span>
                            </el-tooltip>
                          </div>
                        </div>
                        <el-icon class="s-open"><View /></el-icon>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- 操作栏：复制 + 反馈（历史保存成功后才显示反馈） -->
                <div v-if="m.text && !(loading && i === msgs.length - 1)" class="fb">
                  <el-tooltip content="复制回答"><el-button text circle size="small" icon="CopyDocument" @click="copy(m.text)" /></el-tooltip>
                  <template v-if="m.historyId">
                    <el-button size="small" round :type="m.feedback === 1 ? 'success' : ''" :plain="m.feedback === 1" @click="feedback(m, 1)">👍 有用</el-button>
                    <el-button size="small" round :type="m.feedback === -1 ? 'danger' : ''" :plain="m.feedback === -1" @click="feedback(m, -1)">👎 没用</el-button>
                    <span v-if="m.feedbackComment" class="fb-comment">原因：{{ m.feedbackComment }}</span>
                  </template>
                </div>
              </template>
            </div>
            <el-avatar v-if="m.role === 'user'" :size="34" class="av user-av">{{ initial }}</el-avatar>
          </div>

          <!-- 正在检索：打字指示器 -->
          <div v-if="loading && !streaming" class="msg ai">
            <div class="av ai-av"><el-icon><MagicStick /></el-icon></div>
            <div class="body"><div class="ai-card typing">
              <span class="dots"><i /><i /><i /></span>
              {{ rounds ? '正在结合上文理解问题、检索文档…' : '正在检索文档并生成答案…' }}
            </div></div>
          </div>
        </div>
      </div>

      <!-- 输入框 -->
      <div class="composer">
        <div class="input-box">
          <el-input
            v-model="q"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            resize="none"
            placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
            @keydown.enter.exact.prevent="ask()"
          />
          <el-button v-if="loading" class="send stop" circle @click="stop"><el-icon><VideoPause /></el-icon></el-button>
          <el-button v-else type="primary" class="send" circle :disabled="!q.trim()" @click="ask()"><el-icon><Promotion /></el-icon></el-button>
        </div>
        <div class="hint">回答由 AI 基于知识库生成，请以引用原文为准</div>
      </div>
    </section>

    <SourceViewer ref="viewer" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted, watch } from 'vue'
import { md } from '../markdown'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import SourceViewer from '../components/SourceViewer.vue'
import DataAnswer from '../components/DataAnswer.vue'
import { newSessionId } from '../textMatch'
import { fetchMyKbs } from '../kb'

/** 带入后端的历史轮数（与后端一致） */
const MAX_HISTORY_ROUNDS = 3
/** sessionStorage 中保存当前会话的键名 */
const STORE_KEY = 'kb-chat-session'

const docs = ref([])
const kbs = ref([])
const kbId = ref(null)
const docId = ref(null)
const mode = ref('auto')          // auto | doc | data
const datasets = ref([])
const datasetIds = ref([])
const msgs = ref([])
const q = ref('')
const loading = ref(false)
const streaming = ref(false)
const box = ref()
const viewer = ref()
const sessionId = ref(newSessionId())
let controller = null      // 用于“停止生成”

const samples = [
  { icon: '🛠️', t: '设备故障', q: '出现E-03故障代码应该如何处理？' },
  { icon: '🌴', t: '假期制度', q: '工作满10年有几天年假？' },
  { icon: '✈️', t: '差旅报销', q: 'C类员工去上海出差，每晚住宿上限是多少？' },
  { icon: '📦', t: '备件库存', q: 'LF-10滤芯多少钱一个，现在库存多少？' },
  { icon: '🎓', t: '入职指引', q: '新员工入职第一周要办哪些事？' },
  { icon: '🧾', t: '费用报销', q: '报销需要在多长时间内提交？' },
]
const displayName = localStorage.getItem('displayName') || localStorage.getItem('username')
const initial = (displayName || '?').slice(0, 1).toUpperCase()
const docsLoading = ref(true)
const EXT = { pdf: ['PDF', '#ef4444'], docx: ['DOC', '#2563eb'], xlsx: ['XLS', '#16a34a'], pptx: ['PPT', '#ea580c'], txt: ['TXT', '#64748b'], md: ['MD', '#0f172a'], url: ['WEB', '#7c3aed'] }
const extLabel = t => EXT[t]?.[0] || (t || '?').toUpperCase().slice(0, 3)
const extColor = t => EXT[t]?.[1] || '#64748b'
const scopeText = computed(() => {
  if (docId.value) return docs.value.find(d => d.id === docId.value)?.fileName || '指定文档'
  if (kbId.value) return kbs.value.find(k => k.id === kbId.value)?.name || '指定知识库'
  return '全部有权限的知识库'
})
async function copy(t) {
  try { await navigator.clipboard.writeText(t); ElMessage.success('已复制') } catch { ElMessage.warning('复制失败，请手动选择文本') }
}


const scopedDatasets = computed(() => kbId.value ? datasets.value.filter(d => d.kbId === kbId.value) : datasets.value)
watch(kbId, () => { datasetIds.value = [] })

/** 选中知识库后，文档列表只显示该知识库的文档 */
const scopedDocs = computed(() => kbId.value ? docs.value.filter(d => d.kbId === kbId.value) : docs.value)

/** 相关度明细：重排分数、向量相似度、BM25 名次 */
function scoreDetail(s) {
  const d = s.detail || {}
  const parts = []
  if (d.rerank_score != null) parts.push(`重排 ${d.rerank_score}`)
  if (d.vector_score != null) parts.push(`向量 ${d.vector_score}`)
  if (d.bm25_rank != null) parts.push(`关键词第 ${d.bm25_rank} 名`)
  return parts.join(' · ') || '向量相似度'
}
const scroll = () => nextTick(() => { if (box.value) box.value.scrollTop = box.value.scrollHeight })

/** 已完成的问答轮数（用户问题 + 成功的回答算一轮） */
const rounds = computed(() => buildHistory().length)

/** 从消息列表中提取最近几轮成功的问答，作为多轮上下文 */
function buildHistory() {
  const out = []
  for (let i = 0; i + 1 < msgs.value.length; i++) {
    const u = msgs.value[i]
    const a = msgs.value[i + 1]
    if (u.role === 'user' && a.role === 'ai' && a.text && !a.error) {
      const h = { question: u.text, answer: a.text }
      if (a.analysis?.sql) h.sql = a.analysis.sql   // 问数追问时带上上一轮 SQL
      out.push(h)
    }
  }
  return out.slice(-MAX_HISTORY_ROUNDS)
}

/** 来源位置描述：Excel 显示工作表，PPT 显示幻灯片，其他显示页码 */
function sourceLocation(s) {
  if (s.sheet_name) return `工作表「${s.sheet_name}」`
  if (/\.pptx$/i.test(s.file_name)) return `第${s.page}张幻灯片`
  const d = docs.value.find(x => x.id === s.document_id)
  if (d?.fileType === 'url') return '网页'
  return `第${s.page}页`
}

/** 提问：以流式方式读取后端 SSE 事件 */
async function ask(text, opts = {}) {
  const question = (text || q.value).trim()
  if (!question || loading.value) return

  // 先取历史（不含本次问题），再把问题加入消息列表
  const history = buildHistory()
  msgs.value.push({ role: 'user', text: question })
  q.value = ''
  loading.value = true
  streaming.value = false
  scroll()

  const m = reactive({ role: 'ai', text: '', sources: [], rewrite: '', error: '', historyId: null, feedback: null, mode: '', analysis: null })
  let added = false
  let finished = false
  const show = () => {
    if (!added) {
      msgs.value.push(m)
      added = true
      streaming.value = true
    }
  }

  controller = new AbortController()
  try {
    const res = await fetch('/api/qa/ask/stream', {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${localStorage.getItem('token')}`,
      },
      body: JSON.stringify({
        question,
        kbId: kbId.value,
        documentId: docId.value,
        sessionId: sessionId.value,
        history,
        mode: docId.value ? 'doc' : (opts.mode || mode.value),
        datasetIds: opts.datasetIds || (datasetIds.value.length ? datasetIds.value : null),
      }),
    })
    if (res.status === 401) {
      localStorage.clear()
      window.location.href = '/login'
      return
    }
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
      throw new Error(err.error || `服务返回 ${res.status}`)
    }

    // 按行解析 SSE：每个事件形如 data: {...}
    const reader = res.body.getReader()
    const dec = new TextDecoder()
    let buf = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      const lines = buf.split('\n')
      buf = lines.pop()
      for (const l of lines) {
        if (!l.startsWith('data: ')) continue
        const ev = JSON.parse(l.slice(6))
        if (ev.type === 'rewrite') {
          m.rewrite = ev.data
        } else if (ev.type === 'route') {
          m.mode = ev.data
        } else if (ev.type === 'sql') {
          m.analysis = { ...(m.analysis || {}), ...ev.data }
          show()
        } else if (ev.type === 'table') {
          m.analysis = { ...(m.analysis || {}), table: ev.data }
          scroll()
        } else if (ev.type === 'chart') {
          m.analysis = { ...(m.analysis || {}), chart: ev.data }
        } else if (ev.type === 'sources') {
          m.sources = ev.data
        } else if (ev.type === 'delta') {
          show()
          m.text += ev.data
          scroll()
        } else if (ev.type === 'saved') {
          m.historyId = ev.id
        } else if (ev.type === 'error') {
          throw new Error(ev.data)
        } else if (ev.type === 'done') {
          finished = true
        }
      }
    }
    // 连接正常关闭但没有收到 done，说明中途断开
    if (!finished) throw new Error('连接中断')
  } catch (e) {
    show()
    m.error = e.name === 'AbortError' ? '（已停止生成）' : `请求失败：${e.message || '请稍后重试'}`
    if (e.name !== 'AbortError') console.error(e)
  } finally {
    loading.value = false
    streaming.value = false
    controller = null
    scroll()
  }
}

/** 多个数据集都可能回答时，用户点"改用 X"：用同一个问题、指定该数据集重新提问 */
function switchDataset(i, x) {
  const question = msgs.value[i - 1]?.role === 'user' ? msgs.value[i - 1].text : ''
  if (!question) return
  ask(question, { mode: msgs.value[i].mode === 'mixed' ? 'mixed' : 'data', datasetIds: [x.dataset_id] })
}

/** 停止生成：中断请求 */
function stop() {
  controller?.abort()
}

/** 新对话：清空消息、换一个会话 ID */
function newChat() {
  msgs.value = []
  sessionId.value = newSessionId()
}

/** 打开原文查看器 */
function openSource(s) {
  const d = docs.value.find(x => x.id === s.document_id)
  viewer.value.open(s, d)
}

/**
 * 提交反馈。再次点击同一个按钮表示取消反馈。
 * 点“没用”时弹框询问原因（可不填），原因会出现在问答历史中，便于管理员优化文档。
 */
async function feedback(m, value) {
  let comment = null
  if (m.feedback === value) {
    value = 0
  } else if (value === -1) {
    try {
      const r = await ElMessageBox.prompt('请简单说明哪里不对（可不填），帮助我们改进知识库', '反馈：回答没用', {
        confirmButtonText: '提交',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：答案不完整 / 引用的文档过期 / 没理解我的问题',
        inputValidator: v => !v || v.length <= 500 || '最多 500 字',
      })
      comment = r.value
    } catch {
      return   // 用户点了取消
    }
  }
  const saved = await api.post(`/qa/${m.historyId}/feedback`, { value, comment })
  m.feedback = saved.feedback ?? null
  m.feedbackComment = saved.feedbackComment || ''
  ElMessage.success(value === 0 ? '已取消反馈' : '感谢反馈')
}

// 当前会话写入 sessionStorage，切换页面再回来不丢失
watch([msgs, sessionId], () => {
  sessionStorage.setItem(STORE_KEY, JSON.stringify({ sessionId: sessionId.value, msgs: msgs.value }))
}, { deep: true })

onMounted(async () => {
  try {
    const saved = JSON.parse(sessionStorage.getItem(STORE_KEY) || 'null')
    if (saved?.sessionId) {
      sessionId.value = saved.sessionId
      msgs.value = (saved.msgs || []).map(x => reactive(x))
    }
  } catch {
    // 数据损坏就忽略，开始新对话
  }
  scroll()
  const [docList, kbList, dsList] = await Promise.all([api.get('/documents'), fetchMyKbs(), api.get('/datasets').catch(() => [])])
  datasets.value = (dsList || []).filter(d => d.status === 'ready')
  docs.value = docList.filter(d => d.status === 'ready')
  kbs.value = kbList
  docsLoading.value = false
})
</script>

<style scoped>
.chat { display: flex; gap: 16px; height: calc(100vh - 112px); min-height: 520px; }
.side { width: 272px; flex-shrink: 0; padding: 16px; display: flex; flex-direction: column; }
.new-btn { width: 100%; height: 40px; margin-bottom: 18px; background: linear-gradient(90deg, #4f46e5, #7c3aed); border: none; }
.sec-title { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--text-2); margin: 4px 0 8px; }
.sec-title .cnt { margin-left: auto; font-weight: 500; color: var(--text-3); font-size: 12px; }
.kb-select { width: 100%; margin-bottom: 18px; }
.mode-sw { margin-bottom: 10px; display: flex; }
.mode-sw :deep(.el-radio-button) { flex: 1; }
.mode-sw :deep(.el-radio-button__inner) { width: 100%; }
.ds-select { margin-bottom: 14px; }
.doc-scroll { flex: 1; margin: 0 -6px; }
.doc { display: flex; align-items: center; gap: 8px; padding: 8px 10px; margin: 0 6px 2px; border-radius: 8px; cursor: pointer; font-size: 13px; color: var(--text-2); transition: background .15s; }
.doc:hover { background: #f5f6fb; }
.doc.on { background: var(--brand-soft); color: var(--brand); font-weight: 600; }
.ext { font-size: 10px; color: #fff; padding: 1px 4px; border-radius: 4px; font-weight: 700; flex-shrink: 0; min-width: 30px; text-align: center; }
.doc-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; overflow: hidden; }
.top { display: flex; justify-content: space-between; align-items: center; padding: 14px 24px; border-bottom: 1px solid var(--border); gap: 12px; }
.top-title { display: flex; align-items: center; gap: 10px; }
.top-title b { font-size: 15px; }
.scope { font-size: 12px; color: var(--text-3); display: flex; align-items: center; gap: 4px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.msgs { flex: 1; overflow: auto; background: linear-gradient(180deg, #fbfbfe 0%, #fff 240px); }
.inner { max-width: 860px; margin: 0 auto; padding: 24px 24px 8px; }
.welcome { text-align: center; padding: 6vh 0 12px; }
.w-logo { width: 64px; height: 64px; margin: 0 auto 18px; border-radius: 20px; display: grid; place-items: center; color: #fff; font-size: 32px;
  background: linear-gradient(135deg, #6366f1, #a855f7); box-shadow: 0 12px 30px rgba(99, 102, 241, .35); }
.welcome h2 { margin: 0 0 8px; font-size: 24px; }
.welcome p { color: var(--text-3); margin: 0 0 32px; font-size: 14px; }
.suggest { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; text-align: left; }
.sg { display: flex; gap: 12px; align-items: flex-start; padding: 14px 16px; border: 1px solid var(--border); border-radius: 12px; background: #fff; cursor: pointer; transition: all .2s; }
.sg:hover { border-color: #c7d2fe; box-shadow: var(--shadow); transform: translateY(-2px); }
.sg-icon { font-size: 22px; }
.sg b { display: block; font-size: 13px; margin-bottom: 2px; }
.sg span { font-size: 13px; color: var(--text-2); line-height: 1.5; }
.msg { display: flex; gap: 12px; margin: 0 0 24px; align-items: flex-start; }
.msg.user { justify-content: flex-end; }
.av { flex-shrink: 0; }
.ai-av { width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center; color: #fff; font-size: 18px; background: linear-gradient(135deg, #6366f1, #a855f7); }
.user-av { background: #334155; font-weight: 600; }
.body { min-width: 0; max-width: calc(100% - 46px); }
.msg.ai .body { flex: 1; }
.bubble { padding: 10px 16px; border-radius: 16px 4px 16px 16px; background: linear-gradient(135deg, #4f46e5, #6d5df6); color: #fff; line-height: 1.7; white-space: pre-wrap; box-shadow: 0 4px 12px rgba(79, 70, 229, .2); }
.rewrite { font-size: 12px; color: var(--text-3); margin-bottom: 6px; display: flex; align-items: center; gap: 4px; }
.ai-card { background: #fff; border: 1px solid var(--border); border-radius: 4px 16px 16px 16px; padding: 14px 18px; box-shadow: var(--shadow-sm); }
.caret { display: inline-block; width: 8px; height: 16px; background: var(--brand); vertical-align: text-bottom; animation: blink 1s steps(2) infinite; border-radius: 2px; }
@keyframes blink { 50% { opacity: 0; } }
.err { margin-top: 10px; }
.typing { color: var(--text-3); font-size: 13px; display: flex; align-items: center; gap: 10px; }
.dots { display: inline-flex; gap: 4px; }
.dots i { width: 6px; height: 6px; border-radius: 50%; background: var(--brand); animation: bounce 1.2s infinite ease-in-out; }
.dots i:nth-child(2) { animation-delay: .15s; } .dots i:nth-child(3) { animation-delay: .3s; }
@keyframes bounce { 0%, 80%, 100% { transform: scale(.5); opacity: .4; } 40% { transform: scale(1); opacity: 1; } }
.src { margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--border); }
.src-title { font-size: 12px; color: var(--text-3); margin-bottom: 8px; display: flex; align-items: center; gap: 4px; }
.src-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 8px; }
.s { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid var(--border); border-radius: 10px; cursor: pointer; background: #fafbfd; transition: all .15s; }
.s:hover { border-color: #a5b4fc; background: var(--brand-soft); }
.s-idx { width: 22px; height: 22px; border-radius: 6px; background: var(--brand-soft); color: var(--brand); font-size: 12px; font-weight: 700; display: grid; place-items: center; flex-shrink: 0; }
.s-main { min-width: 0; flex: 1; }
.s-name { font-size: 13px; font-weight: 600; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.s-meta { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); margin-top: 2px; }
.s-score { cursor: help; }
.s-open { color: var(--text-3); }
.s:hover .s-open { color: var(--brand); }
.fb { margin-top: 8px; display: flex; align-items: center; gap: 4px; }
.fb .el-button + .el-button { margin-left: 4px; }
.fb-comment { font-size: 12px; color: var(--text-3); margin-left: 8px; }
.composer { padding: 12px 24px 14px; }
.input-box { max-width: 860px; margin: 0 auto; display: flex; align-items: flex-end; gap: 10px; padding: 10px 10px 10px 16px; border: 1px solid var(--border); border-radius: 16px; background: #fff; box-shadow: var(--shadow); transition: border-color .2s, box-shadow .2s; }
.input-box:focus-within { border-color: #a5b4fc; box-shadow: 0 0 0 4px rgba(99, 102, 241, .1), var(--shadow); }
.input-box :deep(.el-textarea__inner) { box-shadow: none !important; padding: 6px 0; font-size: 14.5px; background: transparent; }
.send { width: 38px; height: 38px; font-size: 18px; flex-shrink: 0; }
.stop { background: #f97316; color: #fff; border: none; }
.hint { text-align: center; font-size: 12px; color: var(--text-3); margin-top: 8px; }
@media (max-width: 992px) { .side { display: none; } .chat { height: calc(100vh - 92px); } }
</style>
