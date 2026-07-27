package com.tianji.agent.service.impl;

import com.tianji.agent.domain.dto.ChatChunk;
import com.tianji.agent.domain.dto.ChatRequest;
import com.tianji.agent.domain.dto.RetrievalResult;
import com.tianji.agent.domain.enums.Confidence;
import com.tianji.agent.service.ChatService;
import com.tianji.agent.service.ConversationHistoryService;
import com.tianji.agent.service.PromptBuilder;
import com.tianji.agent.service.RetrievalService;
import com.tianji.api.client.learning.LearningClient;
import com.tianji.api.dto.learning.QuestionFormDTO;
import com.tianji.common.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 对话服务实现（Phase 4 RAG 管线）。
 * <p>
 * 完整流程：检索 → 对话历史 → Prompt 构造 → LLM 流式生成 → 自信度 → SSE 输出。
 *
 * @see ChatService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final int TOP_K = 5;
    private static final double HIGH_THRESHOLD = 0.025;
    private static final double MEDIUM_THRESHOLD = 0.012;

    private final RetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final ConversationHistoryService historyService;
    private final OpenAiStreamingChatModel streamingChatModel;
    private final LearningClient learningClient;

    @Override
    public Flux<ChatChunk> chat(ChatRequest request) {
        log.info("收到对话请求: question={}, courseId={}, sessionId={}",
                request.getQuestion(), request.getCourseId(), request.getSessionId());

        String messageId = UUID.randomUUID().toString().substring(0, 8);

        String sessionId = request.getSessionId() != null && !request.getSessionId().trim().isEmpty()
                ? request.getSessionId() : UUID.randomUUID().toString().substring(0, 8);

        // 1. 检索
        List<RetrievalResult> results = retrievalService.retrieve(
                request.getQuestion(), request.getCourseId(), TOP_K);

        // 2. 判定自信度
        Confidence confidence = computeConfidence(results);

        //异步创建Question
        if (confidence == Confidence.LOW){
            CompletableFuture.runAsync(() -> {
                try {
                    QuestionFormDTO dto = new QuestionFormDTO();
                    dto.setCourseId(request.getCourseId());
                    dto.setTitle(truncate(request.getQuestion(), 100));
                    dto.setDescription(request.getQuestion());
                    learningClient.createQuestion(dto);
                    log.info("已自动创建Question, sessionId = {}", sessionId);
                } catch (Exception e) {
                    log.error("创建问题失败", e);
                }
            });
        }

        // 3. 对话历史
        String history = historyService.getHistoryText(sessionId);

        // 4. 构造 Prompt（根据自信度调整 LLM 回答策略）
        String prompt = promptBuilder.build(results, history, request.getQuestion(), confidence);

        // 5. 构建 sources JSON
        String sourcesJson = buildSourcesJson(results);

        // 6. 流式生成
        return Flux.create(sink -> {
            try {
                streamingChatModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
                    @Override
                    public void onNext(String token) {
                        sink.next(ChatChunk.builder()
                                .messageId(messageId)
                                .content(token)
                                .finished(false)
                                .sessionId(sessionId)
                                .build());
                    }

                    @Override
                    public void onComplete(dev.langchain4j.model.output.Response<AiMessage> response) {
                        sink.next(ChatChunk.builder()
                                .messageId(messageId)
                                .content("")
                                .finished(true)
                                .confidence(confidence)
                                .sources(sourcesJson)
                                .sessionId(sessionId)
                                .build());
                        sink.complete();

                        // 保存对话历史
                        String answer = response.content().text();
                        historyService.append(sessionId, request.getQuestion(), answer);
                        log.info("对话完成: messageId={}, confidence={}, 检索结果={}",
                                messageId, confidence, results.size());
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("LLM 流式生成失败, messageId={}", messageId, error);
                        sink.next(ChatChunk.builder()
                                .messageId(messageId)
                                .content("抱歉，助教暂时无法回答，请稍后重试。")
                                .finished(true)
                                .confidence(Confidence.LOW)
                                .sources("[]")
                                .sessionId(sessionId)
                                .build());
                        sink.complete();
                    }
                });
            } catch (Exception e) {
                log.error("LLM 调用异常, messageId={}", messageId, e);
                sink.next(ChatChunk.builder()
                        .messageId(messageId)
                        .content("抱歉，助教暂时无法回答，请稍后重试。")
                        .finished(true)
                        .confidence(Confidence.LOW)
                        .sources("[]")
                        .sessionId(sessionId)
                        .build());
                sink.complete();
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 基于检索结果中最高 RRF 分数判定自信度。
     * <p>
     * 阈值：≥ 0.08 → HIGH, ≥ 0.04 → MEDIUM, &lt; 0.04 → LOW。
     * 阈值在部署后可根据实际日志调优。
     */
    private Confidence computeConfidence(List<RetrievalResult> results) {
        double maxScore = results.stream()
                .mapToDouble(RetrievalResult::getRrfScore)
                .max()
                .orElse(0.0);
        Confidence confidence;
        if (maxScore >= HIGH_THRESHOLD)   confidence = Confidence.HIGH;
        else if (maxScore >= MEDIUM_THRESHOLD) confidence = Confidence.MEDIUM;
        else confidence = Confidence.LOW;
        log.info("自信度判定: maxRRFScore={}, confidence={} (阈值 HIGH≥{} MEDIUM≥{})",
                String.format("%.4f", maxScore), confidence, HIGH_THRESHOLD, MEDIUM_THRESHOLD);
        return confidence;
    }

    /**
     * 构建 sources JSON 字符串（前端展示引用来源）。
     */
    private String buildSourcesJson(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> sources = results.stream().map(r -> {
            var c = r.getChunk();
            return Map.<String, Object>of(
                    "courseName", c.getCourseName() != null ? c.getCourseName() : "",
                    "chapterTitle", c.getChapterTitle() != null ? c.getChapterTitle() : "",
                    "sourceType", c.getSourceType() != null ? c.getSourceType() : "",
                    "excerpt", excerpt(c.getContent()),
                    "score", Math.round(r.getRrfScore() * 10000.0) / 10000.0
            );
        }).collect(Collectors.toList());
        return JsonUtils.toJsonStr(sources);
    }

    private String excerpt(String content) {
        if (content == null) return "";
        if (content.length() <= 80) return content;
        return content.substring(0, 80) + "…";
    }

    private String truncate(String s, int maxLen){
        if (s == null || s.isEmpty()){
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
