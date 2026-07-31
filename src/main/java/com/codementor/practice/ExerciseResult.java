package com.codementor.practice;

/** 小练习生成状态。 */
public final class ExerciseResult {

    public enum Status {
        IDLE,
        GENERATING,
        SUCCESS,
        FAILED
    }

    private static final ExerciseResult IDLE =
            new ExerciseResult(Status.IDLE, null, "");

    private final Status status;
    private final Exercise exercise;
    private final String errorMessage;

    private ExerciseResult(Status status, Exercise exercise, String errorMessage) {
        this.status = status;
        this.exercise = exercise;
        this.errorMessage = errorMessage;
    }

    public static ExerciseResult idle() {
        return IDLE;
    }

    public static ExerciseResult generating() {
        return new ExerciseResult(Status.GENERATING, null, "");
    }

    public static ExerciseResult success(Exercise exercise) {
        return new ExerciseResult(Status.SUCCESS, exercise, "");
    }

    public static ExerciseResult failed(String message) {
        return new ExerciseResult(Status.FAILED, null, message);
    }

    public Status getStatus() { return status; }
    public Exercise getExercise() { return exercise; }
    public String getErrorMessage() { return errorMessage; }
}
