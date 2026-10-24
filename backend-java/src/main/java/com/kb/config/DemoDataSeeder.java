package com.kb.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kb.entity.Broker;
import com.kb.entity.Kb;
import com.kb.entity.KbMember;
import com.kb.entity.User;
import com.kb.mapper.BrokerMapper;
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

import java.time.LocalDateTime;
import java.util.List;

/**
 * 演示数据：创建一个演示客户 DEMO，并在其下建知识库与账号。
 * 生产可设 KB_DEMO_SEED=false 关闭。
 * <pre>
 * 客户：DEMO（演示客户）
 * 客户管理员：broker01 / Broker@2026
 * 账号：hr01 / fin01 / eng01（同前）
 * </pre>
 */
@Component
@Order(1)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String DEMO_CODE = "DEMO";

    private static final List<String[]> KBS = List.of(
            new String[]{"人事制度", "员工手册、入职培训、考勤假期等人事管理制度"},
            new String[]{"财务制度", "差旅报销、费用管理等财务制度（仅财务人员可见）"},
            new String[]{"设备技术", "设备维护手册、点检记录、备件价格与库存"}
    );

    private static final List<String[]> USERS = List.of(
            new String[]{"hr01", "Hr@2026", "人事专员-李婷", "人事制度:editor"},
            new String[]{"fin01", "Fin@2026", "财务会计-陈静", "财务制度:editor,人事制度:viewer"},
            new String[]{"eng01", "Eng@2026", "设备工程师-王建国", "设备技术:editor,人事制度:viewer"}
    );

    private final KbMapper kbs;
    private final UserMapper users;
    private final KbMemberMapper members;
    private final BrokerMapper brokers;
    private final BCryptPasswordEncoder encoder;

    @Value("${KB_DEMO_SEED:true}")
    private boolean enabled;

    public DemoDataSeeder(KbMapper kbs, UserMapper users, KbMemberMapper members,
                          BrokerMapper brokers, BCryptPasswordEncoder encoder) {
        this.kbs = kbs;
        this.users = users;
        this.members = members;
        this.brokers = brokers;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Broker broker = brokers.selectOne(new QueryWrapper<Broker>().eq("code", DEMO_CODE));
        if (broker == null) {
            broker = new Broker();
            broker.setName("演示客户");
            broker.setCode(DEMO_CODE);
            broker.setEnabled(1);
            broker.setTokenVersion(0);
            broker.setCreateTime(LocalDateTime.now());
            broker.setUpdateTime(LocalDateTime.now());
            brokers.insert(broker);
            log.info("演示数据：创建客户 {}", DEMO_CODE);
        }
        Long brokerId = broker.getId();

        // 旧库治愈：已存在但未归属的演示账号/知识库挂到 DEMO（SchemaMigration 也会做全量孤儿迁移）
        for (User orphan : users.selectList(new QueryWrapper<User>()
                .isNull("broker_id").ne("username", "admin"))) {
            orphan.setBrokerId(brokerId);
            users.updateById(orphan);
            log.info("演示数据：治愈用户 {} → DEMO", orphan.getUsername());
        }
        if (users.selectCount(new QueryWrapper<User>().eq("username", "broker01")) > 0) {
            User ba0 = users.selectOne(new QueryWrapper<User>().eq("username", "broker01"));
            if (ba0 != null && ba0.getBrokerId() == null) {
                ba0.setBrokerId(brokerId);
                if (ba0.getRole() == null || "user".equals(ba0.getRole()) || "admin".equals(ba0.getRole())) {
                    ba0.setRole("broker_admin");
                }
                users.updateById(ba0);
            }
        }
        for (String[] k : KBS) {
            Kb orphanKb = kbs.selectOne(new QueryWrapper<Kb>().eq("name", k[0]).isNull("broker_id"));
            if (orphanKb != null) {
                orphanKb.setBrokerId(brokerId);
                kbs.updateById(orphanKb);
                log.info("演示数据：治愈知识库「{}」→ DEMO", k[0]);
            }
        }

        if (users.selectCount(new QueryWrapper<User>().eq("username", "broker01")) == 0) {
            User ba = new User();
            ba.setUsername("broker01");
            ba.setPassword(encoder.encode("Broker@2026"));
            ba.setDisplayName("演示客户管理员");
            ba.setRole("broker_admin");
            ba.setBrokerId(brokerId);
            ba.setEnabled(1);
            users.insert(ba);
            log.info("演示数据：创建客户管理员 broker01");
        }

        for (String[] k : KBS) {
            if (kbs.selectCount(new QueryWrapper<Kb>().eq("name", k[0]).eq("broker_id", brokerId)) == 0) {
                Kb kb = new Kb();
                kb.setName(k[0]);
                kb.setDescription(k[1]);
                kb.setVisibility("private");
                kb.setBrokerId(brokerId);
                kbs.insert(kb);
                log.info("演示数据：创建知识库「{}」", k[0]);
            }
        }
        for (String[] u : USERS) {
            if (users.selectCount(new QueryWrapper<User>().eq("username", u[0])) > 0) {
                continue;
            }
            User user = new User();
            user.setUsername(u[0]);
            user.setPassword(encoder.encode(u[1]));
            user.setDisplayName(u[2]);
            user.setRole("user");
            user.setBrokerId(brokerId);
            user.setEnabled(1);
            users.insert(user);
            for (String grant : u[3].split(",")) {
                String[] parts = grant.split(":");
                Kb kb = kbs.selectOne(new QueryWrapper<Kb>().eq("name", parts[0]).eq("broker_id", brokerId));
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
