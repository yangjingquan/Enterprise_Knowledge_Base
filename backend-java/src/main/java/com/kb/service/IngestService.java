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

    public IngestService(DocumentMapper mapper, @Value("${kb.rag-url}") String ragUrl) {
        this.mapper = mapper;
        // 使用 SimpleClientHttpRequestFactory（HTTP/1.1）：
        // JDK 默认 HttpClient 会尝试 h2c 协议升级，uvicorn 不支持，会导致请求失败
        this.rag = RestClient.builder()
                .baseUrl(ragUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
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
        mapper.updateById(doc);

        try {
            // 调用 Python 的 /ingest 接口，返回切分出的文本块数量
            Map<?, ?> result = rag.post()
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
        }
        mapper.updateById(doc);
    }

    /**
     * 删除向量库中某个文档的全部文本块。失败只记录日志，不影响数据库记录的删除。
     */
    public void deleteVectors(Long id) {
        try {
            rag.delete()
                    .uri("/documents/{id}", id)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("删除向量失败 {}", id, e);
        }
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
