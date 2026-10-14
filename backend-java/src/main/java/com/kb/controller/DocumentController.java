package com.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.entity.Document;
import com.kb.mapper.DocumentMapper;
import com.kb.service.IngestService;
import com.kb.service.PermissionService;
import com.kb.service.PermissionService.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 文档管理接口：上传、网页导入、列表、查看原文、移动知识库、删除。
 * <p>
 * 权限：文档属于某个知识库。查看（列表、原文、上下文）需要该知识库的查看权限；
 * 上传、导入、删除需要编辑权限；无查看权限时一律返回 404，不透露文档是否存在。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /** 允许上传的文件类型白名单 */
    private static final Set<String> ALLOWED = Set.of("pdf", "docx", "xlsx", "pptx", "txt", "md");

    /** 各类型文件下载时的 Content-Type */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "txt", "text/plain;charset=UTF-8",
            "md", "text/markdown;charset=UTF-8",
            "url", "text/html;charset=UTF-8");

    private final DocumentMapper mapper;
    private final IngestService ingest;
    private final PermissionService perm;

    /** 上传文件的保存目录，来自配置项 kb.upload-dir */
    @Value("${kb.upload-dir}")
    private String uploadDir;

    public DocumentController(DocumentMapper mapper, IngestService ingest, PermissionService perm) {
        this.mapper = mapper;
        this.ingest = ingest;
        this.perm = perm;
    }

    /**
     * 上传文档：POST /api/documents/upload（multipart 表单，字段 file、kbId）。
     * 流程：校验权限和类型 → 保存文件 → 写入数据库（状态 pending）→ 异步触发解析与向量化 → 立即返回。
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("kbId") Long kbId) throws Exception {
        CurrentUser me = perm.current();
        if (!perm.canEdit(me, kbId)) {
            return ResponseEntity.status(403).body(Map.of("error", "没有该知识库的上传权限"));
        }

        // 1. 取扩展名并校验类型
        String name = Objects.requireNonNull(file.getOriginalFilename());
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!ALLOWED.contains(ext)) {
            return ResponseEntity.badRequest().body(Map.of("error", "不支持的文件类型，仅支持 " + String.join(" / ", ALLOWED)));
        }

        // 2. 用 UUID 重命名后保存，避免文件名冲突或含特殊字符
        Path dir = Paths.get(uploadDir).toAbsolutePath();
        Files.createDirectories(dir);
        Path target = dir.resolve(UUID.randomUUID() + "." + ext);
        file.transferTo(target);

        // 3. 保存文档记录
        Document doc = new Document();
        doc.setTitle(name.substring(0, dot));
        doc.setFileName(name);
        doc.setFilePath(target.toString());
        doc.setFileType(ext);
        doc.setStatus("pending");
        doc.setChunkCount(0);
        doc.setOcrPages(0);
        doc.setKbId(kbId);
        doc.setUserId(me.id());
        mapper.insert(doc);   // 插入后 doc.getId() 会被回填为自增 ID

        // 4. 异步处理（@Async 方法，在后台线程执行，这里不会等待）
        ingest.ingest(doc.getId());
        return ResponseEntity.ok(doc);
    }

    /**
     * 网页导入：POST /api/documents/url，请求体 {"url": "https://...", "kbId": 1}。
     * 先同步下载网页（便于把“网址打不开”之类的错误立即告诉用户），再异步解析入库。
     */
    @PostMapping("/url")
    public ResponseEntity<?> importUrl(@RequestBody Map<String, Object> req) {
        CurrentUser me = perm.current();
        Long kbId = req.get("kbId") instanceof Number n ? n.longValue() : null;
        if (!perm.canEdit(me, kbId)) {
            return ResponseEntity.status(403).body(Map.of("error", "没有该知识库的导入权限"));
        }
        String url = req.get("url") == null ? "" : String.valueOf(req.get("url")).trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入以 http:// 或 https:// 开头的网址"));
        }
        if (url.length() > 1000) {
            return ResponseEntity.badRequest().body(Map.of("error", "网址过长"));
        }

        Map<?, ?> fetched;
        try {
            fetched = ingest.fetchUrl(url);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "导入失败：" + e.getMessage()));
        }

        String title = String.valueOf(fetched.get("title"));
        Document doc = new Document();
        doc.setTitle(title);
        // 文件名用网页标题，回答引用来源时显示的就是标题
        doc.setFileName(title);
        doc.setFilePath(String.valueOf(fetched.get("file_path")));
        doc.setFileType("url");
        doc.setSourceUrl(String.valueOf(fetched.get("url")));
        doc.setStatus("pending");
        doc.setChunkCount(0);
        doc.setOcrPages(0);
        doc.setKbId(kbId);
        doc.setUserId(me.id());
        mapper.insert(doc);

        ingest.ingest(doc.getId());
        return ResponseEntity.ok(doc);
    }

    /** 文档列表：GET /api/documents?kbId=可选，只返回有查看权限的知识库中的文档 */
    @GetMapping
    public List<Document> list(@RequestParam(required = false) Long kbId) {
        CurrentUser me = perm.current();
        List<Long> readable = perm.readableKbIds(me);
        if (kbId != null) {
            readable = readable.contains(kbId) ? List.of(kbId) : List.of();
        }
        if (readable.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(new QueryWrapper<Document>().in("kb_id", readable).orderByAsc("id"));
    }

    /**
     * 移动文档到其他知识库：PUT /api/documents/{id}/kb，请求体 {"kbId": 2}。
     * 需要原知识库和目标知识库的编辑权限；只修改元数据，不重新向量化。
     */
    @PutMapping("/{id}/kb")
    public ResponseEntity<?> move(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        CurrentUser me = perm.current();
        Document doc = mapper.selectById(id);
        perm.requireEditable(me, doc);
        Long target = req.get("kbId") instanceof Number n ? n.longValue() : null;
        if (!perm.canEdit(me, target)) {
            return ResponseEntity.status(403).body(Map.of("error", "没有目标知识库的编辑权限"));
        }
        ingest.syncKb(Map.of(id, target));
        doc.setKbId(target);
        mapper.updateById(doc);
        return ResponseEntity.ok(doc);
    }

    /**
     * 查看原文件：GET /api/documents/{id}/file。
     * 需要登录（前端带 token 以 blob 方式获取后再交给 pdf.js 渲染）。
     * 只返回数据库里登记过的文件，并校验路径确实在上传目录内，防止通过篡改路径读取服务器其他文件。
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<?> file(@PathVariable Long id) {
        Document doc = mapper.selectById(id);
        perm.requireReadable(perm.current(), doc);
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path path = Paths.get(doc.getFilePath()).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return ResponseEntity.status(404).body(Map.of("error", "原文件不存在"));
        }

        String type = CONTENT_TYPES.getOrDefault(doc.getFileType(), MediaType.APPLICATION_OCTET_STREAM_VALUE);
        String downloadName = "url".equals(doc.getFileType()) ? doc.getTitle() + ".html" : doc.getFileName();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                // inline：浏览器可直接预览；文件名按 RFC 5987 编码，中文不会乱码
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(downloadName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
                .body(new FileSystemResource(path));
    }

    /**
     * 查看引用片段的上下文：GET /api/documents/{id}/context?chunkIndex=5&window=2。
     * 返回该文本块及前后各 window 块，用于 Excel、PPT、网页等无法按页渲染的格式。
     */
    @GetMapping("/{id}/context")
    public List<?> context(@PathVariable Long id,
                           @RequestParam int chunkIndex,
                           @RequestParam(defaultValue = "2") int window) {
        perm.requireReadable(perm.current(), mapper.selectById(id));
        return ingest.context(id, chunkIndex, Math.max(0, Math.min(window, 5)));
    }

    /** 删除文档：DELETE /api/documents/{id}，同时删除向量库中的文本块和服务器上的原文件 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Document doc = mapper.selectById(id);
        perm.requireEditable(perm.current(), doc);
        ingest.deleteVectors(id);
        mapper.deleteById(id);
        if (doc != null && doc.getFilePath() != null) {
            try {
                Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
                Path path = Paths.get(doc.getFilePath()).toAbsolutePath().normalize();
                if (path.startsWith(root)) {
                    Files.deleteIfExists(path);
                }
            } catch (Exception ignored) {
                // 原文件删除失败不影响接口结果
            }
        }
        return Map.of("ok", true);
    }
}
