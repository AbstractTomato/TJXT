package com.tianji.agent.service.impl;

import com.tianji.agent.domain.po.DocChunk;
import com.tianji.agent.mapper.DocChunkMapper;
import com.tianji.agent.repository.KnowledgeEsRepository;
import com.tianji.agent.service.IngestionService;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.learning.LearningClient;
import com.tianji.api.dto.course.CatalogueDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.learning.QuestionDTO;
import com.tianji.api.dto.learning.ReplyDTO;
import com.tianji.common.domain.dto.PageDTO;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 知识库入库服务实现
 * <p>
 * 将课程章节信息和问答对向量化后存入 MySQL（元数据）和 ES（关键词索引 + dense_vector 向量）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionServiceImpl implements IngestionService {

    private final CourseClient courseClient;
    private final LearningClient learningClient;
    private final DocChunkMapper docChunkMapper;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeEsRepository knowledgeEsRepository;

    /** 每个文本块的最大字符数 */
    private static final int MAX_CHUNK_SIZE = 800;
    /** 文本块之间的重叠字符数 */
    private static final int OVERLAP_SIZE = 100;
    /** 手动录入 sourceId 自增计数器（避免同毫秒重复） */
    private static final AtomicLong MANUAL_ID_COUNTER = new AtomicLong(System.currentTimeMillis() * 1000);

    // ==================== 公开方法 ====================

    @Override
    public void ingestCourse(Long courseId) {
        log.info("开始入库课程, courseId={}", courseId);

        // 1. 获取课程信息（含章节目录）
        CourseFullInfoDTO courseInfo = courseClient.getCourseInfoById(courseId, true, false);
        if (courseInfo == null) {
            log.error("课程不存在, courseId={}", courseId);
            return;
        }
        String courseName = courseInfo.getName();
        log.info("课程名称: {}, 章节数: {}", courseName,
                courseInfo.getChapters() != null ? courseInfo.getChapters().size() : 0);

        List<DocChunk> allChunks = new ArrayList<>();

        // 2. 提取章节种子数据
        allChunks.addAll(buildChapterChunks(courseInfo));
        log.info("章节种子数据块: {}", allChunks.size());

        // 3. 提取问答对数据
        List<DocChunk> qaChunks = buildQAChunks(courseId, courseName);
        allChunks.addAll(qaChunks);
        log.info("课程总数据块（含QA）: {}", allChunks.size());

        // 4. 处理所有块：分块 → 向量化 → 存储
        processChunks(allChunks);
        log.info("课程入库完成, courseId={}", courseId);
    }

    @Override
    public void ingestQA(Long questionId) {
        log.info("开始入库单个问答, questionId={}", questionId);

        // 1. 获取问题详情
        QuestionDTO question = learningClient.queryQuestionById(questionId);
        if (question == null) {
            log.warn("问题不存在, questionId={}", questionId);
            return;
        }

        // 2. 获取课程信息（为了课程名）
        Long courseId = question.getCourseId();
        String courseName = null;
        if (courseId != null) {
            CourseFullInfoDTO courseInfo = courseClient.getCourseInfoById(courseId, false, false);
            if (courseInfo != null) {
                courseName = courseInfo.getName();
            }
        }

        // 3. 获取该问题的所有回答
        List<ReplyDTO> replies = fetchAllReplies(questionId);
        if (replies.isEmpty()) {
            log.info("问题暂无回答, questionId={}", questionId);
            return;
        }

        // 4. 构建 DocChunk
        List<DocChunk> chunks = new ArrayList<>();
        for (ReplyDTO reply : replies) {
            String content = buildQAContent(question.getTitle(), question.getDescription(), reply.getContent());
            List<String> splitTexts = splitText(content);
            for (int i = 0; i < splitTexts.size(); i++) {
                DocChunk chunk = new DocChunk()
                        .setContent(splitTexts.get(i))
                        .setCourseId(courseId)
                        .setCourseName(courseName)
                        .setChapterTitle(null)
                        .setSourceType("QA")
                        .setSourceId(reply.getId());
                chunks.add(chunk);
            }
        }

        // 5. 处理
        processChunks(chunks);
        log.info("单个问答入库完成, questionId={}", questionId);
    }

    /**
     * 手动录入知识内容（管理员直接提交文本，不走 Feign 拉取）
     *
     * @param content      知识文本内容
     * @param courseId     归属课程 ID
     * @param courseName   课程名称
     * @param chapterTitle 章节标题（可为 null）
     */
    @Override
    public void ingestManual(String content, Long courseId, String courseName, String chapterTitle, String sourceType) {
        log.info("手动录入知识内容: sourceType={}, courseName={}, contentLen={}", sourceType, courseName, content.length());

        //分块
        List<String> chunks = splitText(content);

        //每块包装成docChunk
        List<DocChunk> docChunks = new ArrayList<>();
        for (String chunk : chunks) {
            DocChunk c = new DocChunk();
            c.setContent(chunk);
            c.setCreateTime(LocalDateTime.now());
            c.setChapterTitle(chapterTitle);
            c.setSourceType(sourceType != null ? sourceType : "MANUAL");
            c.setCourseId(courseId);
            c.setCourseName(courseName);
            c.setSourceId(MANUAL_ID_COUNTER.incrementAndGet());
            docChunks.add(c);
        }

        //走已有管线
        processChunks(docChunks);
        log.info("手动录入完成, 块数={}", docChunks.size());
    }

    // ==================== 私有方法 ====================

    /**
     * 从课程章节目录构建种子数据块
     */
    private List<DocChunk> buildChapterChunks(CourseFullInfoDTO courseInfo) {
        List<DocChunk> chunks = new ArrayList<>();
        String courseName = courseInfo.getName();
        Long courseId = courseInfo.getId();

        // 课程名作为一个块
        DocChunk courseChunk = new DocChunk()
                .setContent("课程名称：" + courseName)
                .setCourseId(courseId)
                .setCourseName(courseName)
                .setChapterTitle(null)
                .setSourceType("CHAPTER")
                .setSourceId(courseId);
        chunks.add(courseChunk);

        // 遍历章节
        List<CatalogueDTO> chapters = courseInfo.getChapters();
        if (chapters != null) {
            for (CatalogueDTO chapter : chapters) {
                // type=1 是章
                if (chapter.getType() != null && chapter.getType() == 1) {
                    String chapterName = chapter.getName();
                    List<CatalogueDTO> sections = chapter.getSections();
                    if (sections != null) {
                        for (CatalogueDTO section : sections) {
                            // type=2 是节
                            if (section.getType() != null && section.getType() == 2) {
                                String content = String.format("课程《%s》- %s > %s",
                                        courseName, chapterName, section.getName());
                                DocChunk chunk = new DocChunk()
                                        .setContent(content)
                                        .setCourseId(courseId)
                                        .setCourseName(courseName)
                                        .setChapterTitle(chapterName)
                                        .setSourceType("CHAPTER")
                                        .setSourceId(section.getId());
                                chunks.add(chunk);
                            }
                        }
                    }
                }
            }
        }
        return chunks;
    }

    /**
     * 从问答对构建数据块
     */
    private List<DocChunk> buildQAChunks(Long courseId, String courseName) {
        List<DocChunk> chunks = new ArrayList<>();
        int pageNo = 1;
        int pageSize = 50;

        while (true) {
            PageDTO<QuestionDTO> page = learningClient.queryQuestionPage(courseId, pageNo, pageSize);
            if (page == null || page.getList() == null || page.getList().isEmpty()) {
                break;
            }
            for (QuestionDTO question : page.getList()) {
                // 只处理有回答的问题
                if (question.getAnswerTimes() == null || question.getAnswerTimes() == 0) {
                    continue;
                }
                List<ReplyDTO> replies = fetchAllReplies(question.getId());
                for (ReplyDTO reply : replies) {
                    String content = buildQAContent(question.getTitle(), question.getDescription(), reply.getContent());
                    List<String> splitTexts = splitText(content);
                    for (String splitText : splitTexts) {
                        DocChunk chunk = new DocChunk()
                                .setContent(splitText)
                                .setCourseId(courseId)
                                .setCourseName(courseName)
                                .setChapterTitle(null)
                                .setSourceType("QA")
                                .setSourceId(reply.getId());
                        chunks.add(chunk);
                    }
                }
            }
            // 检查是否还有下一页
            if (page.getPages() == null || pageNo >= page.getPages()) {
                break;
            }
            pageNo++;
        }
        return chunks;
    }

    /**
     * 获取某个问题的所有回答（分页遍历）
     */
    private List<ReplyDTO> fetchAllReplies(Long questionId) {
        List<ReplyDTO> allReplies = new ArrayList<>();
        int pageNo = 1;
        int pageSize = 20;

        while (true) {
            PageDTO<ReplyDTO> page = learningClient.queryReplyPage(questionId, pageNo, pageSize);
            if (page == null || page.getList() == null || page.getList().isEmpty()) {
                break;
            }
            allReplies.addAll(page.getList());
            if (page.getPages() == null || pageNo >= page.getPages()) {
                break;
            }
            pageNo++;
        }
        return allReplies;
    }

    /**
     * 拼接问答文本（按 Q-only + Payload 设计，问题作为上下文头）
     */
    private String buildQAContent(String title, String description, String replyContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(title);
        if (description != null && !description.isEmpty()) {
            sb.append("\n").append(description);
        }
        sb.append("\n回答：").append(replyContent != null ? replyContent : "");
        return sb.toString();
    }

    /**
     * 处理所有文档块：向量化 → 存 Redis → 存 MySQL → 存 ES
     */
    private void processChunks(List<DocChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;

        for (DocChunk chunk : chunks) {
            try {
                boolean success = processSingleChunk(chunk);
                if (success){
                    skipCount++;
                }else {
                    successCount++;
                }
            } catch (Exception e) {
                log.error("处理文档块失败, sourceType={}, sourceId={}", chunk.getSourceType(), chunk.getSourceId(), e);
                failCount++;
            }
        }
        log.info("文档块处理完成: 成功={}, 跳过={}, 失败={}", successCount, skipCount, failCount);
    }

    /**
     * 处理单个文档块：向量化 + 存储
     */
    private boolean processSingleChunk(DocChunk chunk) {
        // 0. 去重检查：按 (sourceType, sourceId) 检查是否已存在
        // 简单策略：直接插入，如果已存在则跳过（依赖 DB unique 约束）

        // 1. 向量化
        Embedding embedding = embeddingModel.embed(chunk.getContent()).content();
        float[] vector = embedding.vector();

        // 2. 存 MySQL
        try {
            docChunkMapper.insert(chunk);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.debug("文档块已存在, sourceType={}, sourceId={}, 跳过", chunk.getSourceType(), chunk.getSourceId());
            return true;
        }

        // 3. 存 ES（元数据 + dense_vector 向量，失败不影响主流程）
        try {
            knowledgeEsRepository.save(chunk, vector);
        } catch (Exception e) {
            log.error("ES 索引失败, chunkId={}", chunk.getId(), e);
        }

        return false;
    }

    /**
     * 文本分块：按段落边界切分，每块 500~800 字，重叠 100 字
     */
    List<String> splitText(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        // 短文本不分块
        if (text.length() <= MAX_CHUNK_SIZE) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK_SIZE, text.length());
            // 尽量在段落边界断开（找最后一个换行符）
            if (end < text.length()) {
                int breakPoint = text.lastIndexOf('\n', end);
                if (breakPoint > start + OVERLAP_SIZE) {
                    end = breakPoint + 1; // 包含换行符
                }
            }
            chunks.add(text.substring(start, end).trim());
            start = end - OVERLAP_SIZE;
            if (start >= text.length()) {
                break;
            }
        }
        return chunks;
    }
}
