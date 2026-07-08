package com.termux.app.openhouse.files.saf;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import androidx.documentfile.provider.DocumentFile;

import com.termux.app.openhouse.files.model.FileItem;
import com.termux.app.openhouse.files.model.FileOperation;
import com.termux.app.openhouse.files.model.FileOperationException;
import com.termux.app.openhouse.files.model.FileRepository;
import com.termux.app.openhouse.files.model.FileSpace;
import com.termux.app.openhouse.files.model.FileSpaceType;
import com.termux.app.openhouse.files.storage.FileRepositoryUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class SafDocumentRepository implements FileRepository {

    private static final String SAF_DIRECTORY_MIME = Document.MIME_TYPE_DIR;
    private static final String[] DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS
    };

    private final Context context;
    private final ContentResolver resolver;
    private final SafFileSpaceConfig config;
    private final FileSpace space;

    public SafDocumentRepository(Context context, SafFileSpaceConfig config) {
        if (context == null) throw new IllegalArgumentException("context == null");
        if (config == null) throw new IllegalArgumentException("config == null");
        this.context = context.getApplicationContext();
        this.resolver = context.getContentResolver();
        this.config = config;
        this.space = FileSpace.builder(config.getId(), FileSpaceType.SAF, config.getDisplayName())
            .rootLabel(config.getDisplayName())
            .locationSummary(config.getTreeUri().toString())
            .metadata("treeUri", config.getTreeUri().toString())
            .build();
    }

    @Override
    public FileSpace getSpace() {
        return space;
    }

    @Override
    public FileItem getRoot() throws FileOperationException {
        DocumentFile root = DocumentFile.fromTreeUri(context, config.getTreeUri());
        if (root == null || !root.exists()) {
            throw new FileOperationException(FileOperationException.Code.NOT_FOUND, "SAF tree is not available: " + config.getTreeUri());
        }
        return FileItem.builder(space.getId(), FileItem.ROOT_ID, root.getName() == null ? space.getRootLabel() : root.getName(), true)
            .parentId(FileItem.ROOT_ID)
            .mimeType(SAF_DIRECTORY_MIME)
            .readable(root.canRead())
            .writable(root.canWrite())
            .deletable(false)
            .nativeLocation(config.getTreeUri().toString())
            .build();
    }

    @Override
    public List<FileItem> list(String parentId) throws FileOperationException {
        Uri parentUri = uriForId(parentId);
        String parentDocumentId = DocumentsContract.getDocumentId(parentUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(config.getTreeUri(), parentDocumentId);
        List<FileItem> items = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null);
            if (cursor == null) {
                throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot query SAF children: " + parentUri);
            }
            while (cursor.moveToNext()) {
                items.add(toItemFromCursor(cursor, normalizeId(parentId)));
            }
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot list SAF directory: " + parentUri, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        Collections.sort(items, new Comparator<FileItem>() {
            @Override
            public int compare(FileItem left, FileItem right) {
                if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
                return left.getDisplayName().compareToIgnoreCase(right.getDisplayName());
            }
        });
        return items;
    }

    @Override
    public InputStream openInputStream(String fileId) throws FileOperationException {
        Uri uri = uriForId(fileId);
        try {
            InputStream input = resolver.openInputStream(uri);
            if (input == null) {
                throw new FileOperationException(FileOperationException.Code.NOT_FOUND, "Cannot open SAF input: " + uri);
            }
            return input;
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot open SAF input: " + uri, e);
        }
    }

    @Override
    public OutputStream openOutputStream(String fileId, String mimeType) throws FileOperationException {
        Uri uri = uriForId(fileId);
        try {
            OutputStream output = resolver.openOutputStream(uri, "wt");
            if (output == null) {
                throw new FileOperationException(FileOperationException.Code.NOT_FOUND, "Cannot open SAF output: " + uri);
            }
            return output;
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot open SAF output: " + uri, e);
        }
    }

    @Override
    public FileItem upload(String parentId, String displayName, InputStream input, long size, String mimeType)
        throws FileOperationException {
        String safeName = FileRepositoryUtils.requireDisplayName(displayName);
        Uri parentUri = uriForId(parentId);
        String resolvedMimeType = mimeType == null || mimeType.trim().isEmpty()
            ? FileRepositoryUtils.guessMimeType(safeName, false)
            : mimeType;
        Uri created = null;
        try {
            created = DocumentsContract.createDocument(resolver, parentUri, resolvedMimeType, safeName);
            if (created == null) {
                throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot create SAF document: " + safeName);
            }
            try (OutputStream output = resolver.openOutputStream(created, "wt")) {
                if (output == null) {
                    throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot write SAF document: " + created);
                }
                FileRepositoryUtils.copy(input, output);
            }
            return querySingleItem(created, normalizeId(parentId));
        } catch (FileOperationException e) {
            if (created != null) tryDelete(created);
            throw e;
        } catch (Exception e) {
            if (created != null) tryDelete(created);
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot upload SAF document: " + safeName, e);
        }
    }

    @Override
    public void download(String fileId, OutputStream output) throws FileOperationException {
        try (InputStream input = openInputStream(fileId)) {
            FileRepositoryUtils.copy(input, output);
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot download SAF document: " + fileId, e);
        }
    }

    @Override
    public FileItem createDirectory(String parentId, String displayName) throws FileOperationException {
        String safeName = FileRepositoryUtils.requireDisplayName(displayName);
        Uri parentUri = uriForId(parentId);
        try {
            Uri created = DocumentsContract.createDocument(resolver, parentUri, SAF_DIRECTORY_MIME, safeName);
            if (created == null) {
                throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot create SAF directory: " + safeName);
            }
            return querySingleItem(created, normalizeId(parentId));
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot create SAF directory: " + safeName, e);
        }
    }

    @Override
    public void delete(String fileId) throws FileOperationException {
        if (FileItem.ROOT_ID.equals(normalizeId(fileId))) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "Cannot delete SAF root");
        }
        Uri uri = uriForId(fileId);
        try {
            if (!DocumentsContract.deleteDocument(resolver, uri)) {
                throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot delete SAF document: " + uri);
            }
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot delete SAF document: " + uri, e);
        }
    }

    @Override
    public FileItem rename(String fileId, String newDisplayName) throws FileOperationException {
        String safeName = FileRepositoryUtils.requireDisplayName(newDisplayName);
        Uri uri = uriForId(fileId);
        String parentId = parentId(fileId);
        try {
            Uri renamed = DocumentsContract.renameDocument(resolver, uri, safeName);
            if (renamed == null) {
                throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot rename SAF document: " + uri);
            }
            return querySingleItem(renamed, parentId);
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot rename SAF document: " + uri, e);
        }
    }

    @Override
    public boolean supports(FileOperation operation) {
        return space.supports(operation);
    }

    private FileItem querySingleItem(Uri uri, String parentId) throws FileOperationException {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, DOCUMENT_PROJECTION, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                throw new FileOperationException(FileOperationException.Code.NOT_FOUND, "Cannot query SAF document: " + uri);
            }
            return toItemFromCursor(cursor, parentId);
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot query SAF document: " + uri, e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private FileItem toItemFromCursor(Cursor cursor, String parentId) throws FileOperationException {
        String documentId = getString(cursor, Document.COLUMN_DOCUMENT_ID);
        String name = getString(cursor, Document.COLUMN_DISPLAY_NAME);
        String mimeType = getString(cursor, Document.COLUMN_MIME_TYPE);
        boolean directory = SAF_DIRECTORY_MIME.equals(mimeType);
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(config.getTreeUri(), documentId);
        SafTreeUriGuard.validateDocumentUri(config.getTreeUri(), documentUri);
        int flags = getInt(cursor, Document.COLUMN_FLAGS);
        return FileItem.builder(space.getId(), documentUri.toString(), name, directory)
            .parentId(parentId)
            .size(directory ? -1 : getLong(cursor, Document.COLUMN_SIZE))
            .lastModifiedMillis(getLong(cursor, Document.COLUMN_LAST_MODIFIED))
            .mimeType(mimeType == null ? FileRepositoryUtils.guessMimeType(name, directory) : mimeType)
            .readable(true)
            .writable((flags & Document.FLAG_SUPPORTS_WRITE) != 0 || (directory && (flags & Document.FLAG_DIR_SUPPORTS_CREATE) != 0))
            .deletable((flags & Document.FLAG_SUPPORTS_DELETE) != 0)
            .nativeLocation(documentUri.toString())
            .build();
    }

    private Uri uriForId(String fileId) throws FileOperationException {
        return SafTreeUriGuard.uriForId(config.getTreeUri(), fileId);
    }

    private static String normalizeId(String fileId) {
        return fileId == null || "/".equals(fileId) ? FileItem.ROOT_ID : fileId;
    }

    private String parentId(String fileId) {
        if (fileId == null || fileId.isEmpty()) return FileItem.ROOT_ID;
        try {
            String documentId = DocumentsContract.getDocumentId(uriForId(fileId));
            int slash = documentId.lastIndexOf('/');
            if (slash < 0) return FileItem.ROOT_ID;
            String parentDocumentId = documentId.substring(0, slash);
            return DocumentsContract.buildDocumentUriUsingTree(config.getTreeUri(), parentDocumentId).toString();
        } catch (Exception e) {
            return FileItem.ROOT_ID;
        }
    }

    private static String getString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    private static long getLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? -1 : cursor.getLong(index);
    }

    private static int getInt(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? 0 : cursor.getInt(index);
    }

    private void tryDelete(Uri uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (Exception ignored) {
        }
    }
}
