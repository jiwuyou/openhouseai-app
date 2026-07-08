package com.termux.app.openhouse.files.model;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public interface FileRepository {

    FileSpace getSpace();

    FileItem getRoot() throws FileOperationException;

    List<FileItem> list(String parentId) throws FileOperationException;

    InputStream openInputStream(String fileId) throws FileOperationException;

    OutputStream openOutputStream(String fileId, String mimeType) throws FileOperationException;

    FileItem upload(String parentId, String displayName, InputStream input, long size, String mimeType)
        throws FileOperationException;

    void download(String fileId, OutputStream output) throws FileOperationException;

    FileItem createDirectory(String parentId, String displayName) throws FileOperationException;

    void delete(String fileId) throws FileOperationException;

    FileItem rename(String fileId, String newDisplayName) throws FileOperationException;

    boolean supports(FileOperation operation);
}
