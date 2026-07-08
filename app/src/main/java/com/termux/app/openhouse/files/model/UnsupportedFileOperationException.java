package com.termux.app.openhouse.files.model;

public class UnsupportedFileOperationException extends FileOperationException {

    public UnsupportedFileOperationException(FileOperation operation, String spaceId) {
        super(Code.UNSUPPORTED, operation + " is not supported for file space " + spaceId);
    }
}
