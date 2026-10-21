package com.kb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据库结构自动升级。
 * <p>
 * MySQL 容器只在数据卷第一次创建时执行 sql/init.sql，已有数据卷不会再执行。
 * 为了让老环境升级后也能用新功能，应用启动时逐个检查新增的列，缺少就用 ALTER TABLE 补上。
 * 每一步都先查 information_schema，所以重复启动是安全的（幂等）。
 */
@Component
@Order(0)
public class SchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    /** 需要确保存在的列：表名、列名、列定义 */
    private static final List<String[]> COLUMNS = List.of(
            new String[]{"qa_history", "session_id", "VARCHAR(64) NULL COMMENT '会话ID'"},
            new String[]{"qa_history", "rewritten_question", "TEXT NULL COMMENT '多轮改写后的问题'"},
            new String[]{"qa_history", "username", "VARCHAR(50) NULL COMMENT '提问人'"},
            new String[]{"qa_history", "feedback", "TINYINT NULL COMMENT '1有用 -1没用'"},
            new String[]{"qa_history", "feedback_comment", "VARCHAR(500) NULL COMMENT '反馈原因'"},
            new String[]{"qa_history", "feedback_time", "DATETIME NULL COMMENT '反馈时间'"},
            new String[]{"document", "source_url", "VARCHAR(1000) NULL COMMENT '网页导入的原始网址'"},
            // ---- v0.3 知识库分组与权限、OCR ----
            new String[]{"document", "kb_id", "BIGINT NULL COMMENT '所属知识库'"},
            new String[]{"document", "ocr_pages", "INT DEFAULT 0 COMMENT 'OCR识别的页数'"},
            new String[]{"qa_history", "user_id", "BIGINT NULL COMMENT '提问人ID'"},
            new String[]{"qa_history", "kb_id", "BIGINT NULL COMMENT '限定的知识库'"},
            new String[]{"user", "display_name", "VARCHAR(50) NULL COMMENT '显示名称'"},
            new String[]{"user", "enabled", "TINYINT DEFAULT 1 COMMENT '1启用 0停用'"},
            // ---- 问数 ----
            new String[]{"qa_history", "mode", "VARCHAR(10) NULL COMMENT 'doc 文档问答 / data 数据分析 / mixed 混合'"},
            new String[]{"qa_history", "analysis", "JSON NULL COMMENT '数据分析快照'"},
            // ---- 文档去重、失败原因、删除清理 ----
            new String[]{"document", "file_hash", "VARCHAR(64) NULL COMMENT '文件SHA-256，同库去重'"},
            new String[]{"document", "error_msg", "VARCHAR(500) NULL COMMENT '处理失败原因'"},
            new String[]{"document", "cleanup_status", "VARCHAR(20) NULL COMMENT 'pending=向量待清理'"}
    );

    /** 默认知识库名称：升级前上传的文档都归入这里，所有登录用户可见 */
    public static final String DEFAULT_KB = "公共知识库";

    private final JdbcTemplate jdbc;

    public SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1. 新增的表（CREATE TABLE IF NOT EXISTS 本身就是幂等的）
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "name VARCHAR(100) NOT NULL UNIQUE, "
                + "description VARCHAR(500), "
                + "visibility VARCHAR(20) DEFAULT 'private', "
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS kb_member ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                + "kb_id BIGINT NOT NULL, "
                + "user_id BIGINT NOT NULL, "
                + "role VARCHAR(20) NOT NULL DEFAULT 'viewer', "
                + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_kb_user (kb_id, user_id))");

        // 1.1 结构化数据集（问数）：只存元数据，数据本身在 Python 服务的 DuckDB 文件里
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

        // 2. 新增的列
        for (String[] col : COLUMNS) {
            addColumnIfMissing(col[0], col[1], col[2]);
        }
        // 按会话查询历史时用到的索引
        addIndexIfMissing("qa_history", "idx_qa_session", "session_id");
        addIndexIfMissing("qa_history", "idx_qa_user", "user_id");
        addIndexIfMissing("document", "idx_doc_kb", "kb_id");
        addIndexIfMissing("document", "idx_doc_hash", "file_hash");

        // 3. 默认知识库：不存在就创建（公开），升级前没有知识库的文档全部归入
        jdbc.update("INSERT IGNORE INTO kb (name, description, visibility) VALUES (?, ?, 'public')",
                DEFAULT_KB, "全员可见的通用资料；系统升级前上传的文档自动归入此库");
        Long defaultKb = jdbc.queryForObject("SELECT id FROM kb WHERE name = ?", Long.class, DEFAULT_KB);
        int moved = jdbc.update("UPDATE document SET kb_id = ? WHERE kb_id IS NULL", defaultKb);
        if (moved > 0) {
            log.info("数据库升级：{} 个历史文档归入「{}」", moved, DEFAULT_KB);
        }
        // 老用户没有 enabled 值时视为启用
        jdbc.update("UPDATE user SET enabled = 1 WHERE enabled IS NULL");
        // 旧版本创建的管理员没有显示名称，补上默认值
        jdbc.update("UPDATE user SET display_name = ? WHERE username = ? AND (display_name IS NULL OR display_name = ?)", "系统管理员", "admin", "");
    }

    /** 列不存在时添加 */
    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        if (count != null && count == 0) {
            // 表名和列名来自上面的常量，不是用户输入，拼接 SQL 是安全的
            jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
            log.info("数据库升级：{} 表新增列 {}", table, column);
        }
    }

    /** 索引不存在时添加 */
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
}
