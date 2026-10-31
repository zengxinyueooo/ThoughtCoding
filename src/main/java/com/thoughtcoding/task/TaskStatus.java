package com.thoughtcoding.task;

/** Durable task lifecycle; a text response alone does not prove acceptance. */
public enum TaskStatus {
    PENDING, RUNNING, WAITING_INPUT, PAUSED, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED;

    public boolean terminal() {
        return this == SUCCEEDED || this == CANCELLED;
    }
}
