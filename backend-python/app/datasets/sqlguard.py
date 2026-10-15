"""
大模型生成的 SQL 一律视为不可信（设计文档 7.3 / 15）：
- sqlglot 解析 AST：只允许单条 SELECT / WITH…SELECT / UNION；
- 不允许任何写操作、DDL、ATTACH / COPY / PRAGMA / SET / INSTALL / LOAD；
- 表名只能是本次选中的数据集表（或 SQL 内部定义的 CTE）；FROM 中不允许表函数；
- 禁用文件 / 网络 / 系统相关函数（read_csv、glob、getenv…）；
- 外层统一套 LIMIT。
执行层再加一道保险：DuckDB 只读打开、enable_external_access=false、锁定配置、超时中断。
"""
import re

import sqlglot
from sqlglot import exp

MAX_RETURN_ROWS = 500
MAX_EXPORT_ROWS = 10000

_FORBIDDEN_NODES = tuple(getattr(exp, n) for n in (
    "Insert", "Update", "Delete", "Create", "Drop", "Alter", "Command", "Pragma", "Copy", "Set", "Use",
    "Merge", "TruncateTable", "Attach", "Detach", "Install", "Load", "Describe", "Transaction", "Commit", "Rollback",
    "Into", "Export",
) if hasattr(exp, n))

_FORBIDDEN_FUNC = re.compile(
    r"^(READ_\w+|\w+_SCAN|SCAN_\w+|GLOB|GETENV|CURRENT_SETTING|QUERY|QUERY_TABLE|DUCKDB_\w+|PRAGMA_\w+|"
    r"SNIFF_CSV|PARQUET_\w+|ICEBERG_\w+|DELTA_\w+|HTTP\w*|LOAD\w*|INSTALL\w*|EXPORT\w*|WRITE\w*|TO_\w*FILE|"
    r"SQLITE_\w+|POSTGRES_\w+|MYSQL_\w+|SYSTEM|SHELL|EXEC\w*|SETSEED|NEXTVAL|CURRVAL|MD5_NUMBER_\w+)$", re.I)


class SqlRejected(Exception):
    pass


def _func_name(f: exp.Func) -> str:
    if isinstance(f, exp.Anonymous):
        return str(f.name)
    return f.sql_name()


def validate(sql: str, allowed_tables: set, limit: int = MAX_RETURN_ROWS, rename: dict | None = None) -> str:
    """
    校验并返回可执行的 SQL（外层加 LIMIT limit+1，用于判断是否截断）。
    rename：{提示词中的表名: 物理表名}，如 {"d5_t_1": "t_1"}，校验通过后替换。
    """
    sql = (sql or "").strip().rstrip(";").strip()
    if not sql:
        raise SqlRejected("SQL 为空")
    if len(sql) > 8000:
        raise SqlRejected("SQL 过长")
    try:
        stmts = [s for s in sqlglot.parse(sql, read="duckdb") if s is not None]
    except sqlglot.errors.ParseError as e:
        raise SqlRejected(f"SQL 语法错误：{str(e)[:300]}")
    if len(stmts) != 1:
        raise SqlRejected("只允许一条 SELECT 语句")
    tree = stmts[0]
    if not isinstance(tree, (exp.Select, exp.Union, exp.Intersect, exp.Except, exp.Subquery)):
        raise SqlRejected("只允许 SELECT 查询")
    for node in tree.walk():
        if isinstance(node, _FORBIDDEN_NODES):
            raise SqlRejected(f"不允许的语句：{type(node).__name__}")
        if isinstance(node, exp.Func):
            name = _func_name(node)
            if _FORBIDDEN_FUNC.match(name) or type(node).__name__.startswith("Read"):
                raise SqlRejected(f"不允许的函数：{name}")
    ctes = {c.alias_or_name.lower() for c in tree.find_all(exp.CTE)}
    allowed = {t.lower() for t in allowed_tables}
    for t in tree.find_all(exp.Table):
        if not isinstance(t.this, exp.Identifier):
            raise SqlRejected("FROM 中不允许使用表函数")
        if t.args.get("db") or t.args.get("catalog"):
            raise SqlRejected("不允许跨库访问")
        name = t.name.lower()
        if name not in allowed and name not in ctes:
            raise SqlRejected(f"不存在的表：{t.name}，只能使用 {', '.join(sorted(allowed_tables))}")
    if rename:
        low = {k.lower(): v for k, v in rename.items()}
        for t in list(tree.find_all(exp.Table)):
            name = t.name
            if name.lower() in low and name.lower() not in ctes:
                if not t.alias:
                    # 保留提示词中的表名作为别名，SQL 里 d5_t_1.c1 这样的列引用仍然有效
                    t.set("alias", exp.TableAlias(this=exp.to_identifier(name)))
                t.set("this", exp.to_identifier(low[name.lower()]))
    inner = tree.sql(dialect="duckdb")
    return f"SELECT * FROM ({inner}) AS _q LIMIT {int(limit) + 1}"
