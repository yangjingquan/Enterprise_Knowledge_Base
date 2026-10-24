package com.kb.controller;

import com.kb.service.PermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 效果评测接口（仅客户管理员）：转发给 Python 的评测服务。
 * - POST /api/admin/eval/run    启动评测（后台执行，约需数分钟），请求体 {"judge": false}
 * - GET  /api/admin/eval/status 查询进度和最近一次的评测报告（Markdown）
 */
@RestController
@RequestMapping("/api/admin/eval")
public class EvalController {

    private final RestClient rag;
    private final PermissionService perm;

    public EvalController(PermissionService perm, @Value("${kb.rag-url}") String ragUrl) {
        this.perm = perm;
        // 评测在 Python 后台线程执行，这里只是启动和查询状态，用普通超时
        this.rag = com.kb.service.RagHttp.normal(ragUrl);
    }

    @PostMapping("/run")
    public Map<?, ?> run(@RequestBody(required = false) Map<String, Object> req) {
        perm.requireBrokerAdmin();
        return rag.post()
                .uri("/eval/run")
                .body(req == null ? Map.of() : req)
                .retrieve()
                .body(Map.class);
    }

    @GetMapping("/status")
    public Map<?, ?> status() {
        perm.requireBrokerAdmin();
        return rag.get()
                .uri("/eval/status")
                .retrieve()
                .body(Map.class);
    }
}
