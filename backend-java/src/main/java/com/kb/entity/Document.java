package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档实体，对应数据库表 document。
 * Lombok 的 @Data 会自动生成 getter / setter / toString 等方法。
 */
@Data
@TableName("document")
public class Document {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档标题（文件名去掉扩展名） */
    private String title;

    /** 用户上传时的原始文件名 */
    private String fileName;

    /** 文件在服务器上的保存路径 */
    private String filePath;

    /** 文件类型：pdf / docx / xlsx / pptx / txt / md / url（网页导入） */
    private String fileType;

    /** 处理状态：pending（待处理）→ processing（处理中）→ ready（可问答）/ failed（失败） */
    private String status;

    /** 文档被切分成的文本块数量 */
    private Integer chunkCount;

    /** 创建时间（数据库默认值自动填充） */
    private LocalDateTime createTime;

    /** 网页导入时的原始网址；上传的文件为空 */
    private String sourceUrl;

    /** 所属知识库 ID */
    private Long kbId;

    /** 通过 OCR 识别的页数（扫描件）；0 表示全部为文字版 */
    private Integer ocrPages;

    /** 上传人 ID */
    private Long userId;
}
