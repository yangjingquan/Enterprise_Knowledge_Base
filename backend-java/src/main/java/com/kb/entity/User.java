package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应数据库表 user。
 */
@Data
@TableName("user")
public class User {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名 */
    private String username;

    /** BCrypt 加密后的密码（数据库中不保存明文） */
    /** 密码只允许写入，接口返回时不输出 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 角色，如 admin / user */
    private String role;

    /** 显示名称（中文姓名或岗位，如“人事专员-李婷”） */
    private String displayName;

    /** 是否启用：1 启用，0 停用（停用后无法登录，已签发的 token 也会被拒绝） */
    private Integer enabled;

    /** 创建时间 */
    private LocalDateTime createTime;
}
