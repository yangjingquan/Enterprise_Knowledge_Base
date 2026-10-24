package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库（文档分组），对应数据库表 kb。
 * 每个知识库必须属于某个 broker（存量可暂时为空，由超管手工指定）。
 */
@Data
@TableName("kb")
public class Kb {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** public（本客户内全员可查看）/ private（仅授权用户） */
    private String visibility;

    /** 所属客户 */
    private Long brokerId;

    private LocalDateTime createTime;
}
