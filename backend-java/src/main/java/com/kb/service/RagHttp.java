package com.kb.service;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 调用 Python RAG 服务的 HTTP 客户端工厂，统一超时：
 * <ul>
 *   <li>连接超时 5 秒（Python 服务不在线时快速失败）；</li>
 *   <li>普通接口读取超时 60 秒（删除向量、上下文、知识库同步、网页下载、评测状态等）；</li>
 *   <li>长任务读取超时 300 秒（文档解析入库、数据集解析导入、问答与流式问答）。</li>
 * </ul>
 * 使用 SimpleClientHttpRequestFactory（HTTP/1.1）：JDK HttpClient 会尝试 h2c 升级，uvicorn 不支持。
 */
public final class RagHttp {

    public static final int CONNECT_TIMEOUT_MS = 5_000;
    public static final int NORMAL_TIMEOUT_MS = 60_000;
    public static final int LONG_TIMEOUT_MS = 300_000;

    private RagHttp() {
    }

    public static SimpleClientHttpRequestFactory factory(int readTimeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONNECT_TIMEOUT_MS);
        f.setReadTimeout(readTimeoutMs);
        return f;
    }

    /** 普通接口客户端（读取超时 60 秒） */
    public static RestClient normal(String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory(NORMAL_TIMEOUT_MS)).build();
    }

    /** 长任务客户端（读取超时 300 秒） */
    public static RestClient longRunning(String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory(LONG_TIMEOUT_MS)).build();
    }
}
