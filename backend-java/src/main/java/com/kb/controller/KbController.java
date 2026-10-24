package com.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.entity.Document;
import com.kb.entity.Kb;
import com.kb.entity.KbMember;
import com.kb.entity.User;
import com.kb.mapper.DocumentMapper;
import com.kb.mapper.KbMapper;
import com.kb.mapper.KbMemberMapper;
import com.kb.mapper.UserMapper;
import com.kb.service.PermissionService;
import com.kb.service.PermissionService.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.util.Set;

/**
 * 知识库接口：按 broker 隔离；超管不可访问。
 * 客户管理员可完整管理；本客户 editor 可新建知识库。
 */
@RestController
public class KbController {

    private static final Set<String> KB_ROLES = Set.of("viewer", "editor");

    private final KbMapper kbs;
    private final KbMemberMapper members;
    private final UserMapper users;
    private final DocumentMapper documents;
    private final PermissionService perm;
    private final com.kb.mapper.DatasetMapper datasets;

    public KbController(KbMapper kbs, KbMemberMapper members, UserMapper users,
                        DocumentMapper documents, PermissionService perm, com.kb.mapper.DatasetMapper datasets) {
        this.datasets = datasets;
        this.kbs = kbs;
        this.members = members;
        this.users = users;
        this.documents = documents;
        this.perm = perm;
    }

    @GetMapping("/api/kbs")
    public List<Map<String, Object>> mine() {
        CurrentUser me = perm.requireBusinessUser();
        Map<Long, String> roles = perm.kbRoles(me);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Kb kb : perm.brokerKbs(me.brokerId())) {
            if (roles.containsKey(kb.getId())) {
                Map<String, Object> m = toMap(kb);
                m.put("myRole", roles.get(kb.getId()));
                out.add(m);
            }
        }
        return out;
    }

    @GetMapping("/api/admin/kbs")
    public List<Map<String, Object>> list() {
        CurrentUser me = perm.requireBrokerAdmin();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Kb kb : perm.brokerKbs(me.brokerId())) {
            Map<String, Object> m = toMap(kb);
            m.put("memberCount", members.selectCount(new QueryWrapper<KbMember>().eq("kb_id", kb.getId())));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/api/admin/kbs")
    @Transactional
    public ResponseEntity<?> create(@RequestBody Kb req) {
        CurrentUser me = perm.requireBusinessUser();
        if (!perm.canCreateKb(me)) {
            return ResponseEntity.status(403).body(Map.of("error", "没有新建知识库的权限"));
        }
        String error = validate(req, null, me.brokerId());
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        Kb kb = new Kb();
        kb.setName(req.getName().trim());
        kb.setDescription(req.getDescription());
        kb.setVisibility("public".equals(req.getVisibility()) ? "public" : "private");
        kb.setBrokerId(me.brokerId());
        kbs.insert(kb);
        // 非客户管理员创建时，自动授予自己 editor
        if (!me.isBrokerAdmin()) {
            KbMember m = new KbMember();
            m.setKbId(kb.getId());
            m.setUserId(me.id());
            m.setRole("editor");
            members.insert(m);
        }
        return ResponseEntity.ok(kb);
    }

    @PutMapping("/api/admin/kbs/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Kb req) {
        CurrentUser me = perm.requireBrokerAdmin();
        Kb kb = perm.requireBrokerKb(me, id);
        String error = validate(req, id, me.brokerId());
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        kb.setName(req.getName().trim());
        kb.setDescription(req.getDescription());
        kb.setVisibility("public".equals(req.getVisibility()) ? "public" : "private");
        kbs.updateById(kb);
        return ResponseEntity.ok(kb);
    }

    @DeleteMapping("/api/admin/kbs/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        CurrentUser me = perm.requireBrokerAdmin();
        perm.requireBrokerKb(me, id);
        long docCount = documents.selectCount(new QueryWrapper<Document>().eq("kb_id", id).isNull("cleanup_status"));
        if (docCount > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "该知识库还有 " + docCount + " 个文档，请先删除或移走"));
        }
        long dsCount = datasets.selectCount(new QueryWrapper<com.kb.entity.Dataset>().eq("kb_id", id));
        if (dsCount > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "该知识库还有 " + dsCount + " 个数据集，请先删除"));
        }
        members.delete(new QueryWrapper<KbMember>().eq("kb_id", id));
        kbs.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/api/admin/kbs/{id}/members")
    public List<Map<String, Object>> members(@PathVariable Long id) {
        CurrentUser me = perm.requireBrokerAdmin();
        perm.requireBrokerKb(me, id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbMember m : members.selectList(new QueryWrapper<KbMember>().eq("kb_id", id))) {
            User u = users.selectById(m.getUserId());
            if (u == null || !Objects.equals(u.getBrokerId(), me.brokerId())) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("userId", u.getId());
            row.put("username", u.getUsername());
            row.put("displayName", u.getDisplayName());
            row.put("role", m.getRole());
            out.add(row);
        }
        return out;
    }

    @PutMapping("/api/admin/kbs/{id}/members")
    @Transactional
    public ResponseEntity<?> setMembers(@PathVariable Long id, @RequestBody List<Map<String, Object>> req) {
        CurrentUser me = perm.requireBrokerAdmin();
        perm.requireBrokerKb(me, id);
        for (Map<String, Object> item : req) {
            if (!KB_ROLES.contains(String.valueOf(item.get("role")))) {
                return ResponseEntity.badRequest().body(Map.of("error", "角色只能是 viewer 或 editor"));
            }
            Long userId = ((Number) item.get("userId")).longValue();
            User u = users.selectById(userId);
            if (u == null || !Objects.equals(u.getBrokerId(), me.brokerId()) || "broker_admin".equals(u.getRole())) {
                return ResponseEntity.badRequest().body(Map.of("error", "只能授权本客户的普通用户"));
            }
        }
        members.delete(new QueryWrapper<KbMember>().eq("kb_id", id));
        for (Map<String, Object> item : req) {
            KbMember m = new KbMember();
            m.setKbId(id);
            m.setUserId(((Number) item.get("userId")).longValue());
            m.setRole(String.valueOf(item.get("role")));
            members.insert(m);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String validate(Kb req, Long selfId, Long brokerId) {
        if (req.getName() == null || req.getName().isBlank()) {
            return "知识库名称不能为空";
        }
        if (req.getName().trim().length() > 100) {
            return "知识库名称不能超过 100 个字";
        }
        QueryWrapper<Kb> q = new QueryWrapper<Kb>().eq("name", req.getName().trim()).eq("broker_id", brokerId);
        Kb same = kbs.selectOne(q);
        if (same != null && !same.getId().equals(selfId)) {
            return "已存在同名知识库";
        }
        return null;
    }

    private Map<String, Object> toMap(Kb kb) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", kb.getId());
        m.put("name", kb.getName());
        m.put("description", kb.getDescription());
        m.put("visibility", kb.getVisibility());
        m.put("brokerId", kb.getBrokerId());
        m.put("docCount", documents.selectCount(new QueryWrapper<Document>().eq("kb_id", kb.getId()).isNull("cleanup_status")));
        return m;
    }
}
