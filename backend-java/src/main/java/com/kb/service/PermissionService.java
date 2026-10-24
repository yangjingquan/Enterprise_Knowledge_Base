package com.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.entity.Broker;
import com.kb.entity.Document;
import com.kb.entity.Kb;
import com.kb.entity.KbMember;
import com.kb.entity.User;
import com.kb.mapper.BrokerMapper;
import com.kb.mapper.KbMapper;
import com.kb.mapper.KbMemberMapper;
import com.kb.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限判断：超级管理员只做客户管理；业务数据按 broker 隔离。
 * <p>
 * 规则：
 * 1. admin（超管）：不能访问业务数据；
 * 2. broker_admin：本客户内等同知识库管理员；
 * 3. user：公开库（本客户）可查看；kb_member 覆盖；editor 可建库。
 */
@Service
public class PermissionService {

    public record CurrentUser(Long id, String username, String role, Long brokerId) {
        public boolean isSuperAdmin() {
            return "admin".equals(role);
        }

        /** @deprecated 用 isSuperAdmin；保留以免旧代码编译失败 */
        public boolean isAdmin() {
            return isSuperAdmin();
        }

        public boolean isBrokerAdmin() {
            return "broker_admin".equals(role);
        }
    }

    private final UserMapper users;
    private final KbMapper kbs;
    private final KbMemberMapper members;
    private final BrokerMapper brokers;

    public PermissionService(UserMapper users, KbMapper kbs, KbMemberMapper members, BrokerMapper brokers) {
        this.users = users;
        this.kbs = kbs;
        this.members = members;
        this.brokers = brokers;
    }

    /**
     * 当前登录用户。每次查库：停用、角色变更、broker 停用 / token_version 变更立即生效。
     */
    public CurrentUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        User u = users.selectOne(new QueryWrapper<User>().eq("username", auth.getName()));
        if (u == null || Integer.valueOf(0).equals(u.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在或已停用");
        }
        if (!"admin".equals(u.getRole())) {
            if (u.getBrokerId() == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号尚未分配客户，请联系管理员");
            }
            Broker b = brokers.selectById(u.getBrokerId());
            if (b == null || Integer.valueOf(0).equals(b.getEnabled())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用");
            }
            int expectedBv = b.getTokenVersion() == null ? 0 : b.getTokenVersion();
            int tokenBv = 0;
            Object details = auth.getDetails();
            if (details instanceof Claims claims) {
                Object bv = claims.get("bv");
                if (bv instanceof Number n) {
                    tokenBv = n.intValue();
                }
            }
            if (tokenBv != expectedBv) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
            }
        }
        return new CurrentUser(u.getId(), u.getUsername(), u.getRole(), u.getBrokerId());
    }

    public CurrentUser requireSuperAdmin() {
        CurrentUser me = current();
        if (!me.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要超级管理员权限");
        }
        return me;
    }

    /** 业务接口：拒绝超管 */
    public CurrentUser requireBusinessUser() {
        CurrentUser me = current();
        if (me.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "超级管理员不能访问业务数据");
        }
        if (me.brokerId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号尚未分配客户");
        }
        return me;
    }

    public CurrentUser requireBrokerAdmin() {
        CurrentUser me = requireBusinessUser();
        if (!me.isBrokerAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要客户管理员权限");
        }
        return me;
    }

    /** @deprecated 改为 requireSuperAdmin 或 requireBrokerAdmin */
    public CurrentUser requireAdmin() {
        return requireSuperAdmin();
    }

    /** 本客户下全部知识库 */
    public List<Kb> brokerKbs(Long brokerId) {
        if (brokerId == null) {
            return List.of();
        }
        return kbs.selectList(new QueryWrapper<Kb>().eq("broker_id", brokerId).orderByAsc("id"));
    }

    public Map<Long, String> kbRoles(CurrentUser me) {
        Map<Long, String> roles = new HashMap<>();
        if (me.isSuperAdmin() || me.brokerId() == null) {
            return roles;
        }
        List<Kb> all = brokerKbs(me.brokerId());
        if (me.isBrokerAdmin()) {
            all.forEach(kb -> roles.put(kb.getId(), "admin"));
            return roles;
        }
        all.stream()
                .filter(kb -> "public".equals(kb.getVisibility()))
                .forEach(kb -> roles.put(kb.getId(), "viewer"));
        members.selectList(new QueryWrapper<KbMember>().eq("user_id", me.id()))
                .forEach(m -> {
                    Kb kb = kbs.selectById(m.getKbId());
                    if (kb != null && Objects.equals(kb.getBrokerId(), me.brokerId())) {
                        roles.put(m.getKbId(), m.getRole());
                    }
                });
        return roles;
    }

    public List<Long> readableKbIds(CurrentUser me) {
        return kbRoles(me).keySet().stream().sorted().toList();
    }

    public Set<Long> editableKbIds(CurrentUser me) {
        return kbRoles(me).entrySet().stream()
                .filter(e -> "admin".equals(e.getValue()) || "editor".equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public boolean canRead(CurrentUser me, Long kbId) {
        return kbId != null && kbRoles(me).containsKey(kbId);
    }

    public boolean canEdit(CurrentUser me, Long kbId) {
        return kbId != null && editableKbIds(me).contains(kbId);
    }

    /** 能否新建知识库：客户管理员，或本客户内任一知识库的 editor */
    public boolean canCreateKb(CurrentUser me) {
        if (me.isBrokerAdmin()) {
            return true;
        }
        return editableKbIds(me).stream().anyMatch(id -> {
            String r = kbRoles(me).get(id);
            return "editor".equals(r) || "admin".equals(r);
        });
    }

    public void requireReadable(CurrentUser me, Document doc) {
        if (doc == null || !canRead(me, doc.getKbId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或无权访问");
        }
    }

    public void requireEditable(CurrentUser me, Document doc) {
        requireReadable(me, doc);
        if (!canEdit(me, doc.getKbId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有该知识库的编辑权限");
        }
    }

    /** 知识库必须属于当前用户的客户，否则 404 */
    public Kb requireBrokerKb(CurrentUser me, Long kbId) {
        Kb kb = kbs.selectById(kbId);
        if (kb == null || !Objects.equals(kb.getBrokerId(), me.brokerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或无权访问");
        }
        return kb;
    }

    /** 用户必须属于当前客户，否则 404 */
    public User requireBrokerUser(CurrentUser me, Long userId) {
        User u = users.selectById(userId);
        if (u == null || !Objects.equals(u.getBrokerId(), me.brokerId()) || "admin".equals(u.getRole())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在或无权访问");
        }
        return u;
    }
}
