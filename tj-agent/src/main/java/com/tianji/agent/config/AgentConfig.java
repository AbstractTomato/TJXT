package com.tianji.agent.config;

import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM 大模型配置
 * <p>
 * 支持任何兼容 OpenAI 接口的模型服务（DeepSeek、通义千问、智谱 GLM 等），
 * 只需改 baseUrl 和 apiKey 即可。
 */
@Configuration
@ConfigurationProperties(prefix = "tj.agent.llm")
@Data
public class AgentConfig {

    /** API 地址 */
    private String baseUrl = "https://api.deepseek.com";

    /** API Key */
    private String apiKey;

    /** 模型名称 */
    private String modelName = "deepseek-v4-pro";

    /** 生成温度（0~1，越高越随机，教育场景建议偏低保证准确性） */
    private Double temperature = 0.3;

    /** 最大生成 token 数 */
    private Integer maxTokens = 2048;

    /** 请求超时时间（秒） */
    private Long timeout = 60L;

    /**
     * 流式对话模型 Bean（Phase 4 使用）。
     * <p>
     * 用于 SSE 流式输出，通过 {@code StreamingResponseHandler} 回调
     * 逐 token 推送到前端。API 兼容所有 OpenAI 接口的模型服务。
     */
    @Bean
    public OpenAiStreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeout))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 嵌入模型 Bean（将文本转成向量）
     * <p>
     * 使用本地 BGE-small-zh 模型（768 维），纯 CPU 推理，无网络依赖。
     * 首次启动会自动下载模型文件（约 60MB），后续使用本地缓存。
     */
    @Bean
    public BgeSmallZhEmbeddingModel embeddingModel() {
        return new BgeSmallZhEmbeddingModel();
    }
}
