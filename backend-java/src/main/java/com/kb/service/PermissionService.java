package com.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.entity.Document;
import com.kb.entity.Kb;
import com.kb.entity.KbMember;
import com.kb.entity.User;
import com.kb.mapper.KbMapper;
import com.kb.mapper.KbMemberMapper;
import com.kb.mapper.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限判断服务：所有“能不能看 / 能不能改”的判断都集中在这里，Controller 只调用不重复写规则。
 * <p>
 * 规则：
 * 1. 系统管理员（role = admin）：可查看和管理全部知识库、文档、用户；
 * 2. 普通用户：
 *    - 公开知识库（visibility = public）：可查看（问答、看原文）；
 *    - 被授权为 viewer 的知识库：可查看；
 *    - 被授权为 editor 的知识库：可查看，并可上传、导入、删除文档；
 *    - 其他知识库：不可见，问答检索时也不会用到其中的内容。
 * 3. 权限在 Java 端计算后，把“可查看的知识库 ID 列表”传给 Python，Python 检索时按列表过滤。
 */
@Service
public class PermissionService {

    /** 当前登录用户 */
    public record CurrentUser(Long id, String username, String role) {
        public boolean isAdmin() {
            return "admin".equals(role);
        }
    }

    private final UserMapper users;
    private final KbMapper kbs;
    private final KbMemberMapper members;

    public PermissionService(UserMapper users, KbMapper kbs, KbMemberMapper members) {
        this.users = users;
        this.kbs = kbs;
        this.members = members;
    }

    /**
     * 取当前登录用户。每次都查一次数据库：用户被停用或角色被修改后立即生效，
     * 不用等 token 过期。未登录或已停用时抛出 401。
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
        return new CurrentUser(u.getId(), u.getUsername(), u.getRole());
    }

    /** 要求管理员，否则 403 */
    public CurrentUser requireAdmin() {
        CurrentUser me = current();
        if (!me.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return me;
    }

    /**
     * 用户在各知识库中的角色：{知识库ID: "admin" / "editor" / "viewer"}，不包含无权限的知识库。
     */
    public Map<Long, String> kbRoles(CurrentUser me) {
        Map<Long, String> roles = new HashMap<>();
        List<Kb> all = kbs.selectList(null);
        if (me.isAdmin()) {
            all.forEach(kb -> roles.put(kb.getId(), "admin"));
            return roles;
        }
        // 公开知识库默认可查看
        all.stream()
                .filter(kb -> "public".equals(kb.getVisibility()))
                .forEach(kb -> roles.put(kb.getId(), "viewer"));
        // 授权记录覆盖默认值（例如公开库中被授权为 editor）
        members.selectList(new QueryWrapper<KbMember>().eq("user_id", me.id()))
                .forEach(m -> roles.put(m.getKbId(), m.getRole()));
        return roles;
    }

    /** 可查看的知识库 ID 列表 */
    public List<Long> readableKbIds(CurrentUser me) {
        return kbRoles(me).keySet().stream().sorted().toList();
    }

    /** 可编辑（上传 / 删除文档）的知识库 ID 集合 */
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

    /**
     * 校验文档可查看。无权限时返回 404 而不是 403：不向无权限的人透露“这个文档存在”。
     */
    public void requireReadable(CurrentUser me, Document doc) {
        if (doc == null || !canRead(me, doc.getKbId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或无权访问");
        }
    }

    /** 校验文档可编辑，否则 403 */
    public void requireEditable(CurrentUser me, Document doc) {
        requireReadable(me, doc);
        if (!canEdit(me, doc.getKbId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有该知识库的编辑权限");
        }
    }
}
