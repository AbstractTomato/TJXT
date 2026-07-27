package com.tianji.api.client.learning.fallback;

import com.tianji.api.client.learning.LearningClient;
import com.tianji.api.dto.learning.QuestionDTO;
import com.tianji.api.dto.learning.QuestionFormDTO;
import com.tianji.api.dto.learning.ReplyDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.common.domain.dto.PageDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collections;

@Slf4j
public class LearningClientFallback implements FallbackFactory<LearningClient> {

    @Override
    public LearningClient create(Throwable cause) {
        log.error("查询学习服务异常", cause);
        return new LearningClient() {
            @Override
            public Integer countLearningLessonByCourse(Long courseId) {
                return 0;
            }

            @Override
            public Long isLessonValid(Long courseId) {
                return null;
            }

            @Override
            public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
                return null;
            }

            @Override
            public PageDTO<QuestionDTO> queryQuestionPage(Long courseId, Integer pageNo, Integer pageSize) {
                return new PageDTO<>(0L, 0L, Collections.emptyList());
            }

            @Override
            public QuestionDTO queryQuestionById(Long id) {
                return null;
            }

            @Override
            public PageDTO<ReplyDTO> queryReplyPage(Long questionId, Integer pageNo, Integer pageSize) {
                return new PageDTO<>(0L, 0L, Collections.emptyList());
            }

            @Override
            public void createQuestion(QuestionFormDTO dto) {

            }
        };
    }
}
