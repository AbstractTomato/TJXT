package com.tianji.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 多轮对话历史管理。
 * <p>
 * 使用 Redis List 存储，滑动窗口保留最近 5 轮（10 条消息），
 * 24 小时 TTL 自动过期。不做 LLM 压缩——教育场景轮次少，不需要。
 * <p>
 * Redis Key: agent:session:{sessionId}
 * 每轮: user → "{\"role\":\"user\",\"content\":\"...\"}"
 *       assistant → "{\"role\":\"assistant\",\"content\":\"...\"}"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationHistoryService {

    private static final String KEY_PREFIX = "agent:session:";
    private static final int MAX_MESSAGES = 10;  // 5 轮 × 每轮一问一答
    private static final int TTL_HOURS = 24;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 获取会话的对话历史。
     *
     * @param sessionId 会话 ID（为 null 返回空）
     * @return 格式化的对话历史文本，可直接拼入 Prompt
     */
    public String getHistoryText(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "";
        }
        try {
            List<String> messages = stringRedisTemplate.opsForList()
                    .range(KEY_PREFIX + sessionId, 0, -1);
            if (messages == null || messages.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("【对话历史】\n");
            for (String msg : messages) {
                // msg 已是 JSON {"role":"...","content":"..."}，简单解析提取
                String role = extractField(msg, "role");
                String content = extractField(msg, "content");
                if (role != null && content != null) {
                    String label = "user".equals(role) ? "学生" : "助教";
                    sb.append(label).append("：").append(content).append("\n");
                }
            }
            sb.append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("读取对话历史失败, sessionId={}", sessionId, e);
            return "";
        }
    }

    /**
     * 追加一轮对话到历史（user 提问 + assistant 回答）。
     */
    public void append(String sessionId, String question, String answer) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        try {
            String key = KEY_PREFIX + sessionId;
            String userMsg = buildJson("user", question);
            String assistantMsg = buildJson("assistant", answer);
            stringRedisTemplate.opsForList().rightPushAll(key, userMsg, assistantMsg);
            stringRedisTemplate.opsForList().trim(key, 0, MAX_MESSAGES - 1);  // 只保留最近 N 条
            stringRedisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS);
            log.debug("对话历史已追加, sessionId={}, 轮数新增 1", sessionId);
        } catch (Exception e) {
            log.warn("保存对话历史失败, sessionId={}", sessionId, e);
        }
    }

    private String buildJson(String role, String content) {
        // 简单手写 JSON，避免 import Jackson/Hutool
        return "{\"role\":\"" + escape(role) + "\",\"content\":\"" + escape(content) + "\"}";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 从手写 JSON 中提取字段值（极简解析，不引入 Jackson）。
     */
    private String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"') {
                // 检查是否被转义
                int bsCount = 0;
                for (int j = end - 1; j >= start && json.charAt(j) == '\\'; j--) {
                    bsCount++;
                }
                if (bsCount % 2 == 0) {
                    break; // 真正的引号结束
                }
            }
            end++;
        }
        return json.substring(start, end)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }
}
