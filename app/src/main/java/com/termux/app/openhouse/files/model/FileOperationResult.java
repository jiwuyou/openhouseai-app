package com.termux.app.openhouse.files.model;

public final class FileOperationResult<T> {

    private final boolean success;
    private final T value;
    private final FileOperationException error;

    private FileOperationResult(boolean success, T value, FileOperationException error) {
        this.success = success;
        this.value = value;
        this.error = error;
    }

    public static <T> FileOperationResult<T> success(T value) {
        return new FileOperationResult<>(true, value, null);
    }

    public static <T> FileOperationResult<T> failure(FileOperationException error) {
        return new FileOperationResult<>(false, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public T getValue() {
        return value;
    }

    public FileOperationException getError() {
        return error;
    }
}
