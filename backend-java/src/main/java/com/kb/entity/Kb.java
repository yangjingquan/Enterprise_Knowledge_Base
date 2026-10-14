package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库（文档分组），对应数据库表 kb。
 * 每个文档属于一个知识库；用户按知识库授权，只能检索有权限的知识库。
 */
@Data
@TableName("kb")
public class Kb {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库名称，如“人事制度” */
    private String name;

    /** 说明 */
    private String description;

    /** 可见范围：public（所有登录用户可查看）/ private（仅被授权的用户可查看） */
    private String visibility;

    /** 创建时间 */
    private LocalDateTime createTime;
}
