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
import java.util.Set;

/**
 * 知识库接口。
 * - GET /api/kbs：当前用户可见的知识库（含我的角色、文档数），用于各页面的知识库选择器；
 * - /api/admin/kbs/**：管理员维护知识库和成员授权。
 */
@RestController
public class KbController {

    /** 合法的知识库角色 */
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

    /** 我可见的知识库：[{id, name, description, visibility, myRole, docCount}] */
    @GetMapping("/api/kbs")
    public List<Map<String, Object>> mine() {
        CurrentUser me = perm.current();
        Map<Long, String> roles = perm.kbRoles(me);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Kb kb : kbs.selectList(new QueryWrapper<Kb>().orderByAsc("id"))) {
            if (roles.containsKey(kb.getId())) {
                Map<String, Object> m = toMap(kb);
                m.put("myRole", roles.get(kb.getId()));
                out.add(m);
            }
        }
        return out;
    }

    // ---------------- 以下为管理员接口 ----------------

    /** 全部知识库（含成员数、文档数） */
    @GetMapping("/api/admin/kbs")
    public List<Map<String, Object>> list() {
        perm.requireAdmin();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Kb kb : kbs.selectList(new QueryWrapper<Kb>().orderByAsc("id"))) {
            Map<String, Object> m = toMap(kb);
            m.put("memberCount", members.selectCount(new QueryWrapper<KbMember>().eq("kb_id", kb.getId())));
            out.add(m);
        }
        return out;
    }

    /** 新建知识库：{name, description, visibility} */
    @PostMapping("/api/admin/kbs")
    public ResponseEntity<?> create(@RequestBody Kb req) {
        perm.requireAdmin();
        String error = validate(req, null);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        Kb kb = new Kb();
        kb.setName(req.getName().trim());
        kb.setDescription(req.getDescription());
        kb.setVisibility("public".equals(req.getVisibility()) ? "public" : "private");
        kbs.insert(kb);
        return ResponseEntity.ok(kb);
    }

    /** 修改知识库名称、说明、可见范围 */
    @PutMapping("/api/admin/kbs/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Kb req) {
        perm.requireAdmin();
        Kb kb = kbs.selectById(id);
        if (kb == null) {
            return ResponseEntity.status(404).body(Map.of("error", "知识库不存在"));
        }
        String error = validate(req, id);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        kb.setName(req.getName().trim());
        kb.setDescription(req.getDescription());
        kb.setVisibility("public".equals(req.getVisibility()) ? "public" : "private");
        kbs.updateById(kb);
        return ResponseEntity.ok(kb);
    }

    /** 删除知识库：库中还有文档时不允许删除，避免误删大量资料 */
    @DeleteMapping("/api/admin/kbs/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        perm.requireAdmin();
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

    /** 知识库成员：[{userId, username, displayName, role}] */
    @GetMapping("/api/admin/kbs/{id}/members")
    public List<Map<String, Object>> members(@PathVariable Long id) {
        perm.requireAdmin();
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbMember m : members.selectList(new QueryWrapper<KbMember>().eq("kb_id", id))) {
            User u = users.selectById(m.getUserId());
            if (u == null) {
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

    /**
     * 整体设置知识库成员：[{userId, role}]，未出现在列表中的成员会被移除。
     */
    @PutMapping("/api/admin/kbs/{id}/members")
    @Transactional
    public ResponseEntity<?> setMembers(@PathVariable Long id, @RequestBody List<Map<String, Object>> req) {
        perm.requireAdmin();
        if (kbs.selectById(id) == null) {
            return ResponseEntity.status(404).body(Map.of("error", "知识库不存在"));
        }
        for (Map<String, Object> item : req) {
            if (!KB_ROLES.contains(String.valueOf(item.get("role")))) {
                return ResponseEntity.badRequest().body(Map.of("error", "角色只能是 viewer 或 editor"));
            }
        }
        long dsCount = datasets.selectCount(new QueryWrapper<com.kb.entity.Dataset>().eq("kb_id", id));
        if (dsCount > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "该知识库还有 " + dsCount + " 个数据集，请先删除"));
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

    /** 校验名称非空且不重复；返回错误信息，校验通过返回 null */
    private String validate(Kb req, Long selfId) {
        if (req.getName() == null || req.getName().isBlank()) {
            return "知识库名称不能为空";
        }
        if (req.getName().trim().length() > 100) {
            return "知识库名称不能超过 100 个字";
        }
        Kb same = kbs.selectOne(new QueryWrapper<Kb>().eq("name", req.getName().trim()));
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
        m.put("docCount", documents.selectCount(new QueryWrapper<Document>().eq("kb_id", kb.getId()).isNull("cleanup_status")));
        return m;
    }
}
