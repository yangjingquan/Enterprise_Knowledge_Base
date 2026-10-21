package com.kb.config;

import com.kb.entity.Document;
import com.kb.mapper.DocumentMapper;
import com.kb.service.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 启动时把数据库中“文档 → 知识库”的对应关系同步到向量库元数据。
 * <p>
 * 升级前入库的文本块没有 kb_id，按知识库过滤检索时会查不到；这里统一补齐。
 * 每次启动都同步一遍（只改元数据，很快），也能修复因异常中断导致的不一致。
 * 在后台线程执行并带重试，Python 服务暂时不可用时不影响 Java 启动。
 */
@Component
@Order(3)
public class KbSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KbSyncRunner.class);

    /** 最多重试次数，每次间隔 10 秒 */
    private static final int MAX_RETRY = 30;

    private final DocumentMapper documents;
    private final IngestService ingest;

    public KbSyncRunner(DocumentMapper documents, IngestService ingest) {
        this.documents = documents;
        this.ingest = ingest;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::syncWithRetry, "kb-sync");
        t.setDaemon(true);
        t.start();
    }

    private void syncWithRetry() {
        Map<Long, Long> mapping = new HashMap<>();
        for (Document d : documents.selectList(null)) {
            if (d.getKbId() != null && "ready".equals(d.getStatus()) && d.getCleanupStatus() == null) {
                mapping.put(d.getId(), d.getKbId());
            }
        }
        for (int i = 1; i <= MAX_RETRY; i++) {
            try {
                ingest.syncKb(mapping);
                log.info("知识库元数据同步完成：{} 个文档", mapping.size());
                return;
            } catch (Exception e) {
                log.warn("知识库元数据同步失败（第 {} 次），10 秒后重试：{}", i, e.getMessage());
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
