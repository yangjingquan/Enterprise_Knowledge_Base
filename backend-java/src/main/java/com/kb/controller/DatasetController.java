package com.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.entity.Dataset;
import com.kb.entity.DatasetBlock;
import com.kb.entity.DatasetColumn;
import com.kb.entity.DatasetTable;
import com.kb.mapper.DatasetBlockMapper;
import com.kb.mapper.DatasetColumnMapper;
import com.kb.mapper.DatasetMapper;
import com.kb.mapper.DatasetTableMapper;
import com.kb.service.DatasetService;
import com.kb.service.PermissionService;
import com.kb.service.PermissionService.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 数据集（问数）接口。权限与知识库一致：查看/预览需要知识库查看权限，上传、确认导入、编辑、删除需要编辑权限。
 * 无查看权限时返回 404，不透露数据集是否存在。
 */
@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private static final Set<String> ALLOWED = Set.of("csv", "xlsx", "xls");
    private static final Set<String> TYPES = Set.of("DATE", "TIMESTAMP", "BIGINT", "DOUBLE", "VARCHAR");
    /** 每个知识库的数据集数量上限 */
    private static final int MAX_PER_KB = 50;

    private final DatasetMapper datasets;
    private final DatasetTableMapper tables;
    private final DatasetColumnMapper columns;
    private final DatasetService service;
    private final DatasetBlockMapper blocks;
    private final PermissionService perm;

    @Value("${kb.upload-dir}")
    private String uploadDir;

    public DatasetController(DatasetMapper datasets, DatasetTableMapper tables, DatasetColumnMapper columns,
                             DatasetService service, PermissionService perm, DatasetBlockMapper blocks) {
        this.blocks = blocks;
        this.datasets = datasets;
        this.tables = tables;
        this.columns = columns;
        this.service = service;
        this.perm = perm;
    }

    private Dataset readable(Long id) {
        Dataset ds = datasets.selectById(id);
        if (ds == null || !perm.canRead(perm.current(), ds.getKbId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据集不存在或无权访问");
        }
        return ds;
    }

    private Dataset editable(Long id) {
        Dataset ds = readable(id);
        if (!perm.canEdit(perm.current(), ds.getKbId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有该知识库的编辑权限");
        }
        return ds;
    }

    /** 文件头魔数校验：xlsx 是 zip（PK），xls 是 OLE2；防止改扩展名上传其他文件 */
    private static boolean magicOk(MultipartFile file, String ext) throws Exception {
        byte[] head = new byte[8];
        int n;
        try (InputStream in = file.getInputStream()) {
            n = in.readNBytes(head, 0, 8);
        }
        return switch (ext) {
            case "xlsx" -> n >= 2 && head[0] == 'P' && head[1] == 'K';
            case "xls" -> n >= 4 && (head[0] & 0xff) == 0xD0 && (head[1] & 0xff) == 0xCF
                    && (head[2] & 0xff) == 0x11 && (head[3] & 0xff) == 0xE0;
            default -> true;
        };
    }

    /** 上传：POST /api/datasets/upload（multipart：file、kbId），异步生成导入方案 */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file, @RequestParam("kbId") Long kbId) throws Exception {
        CurrentUser me = perm.current();
        if (!perm.canEdit(me, kbId)) {
            return ResponseEntity.status(403).body(Map.of("error", "没有该知识库的上传权限"));
        }
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "data");
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!ALLOWED.contains(ext)) {
            return ResponseEntity.badRequest().body(Map.of("error", "数据集仅支持 xlsx / xls / csv 文件"));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
        }
        if (!magicOk(file, ext)) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件内容与扩展名不符，可能已加密或已损坏"));
        }
        if (datasets.selectCount(new QueryWrapper<Dataset>().eq("kb_id", kbId)) >= MAX_PER_KB) {
            return ResponseEntity.badRequest().body(Map.of("error", "每个知识库最多 " + MAX_PER_KB + " 个数据集"));
        }
        Path dir = Paths.get(uploadDir, "datasets").toAbsolutePath();
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + "." + ext);
        file.transferTo(target);

        Dataset ds = new Dataset();
        ds.setKbId(kbId);
        ds.setName(dot > 0 ? name.substring(0, dot) : name);
        ds.setFileName(name);
        ds.setFilePath(target.toString());
        ds.setFileType(ext);
        ds.setStatus("pending");
        ds.setTableCount(0);
        ds.setRowCount(0L);
        ds.setSendSamples(1);
        ds.setUserId(me.id());
        datasets.insert(ds);
        service.analyze(ds.getId());
        return ResponseEntity.ok(ds);
    }

    /** 列表：GET /api/datasets?kbId=可选 */
    @GetMapping
    public List<Dataset> list(@RequestParam(required = false) Long kbId) {
        List<Long> readable = perm.readableKbIds(perm.current());
        if (kbId != null) {
            readable = readable.contains(kbId) ? List.of(kbId) : List.of();
        }
        if (readable.isEmpty()) {
            return List.of();
        }
        return datasets.selectList(new QueryWrapper<Dataset>().in("kb_id", readable).orderByDesc("id"));
    }

    /** 详情：数据集 + 表 + 列 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Dataset ds = readable(id);
        List<Map<String, Object>> ts = new ArrayList<>();
        for (DatasetTable t : tables.selectList(new QueryWrapper<DatasetTable>().eq("dataset_id", id).orderByAsc("id"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", t);
            m.put("columns", columns.selectList(new QueryWrapper<DatasetColumn>().eq("table_id", t.getId()).orderByAsc("ordinal")));
            ts.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", ds);
        out.put("tables", ts);
        // sheet 中识别出的备注/口径说明（作为大模型理解数据的上下文，不进入文档问答）
        out.put("notes", blocks.selectList(new QueryWrapper<DatasetBlock>().eq("dataset_id", id).eq("kind", "note").orderByAsc("id")));
        out.put("canEdit", perm.canEdit(perm.current(), ds.getKbId()));
        return out;
    }

    /** 导入方案：GET /api/datasets/{id}/plan */
    @GetMapping("/{id}/plan")
    public ResponseEntity<?> plan(@PathVariable Long id) {
        editable(id);
        try {
            return ResponseEntity.ok(service.plan(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", DatasetService.errorOf(e)));
        }
    }

    /** 原始单元格：GET /api/datasets/{id}/sheets/{sheet}/grid */
    @GetMapping("/{id}/sheets/{sheet}/grid")
    public ResponseEntity<?> grid(@PathVariable Long id, @PathVariable String sheet) {
        editable(id);
        try {
            return ResponseEntity.ok(service.grid(id, sheet));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", DatasetService.errorOf(e)));
        }
    }

    /** 修改范围/表头后重新识别一个块：POST /api/datasets/{id}/plan/block {sheet, range, header_rows, block} */
    @PostMapping("/{id}/plan/block")
    public ResponseEntity<?> refreshBlock(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        Dataset ds = editable(id);
        try {
            return ResponseEntity.ok(service.refreshBlock(ds, req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", DatasetService.errorOf(e)));
        }
    }

    /** 确认导入：PUT /api/datasets/{id}/plan（请求体为完整方案），异步写入 */
    @PutMapping("/{id}/plan")
    public ResponseEntity<?> confirm(@PathVariable Long id, @RequestBody Map<String, Object> plan) {
        Dataset ds = editable(id);
        if (!Set.of("awaiting_confirm", "ready", "failed").contains(ds.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "数据集正在处理中，请稍后"));
        }
        if (!(plan.get("sheets") instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of("error", "导入方案格式不正确"));
        }
        ds.setStatus("importing");
        ds.setErrorMsg(null);
        datasets.updateById(ds);
        service.ingest(id, plan);
        return ResponseEntity.ok(ds);
    }

    /** 预览：GET /api/datasets/{id}/preview?table=t_1&limit=50 */
    @GetMapping("/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable Long id, @RequestParam String table,
                                     @RequestParam(defaultValue = "50") int limit) {
        readable(id);
        try {
            return ResponseEntity.ok(service.preview(id, table, Math.max(1, Math.min(limit, 200))));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", DatasetService.errorOf(e)));
        }
    }

    /**
     * 修改数据集说明、表名、列中文名/描述/同义词：PUT /api/datasets/{id}/columns
     * 请求体 {"description": "...", "tables": [{"id": 1, "displayName": "...", "description": "...",
     *          "columns": [{"id": 3, "displayName": "...", "description": "...", "synonyms": "..."}]}]}
     * 改类型或表头需要重新确认导入方案。
     */
    @PutMapping("/{id}/columns")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> updateColumns(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        Dataset ds = editable(id);
        if (req.containsKey("description")) {
            ds.setDescription(cut(req.get("description"), 1000));
        }
        if (req.containsKey("name") && req.get("name") != null && !String.valueOf(req.get("name")).isBlank()) {
            ds.setName(cut(req.get("name"), 200));
        }
        datasets.updateById(ds);
        for (Map<String, Object> t : (List<Map<String, Object>>) req.getOrDefault("tables", List.of())) {
            DatasetTable dt = tables.selectById(((Number) t.get("id")).longValue());
            if (dt == null || !dt.getDatasetId().equals(id)) {
                continue;
            }
            if (t.containsKey("displayName")) dt.setDisplayName(cut(t.get("displayName"), 200));
            if (t.containsKey("description")) dt.setDescription(cut(t.get("description"), 1000));
            tables.updateById(dt);
            for (Map<String, Object> c : (List<Map<String, Object>>) t.getOrDefault("columns", List.of())) {
                DatasetColumn col = columns.selectById(((Number) c.get("id")).longValue());
                if (col == null || !col.getTableId().equals(dt.getId())) {
                    continue;
                }
                if (c.containsKey("displayName") && c.get("displayName") != null
                        && !String.valueOf(c.get("displayName")).isBlank()) {
                    col.setDisplayName(cut(c.get("displayName"), 200));
                }
                if (c.containsKey("description")) col.setDescription(cut(c.get("description"), 500));
                if (c.containsKey("synonyms")) col.setSynonyms(cut(c.get("synonyms"), 500));
                columns.updateById(col);
            }
        }
        return ResponseEntity.ok(detail(id));
    }

    private static String cut(Object v, int max) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 删除：DELETE /api/datasets/{id} */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Dataset ds = editable(id);
        service.delete(ds);
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path path = Paths.get(ds.getFilePath()).toAbsolutePath().normalize();
            if (path.startsWith(root)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // 原文件删除失败不影响结果
        }
        return Map.of("ok", true);
    }

    /** 允许的列类型（给前端下拉框用） */
    @GetMapping("/types")
    public Set<String> types() {
        return TYPES;
    }
}
