package com.termux.app.openhouse.files.model;

import java.io.IOException;

public class FileOperationException extends IOException {

    public enum Code {
        INVALID_PATH,
        NOT_FOUND,
        PERMISSION_DENIED,
        CONFLICT,
        UNSUPPORTED,
        NETWORK,
        PARSE,
        UNKNOWN
    }

    private final Code code;

    public FileOperationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public FileOperationException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
