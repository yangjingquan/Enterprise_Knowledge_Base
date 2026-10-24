package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应数据库表 user。
 * role：admin（超管，无 broker）/ broker_admin / user；后两者必须属于某个 broker。
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** admin / broker_admin / user */
    private String role;

    private String displayName;

    /** 所属客户；超管为 null */
    private Long brokerId;

    /** 1 启用，0 停用 */
    private Integer enabled;

    private LocalDateTime createTime;
}
