package com.tianji.agent.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
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
     * 对话模型 Bean
     */
    @Bean
    public OpenAiChatModel chatModel() {
        return OpenAiChatModel.builder()
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
     * 如果 baseUrl 指向的模型服务不支持 embedding（如 DeepSeek），
     * 可以换成 langchain4j-embeddings-bge-small-zh 本地模型，无需额外配置。
     */
    @Bean
    public OpenAiEmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName("text-embedding-ada-002")
                .timeout(Duration.ofSeconds(timeout))
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
