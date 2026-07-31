package com.codementor.practice;

import java.util.List;

/** 一道 10~20 分钟、只针对单个知识点的小练习。 */
public final class Exercise {

    private final String title;
    private final String knowledgePoint;
    private final String task;
    private final String starterCode;
    private final List<String> constraints;
    private final List<String> hints;
    private final List<String> acceptanceCriteria;

    public Exercise(String title,
                    String knowledgePoint,
                    String task,
                    String starterCode,
                    List<String> constraints,
                    List<String> hints,
                    List<String> acceptanceCriteria) {
        this.title = title;
        this.knowledgePoint = knowledgePoint;
        this.task = task;
        this.starterCode = starterCode;
        this.constraints = List.copyOf(constraints);
        this.hints = List.copyOf(hints);
        this.acceptanceCriteria = List.copyOf(acceptanceCriteria);
    }

    public String getTitle() { return title; }
    public String getKnowledgePoint() { return knowledgePoint; }
    public String getTask() { return task; }
    public String getStarterCode() { return starterCode; }
    public List<String> getConstraints() { return constraints; }
    public List<String> getHints() { return hints; }
    public List<String> getAcceptanceCriteria() { return acceptanceCriteria; }
}
