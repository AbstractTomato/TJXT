package com.tianji.agent.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * BGE 模型预热
 * <p>
 * BGE-small-zh 模型在首次调用时会下载模型文件（约 60MB）并初始化 ONNX 运行时，
 * 耗时约 30~60 秒。通过应用启动后立即执行一次空调用，避免首次批量入库时卡住。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingWarmup {

    private final EmbeddingModel embeddingModel;

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        log.info("开始预热 BGE 嵌入模型...");
        try {
            embeddingModel.embed("预热测试文本");
            log.info("BGE 嵌入模型预热完成");
        } catch (Exception e) {
            log.error("BGE 嵌入模型预热失败", e);
        }
    }
}
