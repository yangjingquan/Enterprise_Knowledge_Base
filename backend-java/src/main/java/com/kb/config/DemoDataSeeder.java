package com.kb.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.entity.Kb;
import com.kb.entity.KbMember;
import com.kb.entity.User;
import com.kb.mapper.KbMapper;
import com.kb.mapper.KbMemberMapper;
import com.kb.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示数据：三个部门知识库和三个部门账号，展示“按部门授权”的效果。
 * <p>
 * 只在数据不存在时创建（按名称判断），已存在的不会覆盖，管理员在页面上的修改会保留。
 * 生产环境可设置环境变量 KB_DEMO_SEED=false 关闭。
 * <pre>
 * 知识库：人事制度、财务制度、设备技术（均为私有）；公共知识库（公开，由 SchemaMigration 创建）
 * 账号：
 *   hr01  / Hr@2026   人事专员   人事制度 editor
 *   fin01 / Fin@2026  财务会计   财务制度 editor，人事制度 viewer
 *   eng01 / Eng@2026  设备工程师 设备技术 editor，人事制度 viewer
 * </pre>
 */
@Component
@Order(1)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** 演示知识库：名称、说明 */
    private static final List<String[]> KBS = List.of(
            new String[]{"人事制度", "员工手册、入职培训、考勤假期等人事管理制度"},
            new String[]{"财务制度", "差旅报销、费用管理等财务制度（仅财务人员可见）"},
            new String[]{"设备技术", "设备维护手册、点检记录、备件价格与库存"}
    );

    /** 演示账号：用户名、密码、显示名称、授权（知识库名:角色） */
    private static final List<String[]> USERS = List.of(
            new String[]{"hr01", "Hr@2026", "人事专员-李婷", "人事制度:editor"},
            new String[]{"fin01", "Fin@2026", "财务会计-陈静", "财务制度:editor,人事制度:viewer"},
            new String[]{"eng01", "Eng@2026", "设备工程师-王建国", "设备技术:editor,人事制度:viewer"}
    );

    private final KbMapper kbs;
    private final UserMapper users;
    private final KbMemberMapper members;
    private final BCryptPasswordEncoder encoder;

    @Value("${KB_DEMO_SEED:true}")
    private boolean enabled;

    public DemoDataSeeder(KbMapper kbs, UserMapper users, KbMemberMapper members, BCryptPasswordEncoder encoder) {
        this.kbs = kbs;
        this.users = users;
        this.members = members;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        for (String[] k : KBS) {
            if (kbs.selectCount(new QueryWrapper<Kb>().eq("name", k[0])) == 0) {
                Kb kb = new Kb();
                kb.setName(k[0]);
                kb.setDescription(k[1]);
                kb.setVisibility("private");
                kbs.insert(kb);
                log.info("演示数据：创建知识库「{}」", k[0]);
            }
        }
        for (String[] u : USERS) {
            if (users.selectCount(new QueryWrapper<User>().eq("username", u[0])) > 0) {
                continue;   // 已存在的账号不覆盖（密码、授权可能已被管理员修改）
            }
            User user = new User();
            user.setUsername(u[0]);
            user.setPassword(encoder.encode(u[1]));
            user.setDisplayName(u[2]);
            user.setRole("user");
            user.setEnabled(1);
            users.insert(user);
            for (String grant : u[3].split(",")) {
                String[] parts = grant.split(":");
                Kb kb = kbs.selectOne(new QueryWrapper<Kb>().eq("name", parts[0]));
                KbMember m = new KbMember();
                m.setKbId(kb.getId());
                m.setUserId(user.getId());
                m.setRole(parts[1]);
                members.insert(m);
            }
            log.info("演示数据：创建账号 {}", u[0]);
        }
    }
}
