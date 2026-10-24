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
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 用户接口。
 * - GET /api/me：当前登录用户；
 * - /api/admin/users/**：客户管理员管理本客户普通用户（不能创建 broker_admin / admin）。
 */
@RestController
public class UserController {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,29}$");
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

    @GetMapping("/api/me")
    public Map<String, Object> me() {
        CurrentUser me = perm.current();
        User u = users.selectById(me.id());
        Map<String, Object> out = new HashMap<>();
        out.put("id", u.getId());
        out.put("username", u.getUsername());
        out.put("displayName", u.getDisplayName());
        out.put("role", u.getRole());
        out.put("brokerId", u.getBrokerId());
        out.put("kbRoles", me.isSuperAdmin() ? Map.of() : perm.kbRoles(me));
        out.put("canCreateKb", !me.isSuperAdmin() && perm.canCreateKb(me));
        return out;
    }

    @GetMapping("/api/admin/users")
    public List<Map<String, Object>> list() {
        CurrentUser me = perm.requireBrokerAdmin();
        Map<Long, String> kbNames = new HashMap<>();
        perm.brokerKbs(me.brokerId()).forEach(kb -> kbNames.put(kb.getId(), kb.getName()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users.selectList(new QueryWrapper<User>()
                .eq("broker_id", me.brokerId())
                .ne("role", "admin")
                .orderByAsc("id"))) {
            // 客户管理员列表里也展示其他 broker_admin，但不可改他们的角色为普通
            Map<String, Object> row = new HashMap<>();
            row.put("id", u.getId());
            row.put("username", u.getUsername());
            row.put("displayName", u.getDisplayName());
            row.put("role", u.getRole());
            row.put("enabled", !Integer.valueOf(0).equals(u.getEnabled()));
            row.put("createTime", u.getCreateTime());
            List<Map<String, Object>> grants = new ArrayList<>();
            for (KbMember m : members.selectList(new QueryWrapper<KbMember>().eq("user_id", u.getId()))) {
                if (!kbNames.containsKey(m.getKbId())) {
                    continue;
                }
                grants.add(Map.of(
                        "kbId", m.getKbId(),
                        "kbName", kbNames.get(m.getKbId()),
                        "role", m.getRole()));
            }
            row.put("grants", grants);
            out.add(row);
        }
        return out;
    }

    @PostMapping("/api/admin/users")
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> req) {
        CurrentUser me = perm.requireBrokerAdmin();
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
        // 客户管理员只能创建普通用户
        if (req.get("role") != null && !"user".equals(req.get("role"))) {
            return bad("只能创建普通用户");
        }
        User u = new User();
        u.setUsername(username);
        u.setPassword(encoder.encode(password));
        u.setDisplayName(str(req.get("displayName")).isEmpty() ? username : str(req.get("displayName")));
        u.setRole("user");
        u.setBrokerId(me.brokerId());
        u.setEnabled(1);
        users.insert(u);
        if (req.get("grants") instanceof List<?> grants) {
            saveGrants(me, u.getId(), grants);
        }
        return ResponseEntity.ok(Map.of("id", u.getId()));
    }

    @PutMapping("/api/admin/users/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        CurrentUser me = perm.requireBrokerAdmin();
        User u = users.selectById(id);
        if (u == null || !Objects.equals(u.getBrokerId(), me.brokerId())) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }
        // 不能改其他客户管理员的角色/删除其管理员身份；可改密码、停用（同客户）
        boolean isPeerAdmin = "broker_admin".equals(u.getRole());
        boolean self = u.getId().equals(me.id());

        if (req.containsKey("displayName")) {
            u.setDisplayName(str(req.get("displayName")));
        }
        if (req.containsKey("role")) {
            if (isPeerAdmin || self) {
                return bad("不能修改客户管理员的角色");
            }
            if (!"user".equals(req.get("role"))) {
                return bad("只能设置为普通用户");
            }
            u.setRole("user");
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
            if (isPeerAdmin) {
                return bad("不能修改客户管理员的知识库授权（其默认管理本客户全部知识库）");
            }
            members.delete(new QueryWrapper<KbMember>().eq("user_id", id));
            saveGrants(me, id, grants);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private void saveGrants(CurrentUser me, Long userId, List<?> grants) {
        for (Object g : grants) {
            if (!(g instanceof Map<?, ?> m) || !(m.get("kbId") instanceof Number kbId)) {
                continue;
            }
            String role = "editor".equals(m.get("role")) ? "editor" : "viewer";
            Kb kb = kbs.selectById(kbId.longValue());
            if (kb == null || !Objects.equals(kb.getBrokerId(), me.brokerId())) {
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
