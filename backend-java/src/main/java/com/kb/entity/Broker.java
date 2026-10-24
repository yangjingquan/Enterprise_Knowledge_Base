package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户（broker）实体，对应表 broker。
 * 各客户之间业务数据完全隔离；停用时 token_version 递增，令牌立即失效。
 */
@Data
@TableName("broker")
public class Broker {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户名称 */
    private String name;

    /** 编码：全局唯一，仅字母数字，创建后不可改 */
    private String code;

    /** 是否启用：1 启用，0 停用 */
    private Integer enabled;

    /** 令牌版本：停用时 +1，JWT 中的 bv 必须与此一致 */
    private Integer tokenVersion;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
