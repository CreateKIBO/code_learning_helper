package com.codementor.history;

/** 反复出现的知识点提醒。 */
public final class KnowledgeReminder {

    private final String knowledgePoint;
    private final int occurrences;

    public KnowledgeReminder(String knowledgePoint, int occurrences) {
        this.knowledgePoint = knowledgePoint;
        this.occurrences = occurrences;
    }

    public String getKnowledgePoint() {
        return knowledgePoint;
    }

    public int getOccurrences() {
        return occurrences;
    }
}
