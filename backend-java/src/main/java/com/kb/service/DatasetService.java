package com.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.entity.Dataset;
import com.kb.entity.DatasetBlock;
import com.kb.entity.DatasetColumn;
import com.kb.entity.DatasetTable;
import com.kb.mapper.DatasetBlockMapper;
import com.kb.mapper.DatasetColumnMapper;
import com.kb.mapper.DatasetMapper;
import com.kb.mapper.DatasetTableMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集导入服务：调用 Python 生成导入方案（analyze）、按确认后的方案写入 DuckDB（ingest），
 * 并把表、列元数据保存到 MySQL。状态：pending → parsing → awaiting_confirm → importing → ready / failed。
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final DatasetMapper datasets;
    private final DatasetTableMapper tables;
    private final DatasetColumnMapper columns;
    private final DatasetBlockMapper blocks;
    private final TransactionTemplate tx;
    private final RestClient rag;
    private final RestClient ragLong;
    private final ObjectMapper json = new ObjectMapper();

    public DatasetService(DatasetMapper datasets, DatasetTableMapper tables, DatasetColumnMapper columns,
                          DatasetBlockMapper blocks, TransactionTemplate tx, @Value("${kb.rag-url}") String ragUrl) {
        this.datasets = datasets;
        this.tables = tables;
        this.columns = columns;
        this.blocks = blocks;
        this.tx = tx;
        this.rag = RagHttp.normal(ragUrl);
        // 解析 / 重新识别数据块 / 导入可能处理大文件，用长超时
        this.ragLong = RagHttp.longRunning(ragUrl);
    }

    /** 把 Python 返回的 {"detail": "..."} 错误转成可读的提示 */
    public static String errorOf(Exception e) {
        if (e instanceof RestClientResponseException r) {
            try {
                Object d = new ObjectMapper().readValue(r.getResponseBodyAsString(), Map.class).get("detail");
                if (d != null) {
                    return String.valueOf(d);
                }
            } catch (Exception ignored) {
                // 非 JSON
            }
        }
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.substring(0, Math.min(m.length(), 500));
    }

    private void fail(Dataset ds, Exception e) {
        log.error("数据集{}处理失败", ds.getId(), e);
        ds.setStatus("failed");
        ds.setErrorMsg(errorOf(e));
        datasets.updateById(ds);
    }

    /** 上传后异步分析，生成导入方案 */
    @Async
    public void analyze(Long id) {
        Dataset ds = datasets.selectById(id);
        if (ds == null) {
            return;
        }
        ds.setStatus("parsing");
        datasets.updateById(ds);
        try {
            ragLong.post().uri("/datasets/analyze")
                    .body(Map.of("dataset_id", id, "file_path", ds.getFilePath(),
                            "file_type", ds.getFileType(), "title", ds.getName()))
                    .retrieve().body(Map.class);
            ds.setStatus("awaiting_confirm");
            ds.setErrorMsg(null);
            datasets.updateById(ds);
        } catch (Exception e) {
            fail(ds, e);
        }
    }

    public Map<?, ?> plan(Long id) {
        return rag.get().uri("/datasets/{id}/plan", id).retrieve().body(Map.class);
    }

    public Map<?, ?> grid(Long id, String sheet) {
        return rag.get().uri(UriComponentsBuilder.fromPath("/datasets/{id}/grid").queryParam("sheet", sheet)
                .buildAndExpand(id).toUriString()).retrieve().body(Map.class);
    }

    public Map<?, ?> refreshBlock(Dataset ds, Map<String, Object> req) {
        Map<String, Object> body = new HashMap<>(req);
        body.put("file_path", ds.getFilePath());
        body.put("file_type", ds.getFileType());
        return ragLong.post().uri("/datasets/{id}/block", ds.getId()).body(body).retrieve().body(Map.class);
    }

    public Map<?, ?> preview(Long id, String table, int limit) {
        return rag.get().uri(UriComponentsBuilder.fromPath("/datasets/{id}/preview")
                .queryParam("table", table).queryParam("limit", limit)
                .buildAndExpand(id).toUriString()).retrieve().body(Map.class);
    }

    /** 按确认后的方案异步导入 */
    @Async
    public void ingest(Long id, Map<String, Object> plan) {
        Dataset ds = datasets.selectById(id);
        if (ds == null) {
            return;
        }
        try {
            Map<?, ?> result = ragLong.post().uri("/datasets/ingest")
                    .body(Map.of("dataset_id", id, "file_path", ds.getFilePath(),
                            "file_type", ds.getFileType(), "plan", plan))
                    .retrieve().body(Map.class);
            tx.executeWithoutResult(s -> saveMeta(ds, plan, result));
        } catch (Exception e) {
            fail(ds, e);
        }
    }

    /** 保存块、表、列元数据（重新导入时先清掉旧的；保留用户写过的列描述和同义词） */
    @SuppressWarnings("unchecked")
    private void saveMeta(Dataset ds, Map<String, Object> plan, Map<?, ?> result) {
        Map<String, String[]> oldDesc = new HashMap<>();
        for (DatasetTable t : tables.selectList(new QueryWrapper<DatasetTable>().eq("dataset_id", ds.getId()))) {
            for (DatasetColumn c : columns.selectList(new QueryWrapper<DatasetColumn>().eq("table_id", t.getId()))) {
                oldDesc.put(t.getSheetName() + "|" + c.getDisplayName(), new String[]{c.getDescription(), c.getSynonyms()});
            }
            columns.delete(new QueryWrapper<DatasetColumn>().eq("table_id", t.getId()));
        }
        tables.delete(new QueryWrapper<DatasetTable>().eq("dataset_id", ds.getId()));
        blocks.delete(new QueryWrapper<DatasetBlock>().eq("dataset_id", ds.getId()));

        Map<String, Long> blockIds = new HashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) plan.get("sheets")) {
            for (Map<String, Object> b : (List<Map<String, Object>>) s.getOrDefault("blocks", List.of())) {
                DatasetBlock blk = new DatasetBlock();
                blk.setDatasetId(ds.getId());
                blk.setSheetName((String) s.get("name"));
                blk.setRangeRef((String) b.get("range"));
                blk.setKind((String) b.getOrDefault("kind", "data"));
                blk.setDetectedBy(Boolean.TRUE.equals(b.get("manual")) ? "manual" : "auto");
                Object note = b.get("note_text");
                blk.setNoteText(note == null ? null : String.valueOf(note));
                blocks.insert(blk);
                blockIds.put(String.valueOf(b.get("id")), blk.getId());
            }
        }
        for (Map<String, Object> t : (List<Map<String, Object>>) result.get("tables")) {
            DatasetTable dt = new DatasetTable();
            dt.setDatasetId(ds.getId());
            dt.setBlockId(blockIds.get(String.valueOf(t.get("block_id"))));
            dt.setSheetName((String) t.get("sheet_name"));
            dt.setPhysicalName((String) t.get("physical_name"));
            dt.setDisplayName((String) t.get("display_name"));
            dt.setHeaderRow(t.get("header_row") instanceof Number n ? n.intValue() : null);
            dt.setRowCount(((Number) t.get("row_count")).longValue());
            dt.setTransform(toJson(t.get("transform")));
            tables.insert(dt);
            for (Map<String, Object> c : (List<Map<String, Object>>) t.get("columns")) {
                DatasetColumn col = new DatasetColumn();
                col.setTableId(dt.getId());
                col.setOrdinal(((Number) c.get("ordinal")).intValue());
                col.setPhysicalName((String) c.get("physical_name"));
                col.setDisplayName((String) c.get("display_name"));
                col.setDataType((String) c.get("data_type"));
                col.setIsPercent(Boolean.TRUE.equals(c.get("is_percent")) ? 1 : 0);
                col.setProfile(toJson(c.get("profile")));
                String[] old = oldDesc.get(dt.getSheetName() + "|" + col.getDisplayName());
                if (old != null) {
                    col.setDescription(old[0]);
                    col.setSynonyms(old[1]);
                }
                columns.insert(col);
            }
        }
        ds.setTableCount(((Number) result.get("table_count")).intValue());
        ds.setRowCount(((Number) result.get("row_count")).longValue());
        ds.setStatus("ready");
        ds.setErrorMsg(null);
        datasets.updateById(ds);
    }

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除元数据和 Python 端的 DuckDB 文件、方案文件 */
    public void delete(Dataset ds) {
        try {
            rag.delete().uri("/datasets/{id}", ds.getId()).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("删除数据集{}的数据文件失败：{}", ds.getId(), e.getMessage());
        }
        tx.executeWithoutResult(s -> {
            for (DatasetTable t : tables.selectList(new QueryWrapper<DatasetTable>().eq("dataset_id", ds.getId()))) {
                columns.delete(new QueryWrapper<DatasetColumn>().eq("table_id", t.getId()));
            }
            tables.delete(new QueryWrapper<DatasetTable>().eq("dataset_id", ds.getId()));
            blocks.delete(new QueryWrapper<DatasetBlock>().eq("dataset_id", ds.getId()));
            datasets.deleteById(ds.getId());
        });
    }
}
