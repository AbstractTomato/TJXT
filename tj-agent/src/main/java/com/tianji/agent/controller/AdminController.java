package com.tianji.agent.controller;


import com.tianji.agent.domain.dto.AdminIngestRequest;
import com.tianji.agent.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/agent/admin")
@RequiredArgsConstructor
public class AdminController {
    private final IngestionService ingestionService;

    @PostMapping("/ingest")
    public String ingest(@RequestBody AdminIngestRequest request){
        log.info("收到手动录入请求: courseId={}, courseName={}, contentLen={}",
                request.getCourseId(), request.getCourseName(),
                request.getContent() == null ? 0 : request.getContent().length());

        ingestionService.ingestManual(
                request.getContent(),
                request.getCourseId(),
                request.getCourseName(),
                request.getChapterTitle(),
                request.getSourceType()
        );

        return "OK";
    }

    /**
     * 讲师回复后回调——实时将单个问答对入库。
     * learning-service 在讲师回答成功后调用此端点，实现知识库闭环更新。
     */
    @PostMapping("/ingest/qa/{questionId}")
    public String ingestQA(@PathVariable Long questionId) {
        log.info("收到 QA 实时入库回调: questionId={}", questionId);
        ingestionService.ingestQA(questionId);
        return "OK";
    }
}
