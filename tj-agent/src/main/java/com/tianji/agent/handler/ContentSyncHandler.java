package com.tianji.agent.handler;

import com.tianji.agent.service.IngestionService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 知识库同步定时任务
 * <p>
 * 每天凌晨自动将新增/更新的课程内容、问答对同步到向量库和 ES 索引。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentSyncHandler {

    private final IngestionService ingestionService;

    /**
     * 全量同步：每天凌晨 3 点执行一次
     */
    @XxlJob("syncKnowledgeBase")
    public void syncKnowledgeBase() {
        log.info("==== 定时任务 [syncKnowledgeBase] 开始执行 ====");
        // TODO: 扫描最近变更的课程 → 依次调用 ingestionService.ingestCourse()
        log.info("==== 定时任务 [syncKnowledgeBase] 执行完成 ====");
    }

    /**
     * 增量同步：每 10 分钟执行一次，同步新增的问答对
     */
    @XxlJob("syncNewQA")
    public void syncNewQA() {
        log.info("==== 定时任务 [syncNewQA] 开始执行 ====");
        // TODO: 扫描最近新增的已采纳问答对 → 依次调用 ingestionService.ingestQA()
        log.info("==== 定时任务 [syncNewQA] 执行完成 ====");
    }
}
