package com.codementor.practice;

import com.codementor.javaweb.JavaWebRole;
import com.codementor.settings.CodeMentorSettings;

/** 生成小练习所需的最小信息；不会发送项目源码。 */
public final class ExerciseRequest {

    private final String knowledgePoint;
    private final int recentOccurrences;
    private final CodeMentorSettings.TeachingLevel teachingLevel;
    private final JavaWebRole javaWebRole;

    public ExerciseRequest(String knowledgePoint,
                           int recentOccurrences,
                           CodeMentorSettings.TeachingLevel teachingLevel,
                           JavaWebRole javaWebRole) {
        this.knowledgePoint = knowledgePoint;
        this.recentOccurrences = recentOccurrences;
        this.teachingLevel = teachingLevel;
        this.javaWebRole = javaWebRole;
    }

    public String getKnowledgePoint() { return knowledgePoint; }
    public int getRecentOccurrences() { return recentOccurrences; }
    public CodeMentorSettings.TeachingLevel getTeachingLevel() { return teachingLevel; }
    public JavaWebRole getJavaWebRole() { return javaWebRole; }
}
