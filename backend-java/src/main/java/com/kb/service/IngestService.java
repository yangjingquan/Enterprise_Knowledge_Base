package com.kb.service;

import com.kb.entity.Document;
import com.kb.mapper.DocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 文档入库服务：调用 Python RAG 服务完成“解析 → 切分 → 向量化 → 存入 Chroma”，
 * 并维护文档状态 pending → processing → ready / failed。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final DocumentMapper mapper;

    /** 调用 Python 服务的 HTTP 客户端 */
    private final RestClient rag;
    /** 解析入库用的长超时客户端 */
    private final RestClient ragLong;

    public IngestService(DocumentMapper mapper, @Value("${kb.rag-url}") String ragUrl) {
        this.mapper = mapper;
        // HTTP/1.1 客户端，连接超时 5 秒；普通接口读取 60 秒，解析入库 300 秒（见 RagHttp）
        this.rag = RagHttp.normal(ragUrl);
        this.ragLong = RagHttp.longRunning(ragUrl);
    }

    /**
     * 异步处理文档。@Async 让该方法在独立线程中运行，上传接口无需等待向量化完成。
     *
     * @param id 文档 ID
     */
    @Async
    public void ingest(Long id) {
        Document doc = mapper.selectById(id);
        if (doc == null) {
            return;
        }

        // 标记为处理中，前端列表会显示“处理中”
        doc.setStatus("processing");
        doc.setErrorMsg(null);
        mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Document>()
                .eq("id", id).set("status", "processing").set("error_msg", null));

        try {
            // 调用 Python 的 /ingest 接口，返回切分出的文本块数量
            Map<?, ?> result = ragLong.post()
                    .uri("/ingest")
                    .body(Map.of(
                            "document_id", id,
                            "file_path", doc.getFilePath(),
                            "file_name", doc.getFileName(),
                            "kb_id", doc.getKbId() == null ? 0 : doc.getKbId()))
                    .retrieve()
                    .body(Map.class);

            doc.setChunkCount(((Number) result.get("chunk_count")).intValue());
            // 扫描件经 OCR 识别的页数，前端据此显示“OCR”标记
            Object ocr = result.get("ocr_pages");
            doc.setOcrPages(ocr instanceof Number n ? n.intValue() : 0);
            doc.setStatus("ready");
        } catch (Exception e) {
            log.error("文档{}处理失败", id, e);
            doc.setStatus("failed");
            doc.setErrorMsg(failureReason(e));
        }
        if (mapper.selectById(id) == null) {
            return;   // 处理期间文档已被删除，不再写回
        }
        mapper.updateById(doc);
    }

    /** 把调用 Python 的异常转成用户能看懂的失败原因（写入 document.error_msg，最长 500 字） */
    static String failureReason(Exception e) {
        String msg;
        if (e instanceof org.springframework.web.client.RestClientResponseException r) {
            msg = r.getResponseBodyAsString();
            try {
                Object d = new com.fasterxml.jackson.databind.ObjectMapper().readValue(msg, Map.class).get("detail");
                if (d != null) {
                    msg = String.valueOf(d);
                }
            } catch (Exception ignored) {
                // 不是 JSON 就用原文
            }
            if (msg == null || msg.isBlank()) {
                msg = "解析服务返回错误 " + r.getStatusCode().value();
            }
        } else if (e instanceof org.springframework.web.client.ResourceAccessException) {
            msg = "解析服务不可用或处理超时（300 秒），请稍后重试";
        } else {
            msg = "处理失败：" + e.getClass().getSimpleName();
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    /**
     * 删除向量库中某个文档的全部文本块（Chroma + BM25）。
     * 成功返回 true；失败返回 false 并记录日志，由调用方决定是否标记"待清理"稍后重试。
     */
    public boolean deleteVectors(Long id) {
        try {
            rag.delete()
                    .uri("/documents/{id}", id)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("删除向量失败 {}：{}", id, e.getMessage());
            return false;
        }
    }

    /** 向量库中出现过的全部文档 ID（用于清理孤立向量） */
    public java.util.Set<Long> vectorDocumentIds() {
        Map<?, ?> r = rag.get().uri("/admin/vector-docs").retrieve().body(Map.class);
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        if (r != null && r.get("document_ids") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                }
            }
        }
        return ids;
    }

    /**
     * 网页导入第一步：让 Python 下载网页并保存为 HTML 文件。
     * 返回 {"title", "file_path", "url"}；网址无效或下载失败时抛出 IllegalArgumentException（消息可直接给用户看）。
     */
    public Map<?, ?> fetchUrl(String url) {
        try {
            return rag.post()
                    .uri("/fetch-url")
                    .body(Map.of("url", url))
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException e) {
            // Python 返回 400 时，响应体形如 {"detail": "原因"}
            String reason = e.getResponseBodyAsString();
            try {
                Map<?, ?> err = new com.fasterxml.jackson.databind.ObjectMapper().readValue(reason, Map.class);
                reason = String.valueOf(err.get("detail"));
            } catch (Exception ignored) {
                // 不是 JSON 就用原文
            }
            throw new IllegalArgumentException(reason);
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.warn("fetch-url 5xx: {}", e.getMessage());
            throw new IllegalArgumentException("网页解析服务出错，请稍后重试");
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn("fetch-url unreachable: {}", e.getMessage());
            throw new IllegalArgumentException("网页下载超时或解析服务暂不可用，请稍后重试");
        }
    }

    /**
     * 把文档与知识库的对应关系同步到 Python（Chroma 和 BM25 的 kb_id 元数据）。
     * 文档移动知识库、或升级后老文档归入默认知识库时调用。失败抛异常由调用方处理。
     */
    public void syncKb(Map<Long, Long> mapping) {
        if (mapping.isEmpty()) {
            return;
        }
        rag.post()
                .uri("/kb-sync")
                .body(Map.of("mapping", mapping))
                .retrieve()
                .toBodilessEntity();
    }

    /** 取某个文本块及其前后文，用于前端查看原文 */
    public List<?> context(Long documentId, int chunkIndex, int window) {
        return rag.get()
                .uri("/documents/{id}/context?chunk_index={c}&window={w}", documentId, chunkIndex, window)
                .retrieve()
                .body(List.class);
    }
}
