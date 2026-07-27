package com.tianji.agent.service;

import com.tianji.agent.domain.dto.RetrievalResult;
import com.tianji.agent.domain.enums.Confidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Prompt 构造器。
 * <p>
 * 将检索结果 + 对话历史 + 学生问题组装为 LLM 可理解的完整 Prompt。
 * 纯字符串模板，无 Bean 依赖，可独立单元测试。
 */
@Slf4j
@Component
public class PromptBuilder {

    /**
     * Prompt 骨架模板。
     * {confidence_hint} 根据自信度动态替换：
     *   HIGH   → 直接回答
     *   MEDIUM → 追加免责声明
     *   LOW    → 告知无法回答，建议等待讲师
     */
    private static final String SYSTEM_INSTRUCTION =
            "你是天机助教，一个课程问答助手。请根据以下【参考资料】回答学生的问题。\n" +
            "\n" +
            "{confidence_hint}\n" +
            "4. 回答使用中文，语言友好、简洁\n" +
            "5. 不要编造参考资料中没有的信息\n" +
            "\n" +
            "【参考资料】\n" +
            "{references}\n" +
            "\n" +
            "{history}" +
            "【学生问题】\n" +
            "{question}";

    private static final String REFERENCE_TEMPLATE =
            "[{index}] (课程《{courseName}》, 章节《{chapterTitle}》): {content}";

    private static final String HINT_HIGH =
            "要求：\n" +
            "1. 参考资料足以回答问题，请准确、简洁地回答\n" +
            "2. 如果参考资料部分相关但不完整，请基于已有信息回答，并说明\"以上信息基于已有课程资料，可能不完整\"\n" +
            "3. 如果参考资料完全不相关，请如实说\"抱歉，课程资料中暂无相关信息，建议等待讲师回复\"";

    private static final String HINT_MEDIUM =
            "注意：当前检索到的参考资料相关度一般，可能不够完整。\n" +
            "要求：\n" +
            "1. 如果参考资料部分相关，请基于已有信息回答，但必须在回答开头加上\"【仅供参考】以下信息基于已有课程资料，可能不完整，建议确认后使用\"\n" +
            "2. 如果参考资料不足以回答，请如实说\"抱歉，课程资料中暂无相关信息，建议等待讲师回复\"\n" +
            "3. 不要编造参考资料中没有的信息";

    private static final String HINT_LOW =
            "注意：未检索到足够相关的课程资料，你无法确认答案。\n" +
            "要求：\n" +
            "1. 如实告诉学生\"抱歉，课程资料中暂无相关信息，已自动转交给讲师，请等待讲师回复\"\n" +
            "2. 不要尝试猜测或编造答案\n" +
            "3. 语气友好，可以安抚学生";

    /**
     * 构建完整 Prompt。
     *
     * @param results    检索结果（已按 RRF 分数降序）
     * @param history    对话历史文本（可为空字符串）
     * @param question   学生问题
     * @param confidence 自信度级别（决定 LLM 回答策略）
     * @return 完整 Prompt 字符串
     */
    public String build(List<RetrievalResult> results, String history,
                         String question, Confidence confidence) {
        String references = buildReferences(results);
        String hint = confidenceHint(confidence);
        String prompt = SYSTEM_INSTRUCTION
                .replace("{confidence_hint}", hint)
                .replace("{references}", references)
                .replace("{history}", history)
                .replace("{question}", question);
        log.info("Prompt 构造完成: 参考块数={}, confidence={}, 总长度={}",
                results.size(), confidence, prompt.length());
        return prompt;
    }

    private String confidenceHint(Confidence confidence) {
        if (confidence == null) return HINT_MEDIUM;
        switch (confidence) {
            case HIGH:   return HINT_HIGH;
            case MEDIUM: return HINT_MEDIUM;
            case LOW:    return HINT_LOW;
            default:     return HINT_MEDIUM;
        }
    }

    /**
     * 将检索结果格式化为编号的参考资料列表。
     */
    private String buildReferences(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return "（无相关资料）";
        }
        return IntStream.range(0, results.size())
                .mapToObj(i -> {
                    var r = results.get(i);
                    var c = r.getChunk();
                    return REFERENCE_TEMPLATE
                            .replace("{index}", String.valueOf(i + 1))
                            .replace("{courseName}", nvl(c.getCourseName()))
                            .replace("{chapterTitle}", nvl(c.getChapterTitle()))
                            .replace("{content}", nvl(c.getContent()));
                })
                .collect(Collectors.joining("\n"));
    }

    private String nvl(String s) {
        return s != null ? s : "—";
    }
}
