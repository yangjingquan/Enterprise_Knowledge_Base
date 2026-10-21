package com.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.kb.entity.Document;
import com.kb.mapper.DocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档删除与向量清理。
 * <p>
 * 删除顺序：先删向量（Chroma + BM25），成功后再删数据库记录和原文件。
 * 向量删除失败（如 Python 服务暂不可用）时不删记录，而是把 cleanup_status 标记为 pending：
 * 文档从列表中隐藏，后台每分钟重试一次，直到向量删除成功再删除记录和文件。
 * 管理员还可以调用"清理孤立向量"，删除数据库中已没有对应文档的向量（历史遗留或异常中断造成）。
 */
@Service
public class DocumentCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentCleanupService.class);
    public static final String PENDING = "pending";

    private final DocumentMapper mapper;
    private final IngestService ingest;

    @Value("${kb.upload-dir}")
    private String uploadDir;

    public DocumentCleanupService(DocumentMapper mapper, IngestService ingest) {
        this.mapper = mapper;
        this.ingest = ingest;
    }

    /**
     * 删除文档。返回 true 表示已彻底删除；false 表示向量删除失败，已标记待清理（后台重试）。
     */
    public boolean remove(Document doc) {
        if (ingest.deleteVectors(doc.getId())) {
            purgeRowAndFile(doc);
            return true;
        }
        mapper.update(null, new UpdateWrapper<Document>().eq("id", doc.getId()).set("cleanup_status", PENDING));
        log.warn("文档 {} 的向量删除失败，已标记待清理，后台将自动重试", doc.getId());
        return false;
    }

    /** 后台重试：每 60 秒处理一次待清理的文档 */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void retryPending() {
        List<Document> pending = mapper.selectList(new QueryWrapper<Document>().eq("cleanup_status", PENDING));
        for (Document d : pending) {
            if (ingest.deleteVectors(d.getId())) {
                purgeRowAndFile(d);
                log.info("待清理文档 {} 已完成清理", d.getId());
            }
        }
    }

    /**
     * 清理孤立向量：向量库中存在、但数据库中没有对应文档（或文档已标记待清理）的向量。
     *
     * @param dryRun true 只统计不删除
     */
    public Map<String, Object> purgeOrphans(boolean dryRun) {
        Set<Long> inVectors = ingest.vectorDocumentIds();
        Set<Long> active = new HashSet<>();
        List<Document> pendingDocs = new ArrayList<>();
        for (Document d : mapper.selectList(new QueryWrapper<Document>().select("id", "file_path", "cleanup_status"))) {
            if (PENDING.equals(d.getCleanupStatus())) {
                pendingDocs.add(d);
            } else {
                active.add(d.getId());
            }
        }
        List<Long> orphans = inVectors.stream().filter(id -> !active.contains(id)).sorted().toList();
        List<Long> purged = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        if (!dryRun) {
            for (Long id : orphans) {
                (ingest.deleteVectors(id) ? purged : failed).add(id);
            }
            // 待清理文档的向量已删除（或本来就没有向量）：顺便删掉记录和文件
            for (Document d : pendingDocs) {
                if (!failed.contains(d.getId()) && (purged.contains(d.getId()) || ingest.deleteVectors(d.getId()))) {
                    purgeRowAndFile(mapper.selectById(d.getId()));
                }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dryRun", dryRun);
        r.put("vectorDocuments", inVectors.size());
        r.put("orphanDocumentIds", orphans);
        r.put("purged", purged);
        r.put("failed", failed);
        r.put("pendingCleanup", pendingDocs.stream().map(Document::getId).toList());
        log.info("清理孤立向量 dryRun={} orphans={} purged={} failed={}", dryRun, orphans, purged, failed);
        return r;
    }

    private void purgeRowAndFile(Document doc) {
        if (doc == null) {
            return;
        }
        mapper.deleteById(doc.getId());
        if (doc.getFilePath() == null) {
            return;
        }
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path path = Paths.get(doc.getFilePath()).toAbsolutePath().normalize();
            if (path.startsWith(root)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // 原文件删除失败不影响结果
        }
    }
}
