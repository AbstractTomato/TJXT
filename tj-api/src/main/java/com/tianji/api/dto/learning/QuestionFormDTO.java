package com.tianji.api.dto.learning;

import lombok.Data;

@Data
public class QuestionFormDTO {

    private Long courseId;

    private String title;

    private String description;
}
