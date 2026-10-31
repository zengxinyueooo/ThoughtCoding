package com.thoughtcoding.task;

/** Storage failures must escape tool error normalization and stop the execution. */
public final class TaskPersistenceException extends IllegalStateException {
    public TaskPersistenceException(String message, Throwable cause) { super(message, cause); }
}
