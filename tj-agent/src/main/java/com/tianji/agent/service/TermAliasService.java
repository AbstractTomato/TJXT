package com.tianji.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.AnalyzeRequest;
import org.elasticsearch.client.indices.AnalyzeResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 术语标准化服务。
 * <p>
 * 使用 ES IK 分词器（ik_smart）将用户问题切词，
 * 然后对每个词查 Redis Hash {@code agent:term:alias}，
 * 命中则替换为标准术语，未命中保留原词，最后拼回完整问题。
 * <p>
 * 示例：
 * <pre>
 *   输入: "SpringBoot怎么配置多数据源"
 *   IK 分词: ["SpringBoot", "怎么", "配置", "多数据源"]
 *   Redis 命中: SpringBoot→"Spring Boot", 多数据源→"多数据源配置"
 *   输出: "Spring Boot 怎么 配置 多数据源配置"
 * </pre>
 * <p>
 * 后续如果需要支持更大规模术语（>1 万），可升级为 AC 自动机，
 * 避免分词不准导致的漏匹配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TermAliasService {

    private static final String REDIS_HASH_KEY = "agent:term:alias";
    private static final String ANALYZER_NAME = "ik_smart";

    private final StringRedisTemplate stringRedisTemplate;
    private final RestHighLevelClient restHighLevelClient;

    /**
     * 对用户问题进行术语标准化。
     * <p>
     * 流程：IK 分词 → 逐个查 Redis Hash → 命中替换 → 拼回字符串。
     * 如果 IK 分词失败（ES 不可用），降级为原问题返回。
     *
     * @param question 用户原始问题
     * @return 术语标准化后的问题（或原问题）
     */
    public String normalize(String question) {
        if (question == null || question.trim().isEmpty()) {
            return question;
        }

        // 1. IK 分词
        List<String> tokens;
        try {
            tokens = analyze(question);
        } catch (Exception e) {
            log.warn("IK 分词失败, 降级为原问题返回: {}", question, e);
            return question;
        }

        if (tokens.isEmpty()) {
            return question;
        }

        // 2. 批量查 Redis 术语映射（一次 HMGET，O(1) 网络往返）
        List<String> normalized = batchResolve(tokens);

        String result = String.join(" ", normalized);
        if (!result.equals(question)) {
            log.info("术语标准化: {} → {}", question, result);
        }
        return result;
    }

    /**
     * 调用 ES analyze API，用 ik_smart 分词器切词。
     */
    private List<String> analyze(String text) throws Exception {
        AnalyzeRequest request = AnalyzeRequest.withGlobalAnalyzer(ANALYZER_NAME, text);
        AnalyzeResponse response = restHighLevelClient.indices()
                .analyze(request, RequestOptions.DEFAULT);

        return response.getTokens().stream()
                .map(AnalyzeResponse.AnalyzeToken::getTerm)
                .collect(Collectors.toList());
    }

    /**
     * 批量查询 token 列表的术语映射。
     * <p>
     * 一次 HMGET 替代 N 次 HGET，O(1) 网络往返。
     * 时间复杂度 O(N)，N = token 数，与词典大小 M 无关。
     */
    private List<String> batchResolve(List<String> tokens) {
        // 去重：相同 token 只查一次
        List<String> uniqueTokens = tokens.stream().distinct().collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        List<Object> rawValues;
        try {
            Collection<Object> fields = new ArrayList<>(uniqueTokens);
            rawValues = stringRedisTemplate.opsForHash().multiGet(REDIS_HASH_KEY, fields);
        } catch (Exception e) {
            log.warn("Redis HMGET 异常, tokens={}", uniqueTokens.size(), e);
            // 降级：全部原样返回
            return tokens;
        }

        // 构建 uniqueToken → normalized 的映射
        java.util.Map<String, String> aliasMap = new java.util.HashMap<>();
        for (int i = 0; i < uniqueTokens.size(); i++) {
            Object raw = (rawValues != null && i < rawValues.size()) ? rawValues.get(i) : null;
            if (raw != null) {
                aliasMap.put(uniqueTokens.get(i), raw.toString());
            }
        }

        if (!aliasMap.isEmpty()) {
            log.debug("术语命中: {}", aliasMap);
        }

        // 按原始顺序还原
        List<String> result = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            result.add(aliasMap.getOrDefault(token, token));
        }
        return result;
    }
}
