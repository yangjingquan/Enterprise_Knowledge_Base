"""数据集相关的内部接口（只被 Java 后端调用）"""
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from . import store
from .reader import DatasetError

router = APIRouter(prefix="/datasets")


class AnalyzeReq(BaseModel):
    dataset_id: int
    file_path: str
    file_type: str
    title: str = ""


class BlockReq(BaseModel):
    file_path: str
    file_type: str
    sheet: str
    range: str
    header_rows: list[int] | None = None
    block: dict | None = None


class IngestReq(BaseModel):
    dataset_id: int
    file_path: str
    file_type: str
    plan: dict | None = None    # 为空则使用已保存的方案


def _err(e: Exception):
    raise HTTPException(400, str(e))


@router.post("/analyze")
def analyze(req: AnalyzeReq):
    try:
        return store.analyze(req.dataset_id, req.file_path, req.file_type, req.title)
    except DatasetError as e:
        _err(e)


@router.get("/{dataset_id}/plan")
def plan(dataset_id: int):
    try:
        return store.load_plan(dataset_id)
    except DatasetError as e:
        _err(e)


@router.get("/{dataset_id}/grid")
def grid(dataset_id: int, sheet: str):
    try:
        p = store.load_plan(dataset_id)
    except DatasetError as e:
        _err(e)
    s = next((x for x in p["sheets"] if x["name"] == sheet), None)
    if s is None:
        raise HTTPException(404, "sheet 不存在")
    return {"name": s["name"], "rows": s["rows"], "cols": s["cols"], "grid": s["grid"]}


@router.post("/{dataset_id}/block")
def block(dataset_id: int, req: BlockReq):
    try:
        return store.refresh_block(dataset_id, req.file_path, req.file_type, req.sheet, req.range,
                                   req.header_rows, req.block)
    except DatasetError as e:
        _err(e)


@router.post("/ingest")
def ingest(req: IngestReq):
    try:
        plan = req.plan or store.load_plan(req.dataset_id)
        return store.ingest(req.dataset_id, req.file_path, req.file_type, plan)
    except DatasetError as e:
        _err(e)


@router.get("/{dataset_id}/preview")
def preview(dataset_id: int, table: str, limit: int = 50):
    try:
        return store.preview(dataset_id, table, limit)
    except DatasetError as e:
        _err(e)


@router.delete("/{dataset_id}")
def delete(dataset_id: int):
    store.delete(dataset_id)
    return {"ok": True}


class QueryReq(BaseModel):
    dataset_id: int
    sql: str
    limit: int = 10000


@router.post("/query")
def query(req: QueryReq):
    """导出用：校验并执行保存的 SQL（只读、限行、超时）"""
    from .nl2sql import query_for_export
    from .sqlguard import SqlRejected
    try:
        return query_for_export(req.dataset_id, req.sql, req.limit)
    except SqlRejected as e:
        raise HTTPException(400, str(e))
