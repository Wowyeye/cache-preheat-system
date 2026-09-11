package com.jyu.cache.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体类（对应 sys_user 表）
 */
@Data
public class SysUser {

    private Long id;

    /** 登录账号（唯一） */
    private String username;

    /** BCrypt 加密后的密码 */
    private String password;

    /** 展示昵称 */
    private String nickname;

    /** 角色：ADMIN=管理员，USER=普通用户 */
    private String role;

    /** 状态：1=正常，0=禁用 */
    private Integer status;

    private LocalDateTime createTime;
}
