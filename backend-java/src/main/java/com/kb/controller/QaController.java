package com.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.entity.Document;
import com.kb.entity.QaHistory;
import com.kb.mapper.DocumentMapper;
import com.kb.mapper.QaHistoryMapper;
import com.kb.service.PermissionService;
import com.kb.service.PermissionService.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能问答接口：把问题转发给 Python RAG 服务，并把每次问答保存到 qa_history 表。
 * <p>
 * 提供以下接口：
 * - /api/qa/ask             普通方式，等完整答案生成后一次性返回；
 * - /api/qa/ask/stream      流式方式（SSE），答案边生成边推送给前端，实现“打字机”效果；
 * - /api/qa/{id}/feedback   对某条回答点赞 / 点踩；
 * - /api/qa/history         问答历史，可按反馈、会话筛选。
 * <p>
 * 多轮对话：前端每次提问时带上 sessionId 和最近几轮 history，这里原样转给 Python，
 * 由 Python 负责把追问改写成完整问题后再检索。
 * <p>
 * 权限：每次提问都在这里算出“当前用户可查看的知识库 ID 列表”传给 Python，
 * Python 只在这些知识库中检索；问答历史普通用户只能看自己的，管理员可看全部。
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    /** 最多转发给 Python 的历史轮数（Python 端还会再截断一次） */
    private static final int MAX_HISTORY_ROUNDS = 3;

    /** 反馈原因最大长度，与数据库列 VARCHAR(500) 一致 */
    private static final int MAX_COMMENT_LENGTH = 500;

    /** Python RAG 服务地址，如 http://backend-python:8000 */
    private final String ragUrl;

    private final QaHistoryMapper history;

    private final DocumentMapper documents;

    private final PermissionService perm;

    /** 普通问答用的 HTTP 客户端 */
    private final RestClient rag;

    /** JSON 序列化工具 */
    private final ObjectMapper json = new ObjectMapper();

    private final com.kb.service.DatasetCatalog catalog;

    public QaController(QaHistoryMapper history, DocumentMapper documents, PermissionService perm,
                        @Value("${kb.rag-url}") String ragUrl, com.kb.service.DatasetCatalog catalog) {
        this.catalog = catalog;
        this.history = history;
        this.documents = documents;
        this.perm = perm;
        this.ragUrl = ragUrl;
        // 使用 HTTP/1.1，避免 h2c 升级被 uvicorn 拒绝
        this.rag = RestClient.builder()
                .baseUrl(ragUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    /**
     * 普通问答：POST /api/qa/ask
     * 请求体 {"question": "...", "documentId": 可选, "sessionId": 可选, "history": [{"question","answer"}]}。
     * 返回 Python 的结果，并附加 historyId（用于反馈）。
     */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, Object> req) throws Exception {
        CurrentUser user = perm.current();
        Map<String, Object> body = buildRagRequest(req, user);

        // 同步调用 Python，返回 {"answer": "...", "sources": [...], "rewritten_question": "..."}
        Map<?, ?> result = rag.post()
                .uri("/ask")
                .body(body)
                .retrieve()
                .body(Map.class);

        String question = String.valueOf(req.get("question"));
        Object rewritten = result.get("rewritten_question");
        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = result.get("analysis") instanceof Map<?, ?> a ? (Map<String, Object>) a : null;
        QaHistory saved = saveHistory(req, user,
                String.valueOf(result.get("answer")),
                result.get("sources"),
                question.equals(rewritten) ? null : (String) rewritten,
                result.get("mode") == null ? "doc" : String.valueOf(result.get("mode")), analysis);

        Map<String, Object> out = new HashMap<>();
        result.forEach((k, v) -> out.put(String.valueOf(k), v));
        out.put("historyId", saved.getId());
        return out;
    }

    /**
     * 流式问答：POST /api/qa/ask/stream，返回 text/event-stream。
     * <p>
     * Python 推送的每一行形如：data: {"type": "delta", "data": "文字片段"}
     * type 取值：rewrite（改写后的问题）、sources（引用片段）、delta（答案片段）、error（出错）、done（结束）。
     * 这里逐行原样转发给前端，同时把答案拼起来；收到 done 时先保存历史，
     * 再插入一条 {"type": "saved", "id": 历史ID} 事件，最后才转发 done，前端据此显示反馈按钮。
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> askStream(@RequestBody Map<String, Object> req) {
        // 权限必须在这里（请求线程）计算：流式输出在异步线程中执行，那里取不到登录信息
        CurrentUser user = perm.current();
        Map<String, Object> body = buildRagRequest(req, user);
        String question = String.valueOf(req.get("question"));

        // StreamingResponseBody 会在异步线程中执行，os 是写给前端的输出流
        StreamingResponseBody out = os -> {
            // 用 HttpURLConnection 直接读取 Python 的流式响应
            HttpURLConnection conn = (HttpURLConnection) URI.create(ragUrl + "/ask/stream").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.getOutputStream().write(json.writeValueAsBytes(body));

            StringBuilder answer = new StringBuilder();   // 拼接完整答案，用于保存历史
            Object sources = List.of();                   // 引用的原文片段
            String rewritten = null;                      // 改写后的问题
            String mode = "doc";                          // 路由结果
            Map<String, Object> analysis = new HashMap<>(); // 数据分析快照：sql / table / chart
            boolean saved = false;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        Map<?, ?> event = json.readValue(line.substring(6), Map.class);
                        Object type = event.get("type");
                        if ("delta".equals(type)) {
                            answer.append(event.get("data"));
                        } else if ("sources".equals(type)) {
                            sources = event.get("data");
                        } else if ("rewrite".equals(type)) {
                            rewritten = String.valueOf(event.get("data"));
                        } else if ("route".equals(type)) {
                            mode = String.valueOf(event.get("data"));
                        } else if ("sql".equals(type) || "table".equals(type) || "chart".equals(type)) {
                            analysis.put(String.valueOf(type), event.get("data"));
                        } else if ("done".equals(type) && !saved && answer.length() > 0) {
                            // 结束前先保存历史，把历史 ID 告诉前端
                            QaHistory qa = saveHistory(req, user, answer.toString(), sources, rewritten, mode, analysis);
                            saved = true;
                            writeLine(os, "data: " + json.writeValueAsString(Map.of("type", "saved", "id", qa.getId())));
                            writeLine(os, "");
                        }
                    }
                    // 原样转发并立即 flush，前端才能实时看到文字
                    writeLine(os, line);
                }
            }

            // 兜底：Python 异常断开、没有发送 done 时，也把已生成的部分保存下来
            if (!saved && answer.length() > 0) {
                saveHistory(req, user, answer.toString(), sources, rewritten, mode, analysis);
            }
        };

        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")    // 告诉 nginx 不要缓冲，否则会攒满才一次性输出
                .header("Cache-Control", "no-cache")
                .body(out);
    }

    /**
     * 答案反馈：POST /api/qa/{id}/feedback，请求体 {"value": 1 | -1 | 0, "comment": "可选"}。
     * value = 1 有用，-1 没用，0 取消反馈。
     */
    @PostMapping("/{id}/feedback")
    public ResponseEntity<?> feedback(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        CurrentUser me = perm.current();
        QaHistory record = history.selectById(id);
        // 只能对自己的问答反馈（管理员除外）
        if (record == null || (!me.isAdmin() && !me.id().equals(record.getUserId()))) {
            return ResponseEntity.status(404).body(Map.of("error", "问答记录不存在"));
        }
        if (!(req.get("value") instanceof Number num)
                || (num.intValue() != 1 && num.intValue() != -1 && num.intValue() != 0)) {
            return ResponseEntity.badRequest().body(Map.of("error", "value 只能是 1、-1 或 0"));
        }
        int value = num.intValue();
        String comment = req.get("comment") == null ? null : String.valueOf(req.get("comment")).trim();
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            comment = comment.substring(0, MAX_COMMENT_LENGTH);
        }

        // 用 UpdateWrapper 才能把字段显式更新为 NULL（取消反馈、点赞时清空原因）
        UpdateWrapper<QaHistory> update = new UpdateWrapper<QaHistory>().eq("id", id);
        if (value == 0) {
            update.set("feedback", null)
                    .set("feedback_comment", null)
                    .set("feedback_time", null);
        } else {
            update.set("feedback", value)
                    .set("feedback_comment", value == -1 && comment != null && !comment.isEmpty() ? comment : null)
                    .set("feedback_time", LocalDateTime.now());
        }
        history.update(null, update);
        return ResponseEntity.ok(history.selectById(id));
    }

    /**
     * 问答历史：GET /api/qa/history，按时间倒序。
     * 可选参数：feedback = up / down / none（筛选有用、没用、未反馈），sessionId，limit（默认 100，最多 500），
     * username（仅管理员可用，查看某个用户的记录）。普通用户只能看到自己的记录。
     */
    @GetMapping("/history")
    public List<QaHistory> list(@RequestParam(required = false) String feedback,
                                @RequestParam(required = false) String sessionId,
                                @RequestParam(required = false) String username,
                                @RequestParam(defaultValue = "100") int limit) {
        QueryWrapper<QaHistory> query = scoped(username);
        if ("up".equals(feedback)) {
            query.eq("feedback", 1);
        } else if ("down".equals(feedback)) {
            query.eq("feedback", -1);
        } else if ("none".equals(feedback)) {
            query.isNull("feedback");
        }
        if (sessionId != null && !sessionId.isBlank()) {
            query.eq("session_id", sessionId);
        }
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return history.selectList(query.orderByDesc("id").last("limit " + safeLimit));
    }

    /**
     * 反馈统计：GET /api/qa/feedback/stats，返回总数、有用数、没用数、未反馈数。
     */
    @GetMapping("/feedback/stats")
    public Map<String, Long> stats(@RequestParam(required = false) String username) {
        long total = history.selectCount(scoped(username));
        long up = history.selectCount(scoped(username).eq("feedback", 1));
        long down = history.selectCount(scoped(username).eq("feedback", -1));
        return Map.of("total", total, "up", up, "down", down, "none", total - up - down);
    }

    /** 按当前用户限定查询范围：普通用户只看自己的；管理员看全部，或按 username 筛选 */
    private QueryWrapper<QaHistory> scoped(String username) {
        CurrentUser me = perm.current();
        QueryWrapper<QaHistory> query = new QueryWrapper<>();
        if (!me.isAdmin()) {
            query.eq("user_id", me.id());
        } else if (username != null && !username.isBlank()) {
            query.eq("username", username);
        }
        return query;
    }

    /**
     * 组装发给 Python 的请求体：问题、限定文档、最近几轮历史、可检索的知识库列表。
     * - 指定了文档：必须有该文档所在知识库的查看权限，只在该文档中检索；
     * - 指定了知识库：必须有查看权限，只在该知识库中检索；
     * - 都没指定：在用户可查看的全部知识库中检索。
     */
    private Map<String, Object> buildRagRequest(Map<String, Object> req, CurrentUser user) {
        List<Long> kbIds;
        if (req.get("documentId") instanceof Number docId) {
            Document doc = documents.selectById(docId.longValue());
            perm.requireReadable(user, doc);
            kbIds = List.of(doc.getKbId());
        } else if (req.get("kbId") instanceof Number kbId) {
            if (!perm.canRead(user, kbId.longValue())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有该知识库的查看权限");
            }
            kbIds = List.of(kbId.longValue());
        } else {
            kbIds = perm.readableKbIds(user);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("question", req.get("question"));
        body.put("document_id", req.get("documentId"));   // 允许为 null，表示检索全部文档
        body.put("kb_ids", kbIds);
        body.put("history", cleanHistory(req.get("history")));
        // 问数：模式 + 有权限的已就绪数据集元数据（指定文档时只做文档问答）
        Object mode = req.get("mode");
        body.put("mode", mode == null ? "auto" : String.valueOf(mode));
        List<Long> dsIds = null;
        if (req.get("datasetIds") instanceof List<?> l && !l.isEmpty()) {
            dsIds = l.stream().filter(x -> x instanceof Number).map(x -> ((Number) x).longValue()).toList();
        }
        body.put("dataset_ids", dsIds);
        body.put("datasets", req.get("documentId") != null ? List.of() : catalog.forQuestion(kbIds, dsIds));
        return body;
    }

    /** 只保留历史中的 question / answer 字段，并截取最近 MAX_HISTORY_ROUNDS 轮 */
    private List<Map<String, String>> cleanHistory(Object raw) {
        List<Map<String, String>> rounds = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && m.get("question") != null && m.get("answer") != null) {
                    Map<String, String> r = new HashMap<>();
                    r.put("question", String.valueOf(m.get("question")));
                    r.put("answer", String.valueOf(m.get("answer")));
                    if (m.get("sql") != null) {
                        String sql = String.valueOf(m.get("sql"));
                        r.put("sql", sql.length() > 4000 ? sql.substring(0, 4000) : sql);
                    }
                    rounds.add(r);
                }
            }
        }
        int from = Math.max(0, rounds.size() - MAX_HISTORY_ROUNDS);
        return rounds.subList(from, rounds.size());
    }

    /** 保存一条问答历史，返回保存后的记录（含自增 ID） */
    private QaHistory saveHistory(Map<String, Object> req, CurrentUser user, String answer,
                                  Object sources, String rewritten) throws java.io.IOException {
        return saveHistory(req, user, answer, sources, rewritten, null, null);
    }

    private QaHistory saveHistory(Map<String, Object> req, CurrentUser user, String answer, Object sources,
                                  String rewritten, String mode, Map<String, Object> analysis) throws java.io.IOException {
        QaHistory qa = new QaHistory();
        qa.setMode(mode == null ? "doc" : mode);
        if (analysis != null && !analysis.isEmpty()) {
            qa.setAnalysis(json.writeValueAsString(trimAnalysis(analysis)));
        }
        qa.setQuestion(String.valueOf(req.get("question")));
        qa.setAnswer(answer);
        qa.setSources(json.writeValueAsString(sources));
        qa.setRewrittenQuestion(rewritten);
        if (req.get("sessionId") != null) {
            String sid = String.valueOf(req.get("sessionId"));
            qa.setSessionId(sid.length() > 64 ? sid.substring(0, 64) : sid);
        }
        if (user != null) {
            qa.setUsername(user.username());
            qa.setUserId(user.id());
        }
        if (req.get("kbId") instanceof Number kbId) {
            qa.setKbId(kbId.longValue());
        }
        if (req.get("documentId") instanceof Number docId) {
            qa.setDocumentId(docId.longValue());
        }
        history.insert(qa);
        return qa;
    }

    /** 历史里只保存结果的前 50 行 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> trimAnalysis(Map<String, Object> a) {
        Map<String, Object> out = new HashMap<>(a);
        if (out.get("table") instanceof Map<?, ?> t) {
            Map<String, Object> tt = new HashMap<>((Map<String, Object>) t);
            if (tt.get("rows") instanceof List<?> rows && rows.size() > 50) {
                tt.put("rows", rows.subList(0, 50));
                tt.put("truncated", true);
            }
            out.put("table", tt);
        }
        return out;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.kb.mapper.DatasetMapper datasetMapper;

    /**
     * 导出分析结果：GET /api/qa/{id}/export.csv
     * 不信任前端传来的 SQL：按 historyId 取出保存的 SQL，由 Python 重新校验并执行（≤10000 行）。
     * 只有提问人本人或管理员可以导出，且仍需对数据集所在知识库有查看权限。
     */
    @GetMapping("/{id}/export.csv")
    @SuppressWarnings("unchecked")
    public ResponseEntity<byte[]> exportCsv(@PathVariable Long id) throws java.io.IOException {
        CurrentUser me = perm.current();
        QaHistory qa = history.selectById(id);
        if (qa == null || (!me.isAdmin() && !me.id().equals(qa.getUserId()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        }
        if (qa.getAnalysis() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这条问答没有数据分析结果");
        }
        Map<String, Object> a = json.readValue(qa.getAnalysis(), Map.class);
        Map<String, Object> sql = a.get("sql") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> ds = sql.get("dataset") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (!(ds.get("id") instanceof Number dsId) || sql.get("sql") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这条问答没有可导出的查询");
        }
        com.kb.entity.Dataset dataset = datasetMapper.selectById(dsId.longValue());
        if (dataset == null || !perm.canRead(me, dataset.getKbId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据集不存在或无权访问");
        }
        if (!"ready".equals(dataset.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "数据集当前不可用");
        }
        Map<String, Object> result;
        try {
            result = rag.post().uri("/datasets/query").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("dataset_id", dsId.longValue(), "sql", String.valueOf(sql.get("sql")), "limit", 10000))
                    .retrieve().body(Map.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "重新执行查询失败");
        }
        StringBuilder csv = new StringBuilder("\uFEFF");   // BOM：Excel 打开中文不乱码
        csv.append(csvLine((List<Object>) result.get("columns")));
        for (Object row : (List<Object>) result.get("rows")) {
            csv.append(csvLine((List<Object>) row));
        }
        String name = java.net.URLEncoder.encode("分析结果-" + id + ".csv", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"export-" + id + ".csv\"; filename*=UTF-8''" + name)
                .header("X-Truncated", String.valueOf(Boolean.TRUE.equals(result.get("truncated"))))
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String csvLine(List<Object> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            Object v = cells.get(i);
            String s = v == null ? "" : String.valueOf(v);
            // 防 CSV 公式注入：以 = + - @ 开头的文本前加单引号（数字除外）
            if (!(v instanceof Number) && !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
            if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
                s = "\"" + s.replace("\"", "\"\"") + "\"";
            }
            sb.append(s);
        }
        return sb.append("\r\n").toString();
    }

    /** 写一行并立即刷新到前端 */
    private static void writeLine(java.io.OutputStream os, String line) throws java.io.IOException {
        os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
