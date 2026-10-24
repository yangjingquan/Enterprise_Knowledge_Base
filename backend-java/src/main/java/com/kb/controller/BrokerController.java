package com.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.entity.Broker;
import com.kb.entity.User;
import com.kb.mapper.BrokerMapper;
import com.kb.mapper.UserMapper;
import com.kb.service.PasswordCrypto;
import com.kb.service.PermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 超级管理员：客户（broker）管理与指定客户管理员。
 */
@RestController
@RequestMapping("/api/super/brokers")
public class BrokerController {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9]+$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,29}$");
    private static final int MIN_PASSWORD = 6;

    private final BrokerMapper brokers;
    private final UserMapper users;
    private final PermissionService perm;
    private final BCryptPasswordEncoder encoder;
    private final PasswordCrypto crypto;

    public BrokerController(BrokerMapper brokers, UserMapper users, PermissionService perm,
                            BCryptPasswordEncoder encoder, PasswordCrypto crypto) {
        this.brokers = brokers;
        this.users = users;
        this.perm = perm;
        this.encoder = encoder;
        this.crypto = crypto;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        perm.requireSuperAdmin();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Broker b : brokers.selectList(new QueryWrapper<Broker>().orderByAsc("id"))) {
            out.add(toMap(b));
        }
        return out;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> req) {
        perm.requireSuperAdmin();
        String name = str(req.get("name"));
        String code = str(req.get("code"));
        if (name.isEmpty()) {
            return bad("客户名称不能为空");
        }
        if (name.length() > 100) {
            return bad("客户名称不能超过 100 个字");
        }
        if (!CODE.matcher(code).matches()) {
            return bad("客户编码只能包含字母和数字");
        }
        if (brokers.selectCount(new QueryWrapper<Broker>().eq("code", code)) > 0) {
            return bad("客户编码已存在");
        }
        boolean enabled = !Boolean.FALSE.equals(req.get("enabled"));
        Broker b = new Broker();
        b.setName(name);
        b.setCode(code);
        b.setEnabled(enabled ? 1 : 0);
        b.setTokenVersion(0);
        b.setCreateTime(LocalDateTime.now());
        b.setUpdateTime(LocalDateTime.now());
        brokers.insert(b);
        return ResponseEntity.ok(toMap(b));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        perm.requireSuperAdmin();
        Broker b = brokers.selectById(id);
        if (b == null) {
            return ResponseEntity.status(404).body(Map.of("error", "客户不存在"));
        }
        if (req.containsKey("code")) {
            String newCode = str(req.get("code"));
            if (!newCode.equals(b.getCode())) {
                return bad("客户编码创建后不可修改");
            }
        }
        if (req.containsKey("name")) {
            String name = str(req.get("name"));
            if (name.isEmpty()) {
                return bad("客户名称不能为空");
            }
            b.setName(name);
        }
        if (req.containsKey("enabled")) {
            boolean enabled = Boolean.TRUE.equals(req.get("enabled"));
            boolean was = !Integer.valueOf(0).equals(b.getEnabled());
            b.setEnabled(enabled ? 1 : 0);
            if (was && !enabled) {
                // 停用：递增 token_version，使该客户下所有 JWT 立即失效
                b.setTokenVersion((b.getTokenVersion() == null ? 0 : b.getTokenVersion()) + 1);
            }
        }
        b.setUpdateTime(LocalDateTime.now());
        brokers.updateById(b);
        return ResponseEntity.ok(toMap(b));
    }

    @GetMapping("/{id}/admins")
    public ResponseEntity<?> admins(@PathVariable Long id) {
        perm.requireSuperAdmin();
        if (brokers.selectById(id) == null) {
            return ResponseEntity.status(404).body(Map.of("error", "客户不存在"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users.selectList(new QueryWrapper<User>()
                .eq("broker_id", id).eq("role", "broker_admin").orderByAsc("id"))) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", u.getId());
            row.put("username", u.getUsername());
            row.put("displayName", u.getDisplayName());
            row.put("enabled", !Integer.valueOf(0).equals(u.getEnabled()));
            row.put("createTime", u.getCreateTime());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /** 为客户创建一名客户管理员 */
    @PostMapping("/{id}/admins")
    @Transactional
    public ResponseEntity<?> createAdmin(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        perm.requireSuperAdmin();
        Broker b = brokers.selectById(id);
        if (b == null) {
            return ResponseEntity.status(404).body(Map.of("error", "客户不存在"));
        }
        String username = str(req.get("username"));
        if (!USERNAME.matcher(username).matches()) {
            return bad("用户名须以字母开头，由字母、数字、下划线组成，3~30 位");
        }
        if (users.selectCount(new QueryWrapper<User>().eq("username", username)) > 0) {
            return bad("用户名已存在");
        }
        if (req.containsKey("password")) {
            return bad("不支持明文密码，请刷新页面后重试");
        }
        String password;
        try {
            password = crypto.decrypt(req.get("passwordCipher"));
        } catch (PasswordCrypto.BadPayload e) {
            return bad(e.getMessage());
        }
        if (password.length() < MIN_PASSWORD) {
            return bad("密码至少 " + MIN_PASSWORD + " 位");
        }
        User u = new User();
        u.setUsername(username);
        u.setPassword(encoder.encode(password));
        u.setDisplayName(str(req.get("displayName")).isEmpty() ? username : str(req.get("displayName")));
        u.setRole("broker_admin");
        u.setBrokerId(id);
        u.setEnabled(1);
        users.insert(u);
        return ResponseEntity.ok(Map.of("id", u.getId(), "username", u.getUsername()));
    }

    /** 移除客户管理员（删除该账号） */
    @DeleteMapping("/{id}/admins/{userId}")
    @Transactional
    public ResponseEntity<?> removeAdmin(@PathVariable Long id, @PathVariable Long userId) {
        perm.requireSuperAdmin();
        User u = users.selectById(userId);
        if (u == null || !id.equals(u.getBrokerId()) || !"broker_admin".equals(u.getRole())) {
            return ResponseEntity.status(404).body(Map.of("error", "客户管理员不存在"));
        }
        users.deleteById(userId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Map<String, Object> toMap(Broker b) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", b.getId());
        m.put("name", b.getName());
        m.put("code", b.getCode());
        m.put("enabled", !Integer.valueOf(0).equals(b.getEnabled()));
        m.put("tokenVersion", b.getTokenVersion() == null ? 0 : b.getTokenVersion());
        m.put("createTime", b.getCreateTime());
        m.put("updateTime", b.getUpdateTime());
        long adminCount = users.selectCount(new QueryWrapper<User>()
                .eq("broker_id", b.getId()).eq("role", "broker_admin"));
        long userCount = users.selectCount(new QueryWrapper<User>().eq("broker_id", b.getId()));
        m.put("adminCount", adminCount);
        m.put("userCount", userCount);
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static ResponseEntity<Map<String, String>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
