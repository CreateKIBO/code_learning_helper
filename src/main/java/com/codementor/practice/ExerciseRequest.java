package com.codementor.practice;

import com.codementor.javaweb.JavaWebRole;
import com.codementor.settings.CodeMentorSettings;

/** 生成小练习所需的最小信息；不会发送项目源码。 */
public final class ExerciseRequest {

    private final String knowledgePoint;
    private final int reviewOccurrences;
    private final CodeMentorSettings.TeachingLevel teachingLevel;
    private final JavaWebRole javaWebRole;

    public ExerciseRequest(String knowledgePoint,
                           int reviewOccurrences,
                           CodeMentorSettings.TeachingLevel teachingLevel,
                           JavaWebRole javaWebRole) {
        this.knowledgePoint = knowledgePoint;
        this.reviewOccurrences = reviewOccurrences;
        this.teachingLevel = teachingLevel;
        this.javaWebRole = javaWebRole;
    }

    public String getKnowledgePoint() { return knowledgePoint; }
    public int getReviewOccurrences() { return reviewOccurrences; }
    public CodeMentorSettings.TeachingLevel getTeachingLevel() { return teachingLevel; }
    public JavaWebRole getJavaWebRole() { return javaWebRole; }
}
