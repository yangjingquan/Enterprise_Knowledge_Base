package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库授权，对应数据库表 kb_member：某个用户在某个知识库中的角色。
 * <p>
 * 角色：
 * - viewer：可以在该知识库中问答、查看原文；
 * - editor：在 viewer 基础上，还可以上传、导入、删除该知识库的文档。
 * 系统管理员（user.role = admin）不需要授权，可管理全部知识库。
 */
@Data
@TableName("kb_member")
public class KbMember {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库 ID */
    private Long kbId;

    /** 用户 ID */
    private Long userId;

    /** 角色：viewer / editor */
    private String role;

    /** 授权时间 */
    private LocalDateTime createTime;
}
