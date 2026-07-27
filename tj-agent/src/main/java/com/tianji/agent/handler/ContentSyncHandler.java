package com.tianji.agent.handler;

import com.tianji.agent.service.IngestionService;
import com.tianji.api.client.course.CourseClient;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
     * 需要同步的课程 ID 列表（逗号分隔），从 Nacos 配置读取
     * 示例：tj.agent.sync.course-ids=1,2,3
     */
    @Value("${tj.agent.sync.course-ids:}")
    private String courseIdsConfig;

    /**
     * 全量同步：每天凌晨 3 点执行一次
     */
    @XxlJob("syncKnowledgeBase")
    public void syncKnowledgeBase() {
        log.info("==== 定时任务 [syncKnowledgeBase] 开始执行 ====");
        List<Long> courseIds = getCourseIds();
        if (courseIds.isEmpty()) {
            log.warn("未配置同步课程列表，跳过执行。请在 Nacos 配置 tj.agent.sync.course-ids");
            return;
        }
        log.info("待同步课程数: {}", courseIds.size());
        int successCount = 0;
        int failCount = 0;
        for (Long courseId : courseIds) {
            try {
                ingestionService.ingestCourse(courseId);
                successCount++;
            } catch (Exception e) {
                log.error("同步课程失败, courseId={}", courseId, e);
                failCount++;
            }
        }
        log.info("==== 定时任务 [syncKnowledgeBase] 执行完成, 成功={}, 失败={} ====", successCount, failCount);
    }

    /**
     * 增量同步：每 10 分钟执行一次，同步新增的问答对
     * <p>
     * 当前策略：对已配置的课程执行全量 re-sync。
     * 由于 (sourceType, sourceId) 唯一约束，已存在的块会自动跳过（MySQL 层去重）。
     */
    @XxlJob("syncNewQA")
    public void syncNewQA() {
        log.info("==== 定时任务 [syncNewQA] 开始执行 ====");
        List<Long> courseIds = getCourseIds();
        if (courseIds.isEmpty()) {
            log.debug("未配置同步课程列表，跳过执行");
            return;
        }
        // 增量同步：只拉取每个课程的问答对（不重新拉课程章节信息）
        // 由于 ingestCourse 会处理全部，这里先调用轻量的 ingestCourse
        // 后续 Phase 3 可优化为只查增量
        for (Long courseId : courseIds) {
            try {
                ingestionService.ingestCourse(courseId);
            } catch (Exception e) {
                log.error("增量同步课程失败, courseId={}", courseId, e);
            }
        }
        log.info("==== 定时任务 [syncNewQA] 执行完成 ====");
    }

    /**
     * 从配置中解析课程 ID 列表
     */
    private List<Long> getCourseIds() {
        if (courseIdsConfig == null || courseIdsConfig.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(courseIdsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }
}
