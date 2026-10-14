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
    <el-card class="side">
      <b>知识库</b>
      <!-- 只列出当前用户有权限的知识库；不选表示在全部有权限的知识库中检索 -->
      <el-select v-model="kbId" class="kb-select" placeholder="全部有权限的知识库" clearable @change="docId = null">
        <el-option v-for="k in kbs" :key="k.id" :label="k.name" :value="k.id" />
      </el-select>
      <b>文档范围</b>
      <el-radio-group v-model="docId" class="docs">
        <el-radio :value="null">该范围内全部文档</el-radio>
        <el-radio v-for="d in scopedDocs" :key="d.id" :value="d.id">{{ d.fileName }}</el-radio>
      </el-radio-group>
      <div class="samples">
        <b>示例问题</b>
        <el-button v-for="s in samples" :key="s" size="small" text bg @click="ask(s)">{{ s }}</el-button>
      </div>
    </el-card>

    <el-card class="main" body-style="display:flex;flex-direction:column;height:100%;box-sizing:border-box">
      <div class="top">
        <span class="session-tip">
          {{ rounds ? `当前对话 ${rounds} 轮，追问会自动结合上文` : '新对话' }}
        </span>
        <el-button size="small" icon="Plus" :disabled="loading" @click="newChat">新对话</el-button>
      </div>

      <div class="msgs" ref="box">
        <el-empty v-if="!msgs.length" description="上传文档后，在下方用自然语言提问；可以直接追问，例如“那满20年呢？”" />
        <div v-for="(m, i) in msgs" :key="i" :class="['msg', m.role]">
          <div class="bubble">
            <template v-if="m.role === 'user'">{{ m.text }}</template>
            <template v-else>
              <!-- 多轮时显示改写后的问题，便于用户确认系统理解是否正确 -->
              <div v-if="m.rewrite" class="rewrite">已结合上文理解为：{{ m.rewrite }}</div>
              <div class="answer" v-html="md(m.text)" />
              <div v-if="m.error" class="err">{{ m.error }}</div>

              <!-- 引用来源：点击打开原文 -->
              <div v-if="m.sources?.length" class="src">
                <div class="src-title">引用来源（点击查看原文）</div>
                <div v-for="s in m.sources" :key="s.index" class="s" @click="openSource(s)">
                  <el-tag size="small" effect="plain">[{{ s.index }}]</el-tag>
                  <span class="s-name">{{ s.file_name }}</span>
                  <span class="s-loc">{{ sourceLocation(s) }}</span>
                  <el-tag v-if="s.ocr" size="small" type="info">OCR</el-tag>
                  <el-tooltip :content="scoreDetail(s)" placement="top">
                    <span class="s-score">相关度 {{ s.score }}</span>
                  </el-tooltip>
                  <el-icon class="s-open"><View /></el-icon>
                </div>
              </div>

              <!-- 答案反馈：历史保存成功（拿到 historyId）后才显示 -->
              <div v-if="m.historyId" class="fb">
                <el-button
                  size="small"
                  :type="m.feedback === 1 ? 'success' : ''"
                  :plain="m.feedback !== 1"
                  @click="feedback(m, 1)"
                >👍 有用</el-button>
                <el-button
                  size="small"
                  :type="m.feedback === -1 ? 'danger' : ''"
                  :plain="m.feedback !== -1"
                  @click="feedback(m, -1)"
                >👎 没用</el-button>
                <span v-if="m.feedbackComment" class="fb-comment">原因：{{ m.feedbackComment }}</span>
              </div>
            </template>
          </div>
        </div>
        <div v-if="loading && !streaming" class="msg ai">
          <div class="bubble">{{ rounds ? '正在结合上文理解问题、检索文档…' : '正在检索文档并生成答案…' }}</div>
        </div>
      </div>

      <div class="input">
        <el-input
          v-model="q"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="请输入问题，Enter 发送，Shift+Enter 换行"
          @keydown.enter.exact.prevent="ask()"
        />
        <el-button v-if="loading" type="warning" @click="stop">停止生成</el-button>
        <el-button v-else type="primary" @click="ask()">发送</el-button>
      </div>
    </el-card>

    <SourceViewer ref="viewer" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted, watch } from 'vue'
import { marked } from 'marked'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import SourceViewer from '../components/SourceViewer.vue'
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
const msgs = ref([])
const q = ref('')
const loading = ref(false)
const streaming = ref(false)
const box = ref()
const viewer = ref()
const sessionId = ref(newSessionId())
let controller = null      // 用于“停止生成”

const samples = [
  '出现E-03故障代码应该如何处理？',
  '工作满10年有几天年假？',
  'C类员工去上海出差，每晚住宿上限是多少？',
  'LF-10滤芯多少钱一个，现在库存多少？',
  '新员工入职第一周要办哪些事？',
]

const md = t => marked.parse(t || '')

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
      out.push({ question: u.text, answer: a.text })
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
async function ask(text) {
  const question = (text || q.value).trim()
  if (!question || loading.value) return

  // 先取历史（不含本次问题），再把问题加入消息列表
  const history = buildHistory()
  msgs.value.push({ role: 'user', text: question })
  q.value = ''
  loading.value = true
  streaming.value = false
  scroll()

  const m = reactive({ role: 'ai', text: '', sources: [], rewrite: '', error: '', historyId: null, feedback: null })
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
  const [docList, kbList] = await Promise.all([api.get('/documents'), fetchMyKbs()])
  docs.value = docList.filter(d => d.status === 'ready')
  kbs.value = kbList
})
</script>

<style scoped>
.chat { display: flex; gap: 16px; height: calc(100vh - 100px); }
.side { width: 260px; overflow: auto; }
.main { flex: 1; min-width: 0; }
.kb-select { width: 100%; margin: 8px 0 16px; }
.docs { display: flex; flex-direction: column; align-items: flex-start; margin: 10px 0 20px; }
.samples { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; }
.samples .el-button { margin: 0; white-space: normal; text-align: left; height: auto; padding: 6px 8px; }
.top { display: flex; justify-content: space-between; align-items: center; padding-bottom: 8px; border-bottom: 1px solid #f0f0f0; }
.session-tip { color: #999; font-size: 12px; }
.msgs { flex: 1; overflow: auto; padding: 8px; }
.msg { display: flex; margin: 12px 0; }
.msg.user { justify-content: flex-end; }
.bubble { max-width: 80%; padding: 10px 14px; border-radius: 8px; background: #f0f2f5; line-height: 1.6; }
.user .bubble { background: #409eff; color: #fff; white-space: pre-wrap; }
.rewrite { font-size: 12px; color: #909399; margin-bottom: 4px; }
.err { color: #f56c6c; margin-top: 6px; }
.answer :deep(table) { border-collapse: collapse; }
.answer :deep(td), .answer :deep(th) { border: 1px solid #ddd; padding: 3px 8px; }
.src { margin-top: 8px; border-top: 1px dashed #ddd; padding-top: 6px; }
.src-title { font-size: 12px; color: #909399; margin-bottom: 4px; }
.s { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 4px 6px; border-radius: 4px; cursor: pointer; }
.s:hover { background: #e6f0ff; }
.s-name { font-weight: 500; }
.s-loc, .s-score { color: #909399; font-size: 12px; }
.s-open { margin-left: auto; color: #409eff; }
.fb { margin-top: 8px; display: flex; align-items: center; gap: 4px; }
.fb-comment { font-size: 12px; color: #909399; margin-left: 8px; }
.input { display: flex; gap: 10px; align-items: flex-end; margin-top: 10px; }
</style>
