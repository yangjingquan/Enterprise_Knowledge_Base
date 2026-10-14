package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答历史实体，对应数据库表 qa_history。每次提问都会保存一条记录。
 */
@Data
@TableName("qa_history")
public class QaHistory {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 限定提问的文档 ID；为空表示在全部文档中检索 */
    private Long documentId;

    /** 用户的问题 */
    private String question;

    /** 大模型生成的回答 */
    private String answer;

    /** 回答引用的原文片段，以 JSON 字符串保存 */
    private String sources;

    /** 会话 ID：同一次“新对话”里的多轮问答共用一个 ID，前端生成 */
    private String sessionId;

    /** 多轮对话时，大模型把追问改写后的完整问题（用于检索）；单轮时为空 */
    private String rewrittenQuestion;

    /** 提问人用户名 */
    private String username;

    /** 提问人用户 ID：普通用户只能查看自己的问答历史 */
    private Long userId;

    /** 提问时限定的知识库 ID；为空表示在用户有权限的全部知识库中检索 */
    private Long kbId;

    /** 用户反馈：1 = 有用（点赞），-1 = 没用（点踩），null = 未反馈 */
    private Integer feedback;

    /** 点踩时用户填写的原因（可选） */
    private String feedbackComment;

    /** 反馈时间 */
    private LocalDateTime feedbackTime;

    /** 提问时间 */
    private LocalDateTime createTime;
}
