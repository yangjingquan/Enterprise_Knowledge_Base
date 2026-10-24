# 问数功能上线部署说明（kb.nexbyte.top）

本次上线只新增功能，不改动现有文档问答的数据。原则：**不影响服务器上的其他服务**（shop-*）。

## 1. 变更概览

| 项目 | 变更 |
|---|---|
| backend-python 内存 | `mem_limit` 768m → **1g**，`memswap_limit` 1g → **1280m**（4GB 服务器总占用：python 1g + java 384m + 前端 64m） |
| 新卷 | **`ai-kb-datasets`** → `/data/datasets`（每个数据集一个 `{id}.duckdb` 文件，加一个 `{id}.plan.json`） |
| 新环境变量 | `DATASET_DIR=/data/datasets`、`DUCKDB_MEMORY_LIMIT=256MB`、`DUCKDB_THREADS=2`（已写在 docker-compose.prod.yml，`.env` 不用改） |
| pip | 新增 `duckdb==1.5.6`、`xlrd==2.0.2`、`sqlglot==30.23.0`。**清华源没有 duckdb 1.5.6**，Dockerfile 已加 `PIP_EXTRA_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/`（并写入 ENV，pip 会自动使用），主源仍是清华 |
| 前端 | 新增依赖 `echarts`（npmmirror）。图表 chunk 异步加载，首屏不变 |
| MySQL（ai_kb） | **自动迁移**：Java 启动时由 `SchemaMigration` 幂等执行，不需要手工跑 SQL。新表 `dataset`、`dataset_block`、`dataset_table`、`dataset_column`；`qa_history` 新列 `mode VARCHAR(10)`、`analysis JSON`。专用账号需要对 ai_kb 有 CREATE / ALTER 权限（之前的迁移已经用过，应该已具备） |
| 上传目录 | 数据集原文件保存在现有 `ai-kb-uploads` 卷的 `datasets/` 子目录 |
| nginx | `client_max_body_size 60m`（大于 50MB 上传上限，留 multipart 余量） |

## 2. 上线步骤

1. **备份**（可选但建议）：`docker exec shop-mysql mysqldump -u<root> ai_kb > /opt/ai-kb/backup-$(date +%F).sql`。
2. 推送代码，然后由 Jenkins 任务 kb.nexbyte.top 构建。等价的命令：
   ```bash
   cd /opt/ai-kb && docker compose -p ai-kb -f docker-compose.prod.yml up -d --build
   ```
   `ai-kb-datasets` 卷由 compose 自动创建。
3. 观察：
   - `docker logs ai-kb-backend-java | grep -i migration`：能看到新表 / 新列创建，没有报错；
   - `docker inspect -f '{{.State.Health.Status}}' ai-kb-backend-python` 显示 healthy；
   - `docker stats --no-stream`：python 空闲约 200MB，问数和导入时 < 600MB（本地压测：100 万行导入峰值 167MB，6 个并发提问 194MB）；
   - `free -m`：整机仍有余量。
4. 冒烟测试（浏览器）：
   - 文档问答"工作满10年有几天年假"→ 12 天，并带引用；
   - 「数据集」上传 `demo-data/datasets/marketing-2026Q3.xlsx` → 确认导入 → 可用；
   - 问"9 月各渠道的成交金额"→ 有图表、表格、SQL，可以导出 CSV；
   - 冒烟测试结束后删除这个演示数据集（或者保留作为示例）。

## 3. 回滚

- 代码：Jenkins 回滚到上一个构建，或者 `git checkout <上一版本> && docker compose ... up -d --build`。
- 数据库：新增的表和列对旧版本**无影响**（旧代码不读取），不需要回滚。如果要彻底清除：`DROP TABLE dataset_column, dataset_table, dataset_block, dataset; ALTER TABLE qa_history DROP COLUMN mode, DROP COLUMN analysis;`。
- 卷：`docker volume rm ai-kb-datasets`（会删除全部数据集）。
- 内存：回滚后可以把 mem_limit 改回 768m。

## 4. 已知限制

- 本地验收用的是 MariaDB；生产是 MySQL 8（shop-mysql）。迁移语句只用了 `CREATE TABLE IF NOT EXISTS`，加列前先查 information_schema，`JSON` 类型 MySQL 8 原生支持，预期兼容。上线后第 3 步要确认一下迁移日志。
- 服务器上是 Python 3.11 镜像：已用 3.11 虚拟环境（阿里云源）安装 requirements.txt 并跑通全部 105 个单元测试。box 上无法执行 docker build，镜像构建本身在 Jenkins 上首次验证。
