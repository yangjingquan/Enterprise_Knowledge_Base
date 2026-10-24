-- 首次创建 MySQL 数据卷时执行。已有数据卷的升级由 Java 启动时的 SchemaMigration 自动补列。
CREATE DATABASE IF NOT EXISTS kb DEFAULT CHARSET utf8mb4;
USE kb;

CREATE TABLE IF NOT EXISTS broker (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(64) NOT NULL,
    enabled TINYINT DEFAULT 1,
    token_version INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_broker_code (code)
);

CREATE TABLE IF NOT EXISTS user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) DEFAULT 'user' COMMENT 'admin 超管 / broker_admin 客户管理员 / user 普通用户',
    display_name VARCHAR(50) NULL COMMENT '显示名称',
    broker_id BIGINT NULL COMMENT '所属客户，超管为空',
    enabled TINYINT DEFAULT 1 COMMENT '1启用 0停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_broker (broker_id)
);

CREATE TABLE IF NOT EXISTS kb (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    visibility VARCHAR(20) DEFAULT 'private',
    broker_id BIGINT NULL COMMENT '所属客户',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_kb_broker (broker_id),
    UNIQUE KEY uk_kb_broker_name (broker_id, name)
);

CREATE TABLE IF NOT EXISTS kb_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'viewer',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_user (kb_id, user_id)
);

CREATE TABLE IF NOT EXISTS document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200),
    file_name VARCHAR(255),
    file_path VARCHAR(500),
    file_type VARCHAR(20),
    source_url VARCHAR(1000) NULL COMMENT '网页导入的原始网址',
    kb_id BIGINT NULL COMMENT '所属知识库',
    ocr_pages INT DEFAULT 0 COMMENT 'OCR识别的页数',
    status VARCHAR(20) DEFAULT 'pending',
    chunk_count INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    user_id BIGINT,
    INDEX idx_doc_kb (kb_id)
);

CREATE TABLE IF NOT EXISTS qa_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT,
    session_id VARCHAR(64) NULL COMMENT '会话ID',
    question TEXT,
    rewritten_question TEXT NULL COMMENT '多轮改写后的问题',
    answer TEXT,
    sources TEXT,
    username VARCHAR(50) NULL COMMENT '提问人',
    user_id BIGINT NULL COMMENT '提问人ID',
    kb_id BIGINT NULL COMMENT '限定的知识库',
    feedback TINYINT NULL COMMENT '1有用 -1没用',
    feedback_comment VARCHAR(500) NULL COMMENT '反馈原因',
    feedback_time DATETIME NULL COMMENT '反馈时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_qa_session (session_id),
    INDEX idx_qa_user (user_id)
);
