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
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 问数用的数据集目录：按用户可读的知识库，组装已就绪数据集的表、列、画像、描述、口径说明，随问题发给 Python。
 * 权限在这里收口：Python 只能看到这里给出的数据集。
 */
@Service
public class DatasetCatalog {

    private final DatasetMapper datasets;
    private final DatasetTableMapper tables;
    private final DatasetColumnMapper columns;
    private final DatasetBlockMapper blocks;
    private final ObjectMapper json = new ObjectMapper();

    public DatasetCatalog(DatasetMapper datasets, DatasetTableMapper tables, DatasetColumnMapper columns, DatasetBlockMapper blocks) {
        this.datasets = datasets;
        this.tables = tables;
        this.columns = columns;
        this.blocks = blocks;
    }

    private Object parse(String s) {
        if (s == null) return null;
        try {
            return json.readValue(s, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    public List<Map<String, Object>> forQuestion(List<Long> kbIds, List<Long> onlyIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper<Dataset> q = new QueryWrapper<Dataset>().in("kb_id", kbIds).eq("status", "ready").orderByDesc("id");
        if (onlyIds != null && !onlyIds.isEmpty()) {
            q.in("id", onlyIds);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dataset d : datasets.selectList(q)) {
            Map<String, String> notes = new HashMap<>();
            for (DatasetBlock b : blocks.selectList(new QueryWrapper<DatasetBlock>().eq("dataset_id", d.getId()).eq("kind", "note"))) {
                if (b.getNoteText() != null) {
                    notes.merge(b.getSheetName(), b.getNoteText(), (a, x) -> a + "\n" + x);
                }
            }
            List<Map<String, Object>> ts = new ArrayList<>();
            for (DatasetTable t : tables.selectList(new QueryWrapper<DatasetTable>().eq("dataset_id", d.getId()).orderByAsc("id"))) {
                List<Map<String, Object>> cs = new ArrayList<>();
                for (DatasetColumn c : columns.selectList(new QueryWrapper<DatasetColumn>().eq("table_id", t.getId()).orderByAsc("ordinal"))) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("physical_name", c.getPhysicalName());
                    m.put("display_name", c.getDisplayName());
                    m.put("data_type", c.getDataType());
                    m.put("is_percent", c.getIsPercent() != null && c.getIsPercent() == 1);
                    m.put("description", c.getDescription());
                    m.put("synonyms", c.getSynonyms());
                    m.put("profile", parse(c.getProfile()));
                    cs.add(m);
                }
                Map<String, Object> tm = new HashMap<>();
                tm.put("physical_name", t.getPhysicalName());
                tm.put("display_name", t.getDisplayName());
                tm.put("description", t.getDescription());
                tm.put("sheet_name", t.getSheetName());
                tm.put("row_count", t.getRowCount());
                tm.put("transform", parse(t.getTransform()));
                tm.put("notes", notes.get(t.getSheetName()));
                tm.put("columns", cs);
                ts.add(tm);
            }
            Map<String, Object> dm = new HashMap<>();
            dm.put("id", d.getId());
            dm.put("name", d.getName());
            dm.put("description", d.getDescription());
            dm.put("send_samples", d.getSendSamples() == null || d.getSendSamples() == 1);
            dm.put("tables", ts);
            out.add(dm);
        }
        return out;
    }
}
