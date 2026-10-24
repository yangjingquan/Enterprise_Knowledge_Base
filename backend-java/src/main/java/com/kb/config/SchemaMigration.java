package com.kb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据库结构自动升级（幂等）。
 */
@Component
@Order(0)
public class SchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private static final List<String[]> COLUMNS = List.of(
            new String[]{"qa_history", "session_id", "VARCHAR(64) NULL COMMENT '会话ID'"},
            new String[]{"qa_history", "rewritten_question", "TEXT NULL COMMENT '多轮改写后的问题'"},
            new String[]{"qa_history", "username", "VARCHAR(50) NULL COMMENT '提问人'"},
            new String[]{"qa_history", "feedback", "TINYINT NULL COMMENT '1有用 -1没用'"},
            new String[]{"qa_history", "feedback_comment", "VARCHAR(500) NULL COMMENT '反馈原因'"},
            new String[]{"qa_history", "feedback_time", "DATETIME NULL COMMENT '反馈时间'"},
            new String[]{"document", "source_url", "VARCHAR(1000) NULL COMMENT '网页导入的原始网址'"},
            new String[]{"document", "kb_id", "BIGINT NULL COMMENT '所属知识库'"},
            new String[]{"document", "ocr_pages", "INT DEFAULT 0 COMMENT 'OCR识别的页数'"},
            new String[]{"qa_history", "user_id", "BIGINT NULL COMMENT '提问人ID'"},
            new String[]{"qa_history", "kb_id", "BIGINT NULL COMMENT '限定的知识库'"},
            new String[]{"user", "display_name", "VARCHAR(50) NULL COMMENT '显示名称'"},
            new String[]{"user", "enabled", "TINYINT DEFAULT 1 COMMENT '1启用 0停用'"},
            new String[]{"user", "broker_id", "BIGINT NULL COMMENT '所属客户'"},
            new String[]{"qa_history", "mode", "VARCHAR(10) NULL COMMENT 'doc 文档问答 / data 数据分析 / mixed 混合'"},
            new String[]{"qa_history", "analysis", "JSON NULL COMMENT '数据分析快照'"},
            new String[]{"document", "file_hash", "VARCHAR(64) NULL COMMENT '文件SHA-256，同库去重'"},
            new String[]{"document", "error_msg", "VARCHAR(500) NULL COMMENT '处理失败原因'"},
            new String[]{"document", "cleanup_status", "VARCHAR(20) NULL COMMENT 'pending=向量待清理'"},
            new String[]{"kb", "broker_id", "BIGINT NULL COMMENT '所属客户'"}
    );

    public static final String DEFAULT_KB = "公共知识库";

    private final JdbcTemplate jdbc;

    public SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS broker ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "name VARCHAR(100) NOT NULL, "
                + "code VARCHAR(64) NOT NULL, "
                + "enabled TINYINT DEFAULT 1, "
                + "token_version INT DEFAULT 0, "
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_broker_code (code))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS kb ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "name VARCHAR(100) NOT NULL, "
                + "description VARCHAR(500), "
                + "visibility VARCHAR(20) DEFAULT 'private', "
                + "broker_id BIGINT NULL, "
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_member ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "kb_id BIGINT NOT NULL, "
                + "user_id BIGINT NOT NULL, "
                + "role VARCHAR(20) NOT NULL DEFAULT 'viewer', "
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_kb_user (kb_id, user_id))");

        jdbc.execute("CREATE TABLE IF NOT EXISTS dataset ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, kb_id BIGINT NOT NULL, "
                + "name VARCHAR(200), file_name VARCHAR(255), file_path VARCHAR(500), file_type VARCHAR(10), "
                + "description VARCHAR(1000), status VARCHAR(20) DEFAULT 'pending', error_msg VARCHAR(1000), "
                + "table_count INT DEFAULT 0, row_count BIGINT DEFAULT 0, send_samples TINYINT DEFAULT 1, "
                + "user_id BIGINT, create_time DATETIME DEFAULT CURRENT_TIMESTAMP, INDEX idx_ds_kb (kb_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS dataset_block ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, dataset_id BIGINT NOT NULL, sheet_name VARCHAR(200), "
                + "range_ref VARCHAR(30), kind VARCHAR(10), detected_by VARCHAR(10), note_text TEXT NULL, "
                + "INDEX idx_db_ds (dataset_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS dataset_table ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, dataset_id BIGINT NOT NULL, block_id BIGINT NULL, "
                + "sheet_name VARCHAR(200), physical_name VARCHAR(64), display_name VARCHAR(200), "
                + "header_row INT, row_count BIGINT, description VARCHAR(1000), transform JSON NULL, "
                + "INDEX idx_dt_ds (dataset_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS dataset_column ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, table_id BIGINT NOT NULL, ordinal INT, "
                + "physical_name VARCHAR(64), display_name VARCHAR(200), data_type VARCHAR(20), "
                + "is_percent TINYINT DEFAULT 0, description VARCHAR(500), synonyms VARCHAR(500), profile JSON, "
                + "INDEX idx_dc_t (table_id))");

        for (String[] col : COLUMNS) {
            addColumnIfMissing(col[0], col[1], col[2]);
        }
        addIndexIfMissing("qa_history", "idx_qa_session", "session_id");
        addIndexIfMissing("qa_history", "idx_qa_user", "user_id");
        addIndexIfMissing("document", "idx_doc_kb", "kb_id");
        addIndexIfMissing("document", "idx_doc_hash", "file_hash");
        addIndexIfMissing("user", "idx_user_broker", "broker_id");
        addIndexIfMissing("kb", "idx_kb_broker", "broker_id");

        // 知识库名称改为客户内唯一：去掉全局 UNIQUE(name)
        dropIndexIfExists("kb", "name");
        // 复合唯一：同一客户内名称不重复（broker_id 为 NULL 的存量允许多条）
        addUniqueIndexIfMissing("kb", "uk_kb_broker_name", "broker_id, name");

        // 仅当尚不存在同名知识库时创建默认库（uk 改为客户内唯一后，INSERT IGNORE 对 NULL broker_id 不再幂等）
        Integer defaultKbCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb WHERE name = ?", Integer.class, DEFAULT_KB);
        if (defaultKbCount == null || defaultKbCount == 0) {
            jdbc.update("INSERT INTO kb (name, description, visibility) VALUES (?, ?, 'public')",
                    DEFAULT_KB, "全员可见的通用资料；系统升级前上传的文档自动归入此库");
            log.info("数据库升级：创建默认知识库「{}」", DEFAULT_KB);
        }
        Long defaultKb = null;
        try {
            defaultKb = jdbc.queryForObject(
                    "SELECT id FROM kb WHERE name = ? AND broker_id IS NULL ORDER BY id LIMIT 1",
                    Long.class, DEFAULT_KB);
        } catch (Exception ignored) {
            // none
        }
        if (defaultKb == null) {
            try {
                defaultKb = jdbc.queryForObject(
                        "SELECT id FROM kb WHERE name = ? ORDER BY id LIMIT 1",
                        Long.class, DEFAULT_KB);
            } catch (Exception ignored) {
                // none
            }
        }
        if (defaultKb != null) {
            int moved = jdbc.update("UPDATE document SET kb_id = ? WHERE kb_id IS NULL", defaultKb);
            if (moved > 0) {
                log.info("数据库升级：{} 个历史文档归入「{}」", moved, DEFAULT_KB);
            }
        }
        jdbc.update("UPDATE user SET enabled = 1 WHERE enabled IS NULL");
        jdbc.update("UPDATE user SET display_name = ? WHERE username = ? AND (display_name IS NULL OR display_name = ?)",
                "系统管理员", "admin", "");
        jdbc.update("UPDATE broker SET token_version = 0 WHERE token_version IS NULL");

        // 存量未归属数据迁入 DEMO 客户（幂等；生产关闭演示种子时仍会执行）
        migrateOrphansToDemo();
    }

    /**
     * 确保存在编码 DEMO 的客户，并将 broker_id 为空的用户（除 admin）与知识库划归该客户。
     * 若目标客户下已有同名知识库，先将孤儿库改名为「原名-迁移{id}」再归属，避免唯一约束失败。
     */
    private void migrateOrphansToDemo() {
        final String demoCode = "DEMO";
        Long demoId = null;
        try {
            demoId = jdbc.queryForObject(
                    "SELECT id FROM broker WHERE code = ? LIMIT 1", Long.class, demoCode);
        } catch (Exception ignored) {
            // empty
        }
        if (demoId == null) {
            jdbc.update(
                    "INSERT INTO broker (name, code, enabled, token_version, create_time, update_time) "
                            + "VALUES (?, ?, 1, 0, NOW(), NOW())",
                    "演示客户", demoCode);
            demoId = jdbc.queryForObject(
                    "SELECT id FROM broker WHERE code = ? LIMIT 1", Long.class, demoCode);
            log.info("数据迁移：创建客户 {}（id={}）", demoCode, demoId);
        }
        if (demoId == null) {
            log.warn("数据迁移：无法确保 DEMO 客户存在，跳过孤儿归属");
            return;
        }

        int usersMoved = jdbc.update(
                "UPDATE user SET broker_id = ? WHERE broker_id IS NULL AND username <> 'admin'",
                demoId);

        List<Map<String, Object>> orphans = jdbc.queryForList(
                "SELECT id, name FROM kb WHERE broker_id IS NULL ORDER BY id");
        int kbsMoved = 0;
        int kbsRenamed = 0;
        for (Map<String, Object> row : orphans) {
            Long kbId = ((Number) row.get("id")).longValue();
            String name = String.valueOf(row.get("name"));
            Integer clash = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kb WHERE broker_id = ? AND name = ?",
                    Integer.class, demoId, name);
            String assignName = name;
            if (clash != null && clash > 0) {
                assignName = name + "-迁移" + kbId;
                // 极端情况：改名后仍冲突则再拼后缀
                int n = 0;
                while (true) {
                    Integer again = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM kb WHERE broker_id = ? AND name = ?",
                            Integer.class, demoId, assignName);
                    if (again == null || again == 0) {
                        break;
                    }
                    n++;
                    assignName = name + "-迁移" + kbId + "-" + n;
                }
                jdbc.update("UPDATE kb SET name = ? WHERE id = ?", assignName, kbId);
                kbsRenamed++;
                log.info("数据迁移：知识库 id={} 原名「{}」与 DEMO 重名，改名为「{}」", kbId, name, assignName);
            }
            jdbc.update("UPDATE kb SET broker_id = ? WHERE id = ? AND broker_id IS NULL", demoId, kbId);
            kbsMoved++;
        }

        if (usersMoved > 0 || kbsMoved > 0) {
            log.info("数据迁移：已将 {} 个用户、{} 个知识库（其中 {} 个因重名改名）归属到客户 {}",
                    usersMoved, kbsMoved, kbsRenamed, demoCode);
        } else {
            log.info("数据迁移：无待归属的孤儿用户/知识库（DEMO id={}）", demoId);
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
            log.info("数据库升级：{} 表新增列 {}", table, column);
        }
    }

    private void addIndexIfMissing(String table, String index, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, index);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE `" + table + "` ADD INDEX `" + index + "` (`" + column + "`)");
            log.info("数据库升级：{} 表新增索引 {}", table, index);
        }
    }

    private void addUniqueIndexIfMissing(String table, String index, String columns) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, index);
        if (count != null && count == 0) {
            try {
                jdbc.execute("ALTER TABLE `" + table + "` ADD UNIQUE INDEX `" + index + "` (" + columns + ")");
                log.info("数据库升级：{} 表新增唯一索引 {}", table, index);
            } catch (Exception e) {
                log.warn("数据库升级：无法添加唯一索引 {}：{}（可能存在重复存量数据）", index, e.getMessage());
            }
        }
    }

    private void dropIndexIfExists(String table, String index) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, index);
        if (count != null && count > 0) {
            jdbc.execute("ALTER TABLE `" + table + "` DROP INDEX `" + index + "`");
            log.info("数据库升级：{} 表删除索引 {}", table, index);
        }
    }
}
