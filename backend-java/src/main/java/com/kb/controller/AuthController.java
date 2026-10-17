package com.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.config.JwtUtil;
import com.kb.entity.User;
import com.kb.mapper.UserMapper;
import com.kb.service.LoginGuard;
import com.kb.service.PasswordCrypto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
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

    private final PasswordCrypto crypto;
    private final LoginGuard guard;

    /** 构造器注入依赖 */
    public AuthController(UserMapper users, BCryptPasswordEncoder encoder, JwtUtil jwt,
                          PasswordCrypto crypto, LoginGuard guard) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.crypto = crypto;
        this.guard = guard;
    }

    /**
     * 获取登录用公钥：GET /api/auth/public-key，返回 {publicKey(SPKI base64), nonce, expiresIn, alg}。
     * nonce 一次性、60 秒有效；登录、新建用户、重置密码前都要先取一次。
     */
    @GetMapping("/public-key")
    public ResponseEntity<?> publicKey() {
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(crypto.issue());
        } catch (PasswordCrypto.BadPayload e) {
            return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 登录：POST /api/auth/login，请求体 {"username": "...", "passwordCipher": "RSA-OAEP 密文(base64)"}。
     * 密文内容为 {"password", "nonce", "ts"}；不再接受明文 password 字段。
     * 同一账号或同一 IP 连续失败 5 次锁定 10 分钟（429），登录成功清零。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> req, HttpServletRequest http) {
        String username = req.get("username") instanceof String s ? s.trim() : "";
        String ip = clientIp(http);

        long left = guard.lockedSeconds(username, ip);
        if (left > 0) {
            return locked(left);
        }
        if (req.containsKey("password")) {
            return ResponseEntity.badRequest().body(Map.of("error", "不支持明文密码登录，请刷新页面后重试"));
        }

        String password;
        try {
            password = crypto.decrypt(req.get("passwordCipher"));
        } catch (PasswordCrypto.BadPayload e) {
            if (guard.fail(username, ip)) {
                return locked(guard.lockMinutes() * 60);
            }
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }

        // 按用户名查询用户；用户不存在，或密码与数据库中的 BCrypt 密文不匹配
        User user = username.isEmpty() ? null : users.selectOne(new QueryWrapper<User>().eq("username", username));
        if (user == null || !encoder.matches(password, user.getPassword())) {
            if (guard.fail(username, ip)) {
                return locked(guard.lockMinutes() * 60);
            }
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        // 已停用的账号不允许登录
        if (Integer.valueOf(0).equals(user.getEnabled())) {
            return ResponseEntity.status(401).body(Map.of("error", "账号已停用，请联系管理员"));
        }
        guard.success(username, ip);

        String token = jwt.create(user.getId(), user.getUsername(), user.getRole());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "username", user.getUsername(),
                "displayName", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                "role", user.getRole()));
    }

    private ResponseEntity<?> locked(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return ResponseEntity.status(429).body(Map.of(
                "error", "登录失败次数过多，账号或当前网络已被锁定，请 " + minutes + " 分钟后再试",
                "retryAfter", seconds));
    }

    /**
     * 客户端 IP：请求来自内网 / 本机反向代理（nginx）时取 X-Real-IP，否则取直连地址，
     * 防止公网客户端伪造请求头绕过按 IP 限流。
     */
    static String clientIp(HttpServletRequest http) {
        String remote = http.getRemoteAddr();
        String real = http.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && isPrivate(remote)) {
            return real.trim();
        }
        return remote;
    }

    private static boolean isPrivate(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc);   // IPv6 ULA
        } catch (Exception e) {
            return false;
        }
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
