package com.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.config.JwtUtil;
import com.kb.entity.Broker;
import com.kb.entity.User;
import com.kb.mapper.BrokerMapper;
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
import java.util.HashMap;
import java.util.Map;

/**
 * 登录认证接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserMapper users;
    private final BrokerMapper brokers;
    private final BCryptPasswordEncoder encoder;
    private final JwtUtil jwt;
    private final PasswordCrypto crypto;
    private final LoginGuard guard;

    public AuthController(UserMapper users, BrokerMapper brokers, BCryptPasswordEncoder encoder, JwtUtil jwt,
                          PasswordCrypto crypto, LoginGuard guard) {
        this.users = users;
        this.brokers = brokers;
        this.encoder = encoder;
        this.jwt = jwt;
        this.crypto = crypto;
        this.guard = guard;
    }

    @GetMapping("/ping")
    public ResponseEntity<?> ping() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("status", "ok"));
    }

    @GetMapping("/public-key")
    public ResponseEntity<?> publicKey() {
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(crypto.issue());
        } catch (PasswordCrypto.BadPayload e) {
            return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
        }
    }

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

        User user = username.isEmpty() ? null : users.selectOne(new QueryWrapper<User>().eq("username", username));
        if (user == null || !encoder.matches(password, user.getPassword())) {
            if (guard.fail(username, ip)) {
                return locked(guard.lockMinutes() * 60);
            }
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        if (Integer.valueOf(0).equals(user.getEnabled())) {
            return ResponseEntity.status(401).body(Map.of("error", "账号已停用，请联系管理员"));
        }

        Long brokerId = null;
        int bv = 0;
        String brokerName = null;
        if (!"admin".equals(user.getRole())) {
            if (user.getBrokerId() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "账号尚未分配客户，请联系管理员"));
            }
            Broker b = brokers.selectById(user.getBrokerId());
            if (b == null || Integer.valueOf(0).equals(b.getEnabled())) {
                return ResponseEntity.status(401).body(Map.of("error", "账号不可用"));
            }
            brokerId = b.getId();
            bv = b.getTokenVersion() == null ? 0 : b.getTokenVersion();
            brokerName = b.getName();
        }

        guard.success(username, ip);
        String token = jwt.create(user.getId(), user.getUsername(), user.getRole(), brokerId, bv);
        Map<String, Object> out = new HashMap<>();
        out.put("token", token);
        out.put("username", user.getUsername());
        out.put("displayName", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName());
        out.put("role", user.getRole());
        out.put("brokerId", brokerId);
        if (brokerName != null) {
            out.put("brokerName", brokerName);
        }
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<?> locked(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return ResponseEntity.status(429).body(Map.of(
                "error", "登录失败次数过多，账号或当前网络已被锁定，请 " + minutes + " 分钟后再试",
                "retryAfter", seconds));
    }

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
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc);
        } catch (Exception e) {
            return false;
        }
    }

    @Bean
    static CommandLineRunner seedAdmin(UserMapper users, BCryptPasswordEncoder encoder) {
        return args -> {
            boolean exists = users.selectCount(new QueryWrapper<User>().eq("username", "admin")) > 0;
            if (!exists) {
                User admin = new User();
                admin.setUsername("admin");
                admin.setPassword(encoder.encode("admin123"));
                admin.setRole("admin");
                admin.setDisplayName("系统管理员");
                admin.setEnabled(1);
                users.insert(admin);
            }
        };
    }
}
