package com.tianji.agent.service.impl;

import com.tianji.agent.domain.dto.RetrievalResult;
import com.tianji.agent.domain.po.DocChunk;
import com.tianji.agent.repository.KnowledgeEsRepository;
import com.tianji.agent.service.RetrievalService;
import com.tianji.agent.service.TermAliasService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 检索管线实现。
 * <p>
 * 流水线：术语标准化 → embedding（带 Redis 缓存）→ 向量检索 + 关键词检索（并行）
 * → RRF 融合去重排序 → 返回 Top-K（含 RRF 分数，供 Phase 4 自信度判定）。
 * <p>
 * RRF 公式：score(d) = Σ 1/(k + rank_i(d))，k = 60，rank 从 1 开始。
 *
 * @see RetrievalService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalServiceImpl implements RetrievalService {

    private static final String EMBEDDING_CACHE_PREFIX = "agent:embedding:";
    private static final int EMBEDDING_CACHE_TTL_HOURS = 1;
    private static final int RRF_K = 60;

    private final TermAliasService termAliasService;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeEsRepository knowledgeEsRepository;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public List<RetrievalResult> retrieve(String question, Long courseId, int topK) {
        log.info("检索开始: question={}, courseId={}, topK={}", question, courseId, topK);

        // 1. 术语标准化
        String normalized = termAliasService.normalize(question);
        if (!Objects.equals(question, normalized)) {
            log.info("术语标准化: {} → {}", question, normalized);
        }

        // 2. Embedding（带缓存）
        float[] queryVector = getCachedOrComputeEmbedding(normalized);

        // 3. 并行检索（多召回一些，给 RRF 更多融合空间）
        int fetchSize = topK * 2;
        CompletableFuture<List<DocChunk>> vectorFuture = CompletableFuture.supplyAsync(
                () -> knowledgeEsRepository.searchByVector(queryVector, courseId, fetchSize)
        );
        CompletableFuture<List<DocChunk>> keywordFuture = CompletableFuture.supplyAsync(
                () -> knowledgeEsRepository.searchByKeyword(normalized, courseId, fetchSize)
        );

        List<DocChunk> vectorResults = getQuietly(vectorFuture, "向量检索");
        List<DocChunk> keywordResults = getQuietly(keywordFuture, "关键词检索");

        log.info("向量检索命中: {}, 关键词检索命中: {}", vectorResults.size(), keywordResults.size());

        // 4. RRF 融合（返回带分数的 RetrievalResult）
        List<RetrievalResult> fused = rrfFusion(vectorResults, keywordResults, topK);
        log.info("检索完成: RRF 融合后结果数={}", fused.size());
        return fused;
    }

    // ==================== RRF 融合 ====================

    private List<RetrievalResult> rrfFusion(List<DocChunk> vectorResults,
                                             List<DocChunk> keywordResults,
                                             int topK) {
        Map<String, DocChunk> byKey = new HashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        // 向量检索排名（rank 从 1 开始）
        for (int i = 0; i < vectorResults.size(); i++) {
            DocChunk chunk = vectorResults.get(i);
            String key = docKey(chunk);
            double contribution = 1.0 / (RRF_K + (i + 1));
            rrfScores.merge(key, contribution, Double::sum);
            byKey.putIfAbsent(key, chunk);
        }

        // 关键词检索排名
        for (int i = 0; i < keywordResults.size(); i++) {
            DocChunk chunk = keywordResults.get(i);
            String key = docKey(chunk);
            double contribution = 1.0 / (RRF_K + (i + 1));
            rrfScores.merge(key, contribution, Double::sum);
            byKey.putIfAbsent(key, chunk);
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    DocChunk chunk = byKey.get(entry.getKey());
                    if (chunk == null) return null;
                    log.debug("RRF: key={}, score={}", entry.getKey(), entry.getValue());
                    return new RetrievalResult(chunk, entry.getValue());
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private String docKey(DocChunk chunk) {
        return chunk.getSourceType() + ":" + chunk.getSourceId();
    }

    // ==================== 容错 ====================

    private List<DocChunk> getQuietly(CompletableFuture<List<DocChunk>> future, String label) {
        try {
            return future.get();
        } catch (Exception e) {
            log.error("{} 异常, 降级为空列表", label, e);
            return List.of();
        }
    }

    // ==================== Embedding 缓存 ====================

    private float[] getCachedOrComputeEmbedding(String text) {
        String md5 = DigestUtils.md5DigestAsHex(text.getBytes(StandardCharsets.UTF_8));
        String cacheKey = EMBEDDING_CACHE_PREFIX + md5;

        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                log.debug("Embedding 缓存命中: md5={}", md5);
                return base64ToFloatArray(cached);
            }
        } catch (Exception e) {
            log.warn("Redis embedding 缓存读取失败, md5={}", md5, e);
        }

        float[] vector = embeddingModel.embed(text).content().vector();
        log.debug("Embedding 计算完成: md5={}, dims={}", md5, vector.length);

        try {
            String base64 = floatArrayToBase64(vector);
            stringRedisTemplate.opsForValue().set(cacheKey, base64, EMBEDDING_CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis embedding 缓存写入失败, md5={}", md5, e);
        }

        return vector;
    }

    private String floatArrayToBase64(float[] array) {
        ByteBuffer buffer = ByteBuffer.allocate(array.length * 4);
        buffer.asFloatBuffer().put(array);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private float[] base64ToFloatArray(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        FloatBuffer floatBuffer = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] array = new float[floatBuffer.remaining()];
        floatBuffer.get(array);
        return array;
    }
}
