package com.tianji.agent.repository;

import cn.hutool.json.JSONObject;
import com.tianji.agent.domain.po.DocChunk;
import com.tianji.common.exceptions.CommonException;
import com.tianji.common.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.ScriptScoreQueryBuilder;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 知识库 ES 操作仓库
 * <p>
 * 负责将知识库文档块索引到 ES（agent_knowledge 索引），
 * 同时存储元数据（关键词检索）和 768 维 embedding 向量（dense_vector 相似度检索）。
 */
@Slf4j
@Component
public class KnowledgeEsRepository {

    private static final String INDEX_NAME = "agent_knowledge";

    private final RestHighLevelClient restHighLevelClient;

    public KnowledgeEsRepository(RestHighLevelClient restHighLevelClient) {
        this.restHighLevelClient = restHighLevelClient;
    }

    // ==================== 写入 ====================

    /**
     * 保存单个文档块及其向量到 ES。
     *
     * @param chunk     文档块元数据
     * @param embedding 768 维向量
     */
    public void save(DocChunk chunk, float[] embedding) {
        IndexRequest request = new IndexRequest(INDEX_NAME)
                .id(chunk.getId().toString())
                .source(buildSourceWithEmbedding(chunk, embedding), XContentType.JSON);
        try {
            restHighLevelClient.index(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.error("ES 索引单个文档块失败, id={}", chunk.getId(), e);
            throw new CommonException("ES 索引知识库文档块失败", e);
        }
    }

    /**
     * 批量保存文档块及其向量到 ES。
     * <p>
     * chunks 和 embeddings 必须一一对应（同长度）。
     *
     * @param chunks     文档块列表
     * @param embeddings 对应的向量列表
     */
    public void saveAll(List<DocChunk> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException("chunks 和 embeddings 长度不一致");
        }
        BulkRequest request = new BulkRequest(INDEX_NAME);
        for (int i = 0; i < chunks.size(); i++) {
            DocChunk chunk = chunks.get(i);
            float[] embedding = embeddings.get(i);
            request.add(new IndexRequest(INDEX_NAME)
                    .id(chunk.getId().toString())
                    .source(buildSourceWithEmbedding(chunk, embedding), XContentType.JSON));
        }
        try {
            BulkResponse bulkResponse = restHighLevelClient.bulk(request, RequestOptions.DEFAULT);
            for (BulkItemResponse itemResponse : bulkResponse.getItems()) {
                if (itemResponse.status().compareTo(RestStatus.BAD_REQUEST) >= 0) {
                    log.error("ES 批量索引失败, id={}, 原因={}", itemResponse.getId(), itemResponse.getFailureMessage());
                }
            }
        } catch (IOException e) {
            log.error("ES 批量索引文档块失败, 数量={}", chunks.size(), e);
            throw new CommonException("ES 批量索引知识库文档块失败", e);
        }
    }

    // ==================== 删除 ====================

    /**
     * 删除指定课程的所有文档块（用于重新入库前清理）
     *
     * @param courseId 课程 ID
     */
    public void deleteByCourseId(Long courseId) {
        try {
            org.elasticsearch.index.reindex.DeleteByQueryRequest request =
                    new org.elasticsearch.index.reindex.DeleteByQueryRequest(INDEX_NAME);
            request.setQuery(new org.elasticsearch.index.query.TermQueryBuilder("courseId", courseId));
            restHighLevelClient.deleteByQuery(request, RequestOptions.DEFAULT);
            log.info("ES 已删除课程 {} 的旧索引", courseId);
        } catch (IOException e) {
            log.error("ES 删除课程 {} 的旧索引失败", courseId, e);
            throw new CommonException("ES 删除知识库索引失败", e);
        }
    }

    // ==================== 检索（Phase 3） ====================

    /**
     * 向量相似度检索。
     * <p>
     * 使用 ES {@code script_score} 查询 + Painless {@code cosineSimilarity} 函数。
     * 余弦相似度 + 1.0 确保分数非负（ES 要求 score &ge; 0）。
     *
     * @param queryVector 查询向量（768 维）
     * @param courseId    限定课程范围（为 null 则检索全部）
     * @param topK        返回 Top-K 个结果
     * @return 按相似度降序排列的文档块列表
     */
    public List<DocChunk> searchByVector(float[] queryVector, Long courseId, int topK) {
        // 1. 构建过滤查询（限定课程范围，减少无效计算）
        QueryBuilder innerQuery = (courseId != null)
                ? QueryBuilders.termQuery("courseId", courseId)
                : QueryBuilders.matchAllQuery();

        // 2. 构建 script_score 查询
        Map<String, Object> params = Map.of("query_vector", toFloatList(queryVector));
        Script script = new Script(
                ScriptType.INLINE,
                "painless",
                "cosineSimilarity(params.query_vector, 'embedding') + 1.0",
                params
        );
        ScriptScoreQueryBuilder scriptScoreQuery = QueryBuilders.scriptScoreQuery(innerQuery, script);

        // 3. 组装 SearchRequest
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                .query(scriptScoreQuery)
                .size(topK);
        SearchRequest searchRequest = new SearchRequest(INDEX_NAME).source(sourceBuilder);

        // 4. 执行查询
        SearchResponse response;
        try {
            response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.error("ES 向量检索失败, courseId={}", courseId, e);
            throw new CommonException("ES 向量检索失败", e);
        }

        // 5. 解析结果
        List<DocChunk> results = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            DocChunk chunk = JsonUtils.toBean(hit.getSourceAsString(), DocChunk.class);
            log.debug("向量检索命中: id={}, score={}", chunk.getId(), hit.getScore());
            results.add(chunk);
        }
        return results;
    }

    /**
     * 关键词检索（IK 分词全文搜索）。
     * <p>
     * 对 {@code content} 字段做 {@code match} 查询，ES 端使用 IK 分词器
     * （ik_smart 搜索时细粒度切词），按 BM25 相关性评分降序返回。
     *
     * @param keyword  搜索关键词
     * @param courseId 限定课程范围（为 null 则检索全部）
     * @param topK     返回 Top-K 个结果
     * @return 按 BM25 评分降序排列的文档块列表
     */
    public List<DocChunk> searchByKeyword(String keyword, Long courseId, int topK) {
        // 1. 构建查询：must(match) + filter(term)
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
                .must(QueryBuilders.matchQuery("content", keyword));
        if (courseId != null) {
            boolQuery.filter(QueryBuilders.termQuery("courseId", courseId));
        }

        // 2. 组装 SearchRequest
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                .query(boolQuery)
                .size(topK);
        SearchRequest searchRequest = new SearchRequest(INDEX_NAME).source(sourceBuilder);

        // 3. 执行查询
        SearchResponse response;
        try {
            response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.error("ES 关键词检索失败, keyword={}, courseId={}", keyword, courseId, e);
            throw new CommonException("ES 关键词检索失败", e);
        }

        // 4. 解析结果
        List<DocChunk> results = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            DocChunk chunk = JsonUtils.toBean(hit.getSourceAsString(), DocChunk.class);
            log.debug("关键词检索命中: id={}, score={}", chunk.getId(), hit.getScore());
            results.add(chunk);
        }
        return results;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 将 float[] 转换为 List&lt;Float&gt;（兼容 JSON 序列化）。
     */
    private List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float v : array) {
            list.add(v);
        }
        return list;
    }

    /**
     * 构建包含 embedding 向量的 ES 文档 JSON。
     * <p>
     * 先序列化 DocChunk（元数据），再注入 embedding 向量。
     * vectorKey 字段不再写入 ES（已迁移至 dense_vector）。
     */
    private String buildSourceWithEmbedding(DocChunk chunk, float[] embedding) {
        JSONObject jsonObj = JsonUtils.parseObj(JsonUtils.toJsonStr(chunk));
        jsonObj.remove("vectorKey");
        jsonObj.set("embedding", toFloatList(embedding));
        return jsonObj.toString();
    }
}
