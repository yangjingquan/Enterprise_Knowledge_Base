# 企业AI知识库（RAG 文档智能问答系统）

上传 PDF / Word / Excel / PPT / TXT / Markdown 文档或导入网页后，用自然语言提问，系统基于文档内容给出**带出处的回答**（文件名、页码、原文片段、相似度），文档中没有的内容明确回答「无法回答」。

## 技术架构

```
前端 Vue3 + Element Plus ──▶ Java Spring Boot 3 ──▶ Python FastAPI + LangChain
   (nginx, :5173)          用户/JWT/文档/历史        解析 · 切分 · 向量化 · 检索 · 生成
                                  │                          │
                               MySQL 8                 Chroma + bge-m3 ──▶ DeepSeek
```

| 模块 | 技术 |
|---|---|
| 登录鉴权 | Spring Security + JWT，BCrypt 密码 |
| 文档管理 | 上传（类型白名单、≤20MB）、异步处理、状态流转 pending → processing → ready / failed |
| 文档解析 | pdfplumber（表格转 Markdown、NFKC 字符标准化）、python-docx、openpyxl（每个工作表转 Markdown 表格，每块重复表头）、python-pptx（按幻灯片，含表格和演讲者备注）、trafilatura（网页正文提取） |
| 多轮对话 | 带最近 3 轮历史；DeepSeek 先把追问改写成独立问题再检索，历史作为多轮消息参与生成；会话 ID 记入历史 |
| 原文查看 | 点击引用来源：PDF 用 pdf.js 渲染对应页并高亮引用文字；其他格式显示引用块及前后文 |
| 答案反馈 | 每条回答「有用 / 没用」，没用可填原因；问答历史可按反馈筛选，显示满意度统计 |
| 切分 | RecursiveCharacterTextSplitter，500 字 / 重叠 50，中文标点优先 |
| 向量化 | BAAI/bge-m3（1024 维，本地离线），Chroma 持久化，document_id 元数据过滤 |
| 问答 | Top-4 检索，相似度 < 0.5 直接拒答，temperature 0.2，SSE 流式输出 |

## 一键启动（Docker）

```bash
cp .env.example .env        # 填入 DEEPSEEK_API_KEY 和 DASHSCOPE_API_KEY（阿里云百炼）
docker compose up -d --build
```

- 默认使用阿里云百炼云端向量（text-embedding-v3）和重排（gte-rerank-v2），不下载本地模型，Python 服务几秒即就绪
- 想继续用本地 bge-m3：`.env` 中设 `EMBEDDING_PROVIDER=local`、`RERANK_PROVIDER=local`（或 `none`）、`WITH_LOCAL_MODELS=true`、`PY_MEM_LIMIT=6g`，国内网络经 `https://hf-mirror.com` 下载模型
- 启动后访问 http://localhost:5173 ，演示账号 **admin / admin123**

## 本地开发启动

```bash
# 1. MySQL：执行 sql/init.sql，创建用户 kb / kb123
# 2. Python RAG 服务
cd backend-python && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
export DEEPSEEK_API_KEY=sk-xxx && .venv/bin/uvicorn app.main:app --port 8000
# 3. Java 服务（JDK 21）
cd backend-java && mvn -DskipTests package && java -jar target/kb-backend-0.1.0.jar
# 4. 前端
cd frontend && npm install && npm run dev      # http://localhost:5173
```

## 第一档扩展功能（v0.2）

1. **多轮对话**：直接追问「那满20年呢？」「C类呢？」，系统会显示「已结合上文理解为：……」。右上角「新对话」清空上下文。
2. **点击来源看原文**：回答下方的引用来源可点击。PDF 打开对应页并用黄色高亮引用内容，可翻页、下载原文件；Excel 显示工作表中的引用块及前后表格；PPT 显示对应幻灯片内容；网页提供原网址链接。
3. **答案反馈**：每条回答下有 👍 有用 / 👎 没用（可填原因，再点一次取消）。「问答历史」页顶部显示满意度统计，可按有用 / 没用 / 未反馈筛选，点「N 轮」查看整段对话。
4. **更多格式**：新增 Excel（.xlsx）、PPT（.pptx）上传，以及「导入网页」（输入网址，自动提取正文，导航栏和广告会被去掉）。

数据库升级：已有的 MySQL 数据卷不会重新执行 `sql/init.sql`，Java 启动时 `SchemaMigration` 会自动补齐新增的列（`qa_history.session_id / rewritten_question / username / feedback / feedback_comment / feedback_time`、`document.source_url`），可重复执行，不会丢数据。

## 第二档扩展功能（v0.3）

### 1. 混合检索 + 重排序
- **BM25 关键词检索**：jieba 分词，并单独提取编号和数字（E-03、LF-10、8200、WX-20260918-07），解决纯向量检索对编号不敏感的问题。索引持久化在 `CHROMA_DIR/bm25_tokens.json`，启动时与 Chroma 对账，上传、删除、移动知识库时同步更新。
- **RRF 融合**：向量 Top20 与 BM25 Top20 用 RRF（k=60）合并。
- **交叉编码器重排**：`BAAI/bge-reranker-v2-m3` 在 CPU 上对前 15 个候选重新打分，低于 `RERANK_THRESHOLD`（默认 0.15）的丢弃，用来拒答无关问题。
- 设置 `RERANK_ENABLED=false` 后退回“向量 + BM25”混合检索（阈值改用向量相似度 0.5）。来源卡片悬停可看到向量分、BM25 排名和重排分。

### 2. 知识库分组与权限
- 文档都归属于某个知识库，`kb_id` 写入 Chroma 和 BM25 的元数据，检索时按知识库过滤。
- 角色：系统管理员管理全部；普通用户在各知识库上为“查看”（问答、看原文）或“编辑”（还能上传、删除、移动文档）；“公开”知识库所有人默认可查看。
- 权限由 Java 统一判定：问答时把用户可读的 kb_ids 传给 Python。原文、上下文、删除接口都做校验，无权限返回 404，不暴露文档是否存在。问答历史按用户隔离，管理员可看全部。
- 升级迁移：旧文档自动归入“公共知识库”（公开），启动时同步 Chroma 元数据。
- 演示账号（`KB_DEMO_SEED=true` 时自动创建）：

| 账号 | 密码 | 身份 | 权限 |
|---|---|---|---|
| admin | admin123 | 系统管理员 | 全部 |
| hr01 | Hr@2026 | 人事专员-李婷 | 人事制度：编辑 |
| fin01 | Fin@2026 | 财务会计-陈静 | 财务制度：编辑；人事制度：查看 |
| eng01 | Eng@2026 | 设备工程师-王建国 | 设备技术：编辑；人事制度：查看 |

所有人都可以查看“公共知识库”。建议的上传归属：员工手册、PPT → 人事制度；差旅报销 → 财务制度；SK-850 手册、点检 Excel、扫描件 → 设备技术；网页 → 公共知识库。

### 3. 扫描件 OCR
文字少于 20 个字但含有图片的 PDF 页面，按 200 DPI 渲染后交给 RapidOCR（ONNX，CPU，模型随安装包自带，约 15MB）识别。文档列表会显示“OCR n 页”标签。演示文件：`demo-data/设备点检记录表（扫描件）.pdf`，可以问“点检发现的排屑机异响报修单号是多少？”。

### 4. 效果评测
`backend-python/eval/dataset.json` 有 60 道题，覆盖事实、PPT、编号、Excel、OCR、多轮和拒答。指标包括 hit@1/3/5、MRR、拒答率和答案关键词正确率（可选大模型裁判），对比 vector、hybrid、rerank 三种模式。
```bash
docker compose exec backend-python python -m eval.run_eval            # 报告：/data/chroma_db/eval-report.md
docker compose exec backend-python python -m eval.run_eval --judge    # 加大模型裁判
```
管理员也可以在“效果评测”页面一键运行。最新结果见 [docs/eval-report.md](docs/eval-report.md)。

### 新增接口
`GET /api/me`、`GET /api/kbs`、`/api/admin/kbs[/{id}[/members]]`、`/api/admin/users[/{id}]`、`PUT /api/documents/{id}/kb`、`/api/admin/eval/run|status`；上传接口新增 `kbId` 参数，问答接口新增 `kbId` 字段。

### 资源占用
**云端模式（默认，v0.4）**：见下文「4GB 内存服务器部署」。以下为本地模型模式：
bge-m3 约 2.2GB、bge-reranker-v2-m3 约 2.2GB（首次启动经 hf-mirror 下载）。Python 容器运行时约占 5~5.5GB 内存。Docker Desktop 建议分配 ≥ 7GB；在 8GB 内存的电脑上建议设置 `RERANK_ENABLED=false`（约 3GB）。

## 4GB 内存服务器部署（v0.4 低内存模式）

向量化和重排改为调用阿里云百炼 API，本机不再加载 torch 和模型，Python 镜像从 2.25GB 降到 1.06GB。

| 容器 | 内存上限 | 实测空闲 | 实测峰值 |
|---|---|---|---|
| backend-python | 1g（`PY_MEM_LIMIT`） | 约 130MB | 约 650MB（扫描件 OCR），问答约 300MB |
| backend-java | 512m（堆 `-Xmx256m`） | 约 210MB | 约 250MB |
| mysql | 512m（`innodb_buffer_pool_size=128M`，关闭 performance_schema） | 约 130MB | 约 180MB |
| frontend（nginx） | 64m | 约 8MB | — |

合计峰值约 1.1GB，4GB 服务器给系统留出足够余量（仍建议加 2GB swap 防止意外）。

### 步骤
1. 开通百炼并创建 API Key：https://bailian.console.aliyun.com/ → 「API-KEY」→ 创建（新用户有免费额度；text-embedding-v3 和 gte-rerank-v2 均按 token 计费，演示数据重建一次索引花费不到 1 分钱）。
2. 在 `.env` 中添加（参考 `.env.example`）：
   ```
   DASHSCOPE_API_KEY=sk-你的百炼key
   EMBEDDING_PROVIDER=dashscope
   RERANK_PROVIDER=dashscope      # 不想用重排设为 none
   ```
3. 重新构建并启动：`docker compose up -d --build`
4. 已有文档重建索引（升级时执行一次，约 15 秒/百块，不需要重新上传文件）：
   ```
   docker compose exec backend-python python -m app.reindex
   docker compose restart backend-python
   ```
   脚本会把旧集合 `kb_docs`（bge-m3 向量）里的文本和元数据复制到新集合 `kb_docs_dashscope_v3_1024` 并重新计算向量；旧集合保留，可随时切回本地模式。
   也可在服务运行中调用 `POST /admin/reindex`（Python 服务内网接口），`GET /admin/reindex` 查看进度。
5. 检查：`docker compose exec backend-python python -c "import urllib.request;print(urllib.request.urlopen('http://localhost:8000/stats').read().decode())"`，`chunks_vector` 应等于文档块数。

### 效果对比（60 题评测集，详见 docs/eval-report-dashscope.md）
| 方案 | Hit@1 | Hit@3 | 单次检索平均耗时 |
|---|---|---|---|
| 本地 bge-m3 向量 / 混合 | 74.5% | 92.7% | — |
| 本地 bge-m3 + bge-reranker | 90.9% | 100% | 约 3 秒（CPU） |
| 百炼 v3 向量 / 混合 | 85.5% | 94.5% | 约 0.9~1.0 秒 |
| 百炼 v3 + gte-rerank-v2 | 94.5% | 100% | 约 1.6 秒 |

注意：百炼向量的相似度分布与 bge-m3 不同，无关问题也有 0.47~0.65，向量阈值（`SCORE_THRESHOLD`，云端默认 0.4）无法单独拒答，建议保持重排开启（拒答题 5 题中 4 题检索为空，剩下 1 题由大模型拒答）。
云端重排调用失败时会自动退回混合检索，问答不中断。

## 演示步骤（1-2 分钟）

1. 登录 → 进入「文档管理」，上传 `demo-data/SK-850维护手册.pdf`
2. 状态从「处理中」变为「就绪」（约 15-20 秒，15 个切片）
3. 进入「智能问答」，点击左侧示例问题，观察流式回答，展开「引用来源」查看原文与页码
4. 问一个文档外的问题，展示拒答能力
5. 在「问答历史」查看记录

### 标准测试问题与预期答案

| 问题 | 预期答案 | 出处 |
|---|---|---|
| 主轴轴承的更换周期是多久？ | 每运行 8000 小时或 2 年，先到为准 | 第 4 页，3.1/3.5 |
| 出现 E-03 故障代码应该如何处理？ | 主轴过热：停主轴 → 查冷却机和冷却油 → 降至 30℃ 以下低速试运行 → 仍报警查轴承并通知设备动力部 | 第 6 页，第四章 |
| 每日开机前需要检查哪些项目？ | 导轨油位、气压、冷却液、主轴冷却机、防护门联锁、急停、防护罩、电气柜风扇共 8 项 | 第 3 页，2.1 |
| 更换导轨润滑油滤芯的步骤是什么？ | 断电挂牌 → 接油盘 → 旋下外壳 → 换 LF-10 滤芯 → 旋紧 15 N·m → 试泵 3 次 → 记录 | 第 4-5 页，3.3 |
| 今天上海天气怎么样？ | 根据现有文档无法回答该问题 | — |

### 扩展功能测试问题（需上传 demo-data 下全部示例文件）

| 测试点 | 问题 | 预期答案 | 出处 |
|---|---|---|---|
| 多轮 | ① 工作满10年有几天年假？ ② 那满20年呢？ | ① 12 天（法定 10 + 福利 2）② 17 天（法定 15 + 福利 2），第二问显示改写为“工作满20年有几天年假” | 员工手册.pdf 4.2 |
| 多轮 | ① C类员工去上海出差，每晚住宿上限是多少？ ② 那去成都呢？ | ① 每晚 500 元（一线城市）② 每晚 400 元（成都为省会城市） | 差旅与费用报销管理制度.pdf |
| Excel | LF-10滤芯多少钱一个，现在库存多少？ | 85 元/个，库存 36 个（安全库存 20） | 备件表 · 工作表「备件价格与库存」 |
| Excel | 采购一批 6 万元的备件需要谁审批？ | 50000 元以上报分管副总经理审批 | 备件表 · 工作表「采购与领用规则」 |
| Excel 多轮 | ① 主轴轴承（前）的供应商是谁？ ② 他们的联系电话是多少？ | ① 上海恒锐轴承 ② 王磊 021-58830216 | 「备件价格与库存」「供应商联系表」 |
| PPT | 新员工入职第一周要办哪些事？ | 第1天领工牌开账号录人脸，第2天签合同和保密协议…… | 新员工入职培训.pptx 第5张 |
| PPT 备注 | 入职培训考试多少分及格？ | 80 分（下午场结束后 30 分钟闭卷考试） | 第2张幻灯片备注 |
| 网页 | 导入任意公开网页后，就网页内容提问 | 回答并标注网页标题 | 网页 |

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/login | 登录，返回 JWT |
| POST | /api/documents/upload | 上传文档（multipart，pdf/docx/xlsx/pptx/txt/md） |
| POST | /api/documents/url | 导入网页，请求体 `{"url": "https://..."}` |
| GET | /api/documents | 文档列表 |
| GET | /api/documents/{id}/file | 获取原文件（需登录，前端以 blob 方式获取） |
| GET | /api/documents/{id}/context?chunkIndex=&window=2 | 引用块及前后文 |
| DELETE | /api/documents/{id} | 删除文档、向量和原文件 |
| POST | /api/qa/ask | 问答（一次性返回），请求体 `{question, documentId?, sessionId?, history?: [{question, answer}]}`，返回含 `historyId` |
| POST | /api/qa/ask/stream | 问答（SSE 流式），事件：rewrite → sources → delta… → saved{id} → done（出错时 error） |
| POST | /api/qa/{id}/feedback | 反馈，`{value: 1 \| -1 \| 0, comment?}`，0 表示取消 |
| GET | /api/qa/history?feedback=up\|down\|none&sessionId=&limit= | 问答历史（筛选） |
| GET | /api/qa/feedback/stats | 反馈统计：total / up / down / none |

Python 内部接口（仅 Docker 内网）：`/ingest`、`/ask`、`/ask/stream`、`/fetch-url`、`/documents/{id}/context`、`/search`、`/parse`。

## 目录结构

```
├── backend-java/      Spring Boot：鉴权、文档、问答编排、历史
├── backend-python/    FastAPI：document_loader.py / vectorstore.py / rag.py / web_fetch.py，tests/ 单元测试
├── frontend/          Vue3 + Element Plus
├── sql/init.sql       建表脚本
├── demo-data/         演示文档（3 份 PDF、备件表 xlsx、入职培训 pptx）与截图
├── docker-compose.yml
└── .env.example
```

## 可扩展方向

混合检索 + 重排序、知识库分组与权限、扫描件 OCR、效果评测集、企业微信/钉钉机器人、管理看板。

## 单元测试

```bash
cd backend-python && .venv/bin/pip install pytest && .venv/bin/python -m pytest -q tests
```
