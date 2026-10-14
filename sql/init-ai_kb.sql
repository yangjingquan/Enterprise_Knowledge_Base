-- 首次创建 MySQL 数据卷时执行。已有数据卷的升级由 Java 启动时的 SchemaMigration 自动补列。
CREATE DATABASE IF NOT EXISTS ai_kb DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_kb;

-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) DEFAULT 'user' COMMENT 'admin 系统管理员 / user 普通用户',
    display_name VARCHAR(50) NULL COMMENT '显示名称',
    enabled TINYINT DEFAULT 1 COMMENT '1启用 0停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 知识库表：visibility = public 时所有登录用户可查看，private 时仅被授权的用户可查看
CREATE TABLE IF NOT EXISTS kb (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    visibility VARCHAR(20) DEFAULT 'private',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 知识库授权表：role = viewer（可问答、看原文）/ editor（还可上传、删除文档）
CREATE TABLE IF NOT EXISTS kb_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'viewer',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_user (kb_id, user_id)
);

-- 文档表：file_type 为 url 表示网页导入，source_url 记录原始网址；kb_id 为所属知识库
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

-- 问答历史表：session_id 关联同一次对话的多轮问答，feedback 为用户反馈（1 有用 / -1 没用）
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
