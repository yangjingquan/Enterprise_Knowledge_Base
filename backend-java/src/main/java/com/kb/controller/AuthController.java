package com.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.config.JwtUtil;
import com.kb.entity.User;
import com.kb.mapper.UserMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录认证接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserMapper users;
    private final BCryptPasswordEncoder encoder;
    private final JwtUtil jwt;

    /** 构造器注入依赖 */
    public AuthController(UserMapper users, BCryptPasswordEncoder encoder, JwtUtil jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /**
     * 登录：POST /api/auth/login，请求体 {"username": "...", "password": "..."}。
     * 校验通过返回 token、用户名和角色；失败返回 401。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> req) {
        // 按用户名查询用户
        User user = users.selectOne(new QueryWrapper<User>().eq("username", req.get("username")));

        // 用户不存在，或密码与数据库中的 BCrypt 密文不匹配
        if (user == null || !encoder.matches(req.getOrDefault("password", ""), user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        // 已停用的账号不允许登录
        if (Integer.valueOf(0).equals(user.getEnabled())) {
            return ResponseEntity.status(401).body(Map.of("error", "账号已停用，请联系管理员"));
        }

        String token = jwt.create(user.getId(), user.getUsername(), user.getRole());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "username", user.getUsername(),
                "displayName", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                "role", user.getRole()));
    }

    /**
     * 应用启动时执行：如果数据库里还没有 admin 用户，就自动创建演示管理员 admin / admin123。
     */
    @Bean
    static CommandLineRunner seedAdmin(UserMapper users, BCryptPasswordEncoder encoder) {
        return args -> {
            boolean exists = users.selectCount(new QueryWrapper<User>().eq("username", "admin")) > 0;
            if (!exists) {
                User admin = new User();
                admin.setUsername("admin");
                admin.setPassword(encoder.encode("admin123"));   // 只保存加密后的密码
                admin.setRole("admin");
                admin.setDisplayName("系统管理员");
                admin.setEnabled(1);
                users.insert(admin);
            }
        };
    }
}
