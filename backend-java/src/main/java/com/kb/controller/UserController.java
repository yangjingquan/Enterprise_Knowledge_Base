package com.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.entity.Kb;
import com.kb.entity.KbMember;
import com.kb.entity.User;
import com.kb.mapper.KbMapper;
import com.kb.mapper.KbMemberMapper;
import com.kb.mapper.UserMapper;
import com.kb.service.PasswordCrypto;
import com.kb.service.PermissionService;
import com.kb.service.PermissionService.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用户接口。
 * - GET /api/me：当前登录用户信息及可见知识库角色；
 * - /api/admin/users/**：管理员创建用户、修改角色 / 状态 / 密码、分配知识库权限。
 */
@RestController
public class UserController {

    /** 用户名规则：字母开头，字母数字下划线，3~30 位 */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,29}$");

    /** 密码最短长度 */
    private static final int MIN_PASSWORD = 6;

    private final UserMapper users;
    private final KbMemberMapper members;
    private final KbMapper kbs;
    private final BCryptPasswordEncoder encoder;
    private final PermissionService perm;
    private final PasswordCrypto crypto;

    public UserController(UserMapper users, KbMemberMapper members, KbMapper kbs,
                          BCryptPasswordEncoder encoder, PermissionService perm, PasswordCrypto crypto) {
        this.crypto = crypto;
        this.users = users;
        this.members = members;
        this.kbs = kbs;
        this.encoder = encoder;
        this.perm = perm;
    }

    /** 当前用户：{id, username, displayName, role, kbRoles: {知识库ID: 角色}} */
    @GetMapping("/api/me")
    public Map<String, Object> me() {
        CurrentUser me = perm.current();
        User u = users.selectById(me.id());
        Map<String, Object> out = new HashMap<>();
        out.put("id", u.getId());
        out.put("username", u.getUsername());
        out.put("displayName", u.getDisplayName());
        out.put("role", u.getRole());
        out.put("kbRoles", perm.kbRoles(me));
        return out;
    }

    /** 用户列表（含每个用户的知识库授权） */
    @GetMapping("/api/admin/users")
    public List<Map<String, Object>> list() {
        perm.requireAdmin();
        Map<Long, String> kbNames = new HashMap<>();
        kbs.selectList(null).forEach(kb -> kbNames.put(kb.getId(), kb.getName()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users.selectList(new QueryWrapper<User>().orderByAsc("id"))) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", u.getId());
            row.put("username", u.getUsername());
            row.put("displayName", u.getDisplayName());
            row.put("role", u.getRole());
            row.put("enabled", !Integer.valueOf(0).equals(u.getEnabled()));
            row.put("createTime", u.getCreateTime());
            List<Map<String, Object>> grants = new ArrayList<>();
            for (KbMember m : members.selectList(new QueryWrapper<KbMember>().eq("user_id", u.getId()))) {
                grants.add(Map.of(
                        "kbId", m.getKbId(),
                        "kbName", kbNames.getOrDefault(m.getKbId(), "已删除"),
                        "role", m.getRole()));
            }
            row.put("grants", grants);
            out.add(row);
        }
        return out;
    }

    /**
     * 创建用户：{username, displayName, passwordCipher（RSA-OAEP 密文，同登录）, role: user|admin, grants: [{kbId, role}]}
     */
    @PostMapping("/api/admin/users")
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> req) {
        perm.requireAdmin();
        String username = str(req.get("username"));
        if (req.containsKey("password")) {
            return bad("不支持明文密码，请刷新页面后重试");
        }
        String password;
        try {
            password = crypto.decrypt(req.get("passwordCipher"));
        } catch (PasswordCrypto.BadPayload e) {
            return bad(e.getMessage());
        }
        if (!USERNAME.matcher(username).matches()) {
            return bad("用户名须以字母开头，由字母、数字、下划线组成，3~30 位");
        }
        if (users.selectCount(new QueryWrapper<User>().eq("username", username)) > 0) {
            return bad("用户名已存在");
        }
        if (password.length() < MIN_PASSWORD) {
            return bad("密码至少 " + MIN_PASSWORD + " 位");
        }
        User u = new User();
        u.setUsername(username);
        u.setPassword(encoder.encode(password));
        u.setDisplayName(str(req.get("displayName")).isEmpty() ? username : str(req.get("displayName")));
        u.setRole("admin".equals(req.get("role")) ? "admin" : "user");
        u.setEnabled(1);
        users.insert(u);
        if (req.get("grants") instanceof List<?> grants) {
            saveGrants(u.getId(), grants);
        }
        return ResponseEntity.ok(Map.of("id", u.getId()));
    }

    /**
     * 修改用户：{displayName?, role?, enabled?, passwordCipher?, grants?}，只修改传入的字段。
     * 不允许管理员停用自己或取消自己的管理员身份，避免把自己锁在系统外。
     */
    @PutMapping("/api/admin/users/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        CurrentUser me = perm.requireAdmin();
        User u = users.selectById(id);
        if (u == null) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }
        boolean self = u.getId().equals(me.id());
        if (req.containsKey("displayName")) {
            u.setDisplayName(str(req.get("displayName")));
        }
        if (req.containsKey("role")) {
            String role = "admin".equals(req.get("role")) ? "admin" : "user";
            if (self && !"admin".equals(role)) {
                return bad("不能取消自己的管理员身份");
            }
            u.setRole(role);
        }
        if (req.containsKey("enabled")) {
            boolean enabled = Boolean.TRUE.equals(req.get("enabled"));
            if (self && !enabled) {
                return bad("不能停用自己的账号");
            }
            u.setEnabled(enabled ? 1 : 0);
        }
        if (req.get("password") != null && !str(req.get("password")).isEmpty()) {
            return bad("不支持明文密码，请刷新页面后重试");
        }
        // 重置密码：passwordCipher 为 RSA-OAEP 密文（同登录）；不传表示不修改
        if (req.get("passwordCipher") != null && !str(req.get("passwordCipher")).isEmpty()) {
            String password;
            try {
                password = crypto.decrypt(req.get("passwordCipher"));
            } catch (PasswordCrypto.BadPayload e) {
                return bad(e.getMessage());
            }
            if (password.length() < MIN_PASSWORD) {
                return bad("密码至少 " + MIN_PASSWORD + " 位");
            }
            u.setPassword(encoder.encode(password));
        }
        users.updateById(u);
        if (req.get("grants") instanceof List<?> grants) {
            members.delete(new QueryWrapper<KbMember>().eq("user_id", id));
            saveGrants(id, grants);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 保存知识库授权，忽略不存在的知识库和非法角色 */
    private void saveGrants(Long userId, List<?> grants) {
        for (Object g : grants) {
            if (!(g instanceof Map<?, ?> m) || !(m.get("kbId") instanceof Number kbId)) {
                continue;
            }
            String role = "editor".equals(m.get("role")) ? "editor" : "viewer";
            Kb kb = kbs.selectById(kbId.longValue());
            if (kb == null) {
                continue;
            }
            KbMember member = new KbMember();
            member.setUserId(userId);
            member.setKbId(kb.getId());
            member.setRole(role);
            members.insert(member);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static ResponseEntity<Map<String, String>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
