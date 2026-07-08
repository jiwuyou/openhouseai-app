package com.termux.app.openhouse.files.saf;

import android.net.Uri;
import android.provider.DocumentsContract;

import com.termux.app.openhouse.files.model.FileItem;
import com.termux.app.openhouse.files.model.FileOperationException;

import java.util.List;

public final class SafTreeUriGuard {

    private SafTreeUriGuard() {
    }

    public static Uri uriForId(Uri treeUri, String fileId) throws FileOperationException {
        validateTreeUri(treeUri);
        if (fileId == null || fileId.isEmpty() || "/".equals(fileId)) {
            String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
        }
        Uri documentUri = Uri.parse(fileId);
        validateDocumentUri(treeUri, documentUri);
        return documentUri;
    }

    public static void validateTreeUri(Uri treeUri) throws FileOperationException {
        if (treeUri == null) throw invalid("SAF tree uri is null");
        if (!"content".equals(treeUri.getScheme())) throw invalid("SAF tree uri must use content scheme: " + treeUri);
        if (treeUri.getAuthority() == null || treeUri.getAuthority().isEmpty()) {
            throw invalid("SAF tree uri has no authority: " + treeUri);
        }
        try {
            DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "Invalid SAF tree uri: " + treeUri, e);
        }
    }

    public static void validateDocumentUri(Uri treeUri, Uri documentUri) throws FileOperationException {
        validateTreeUri(treeUri);
        if (documentUri == null) throw invalid("SAF document uri is null");
        if (!"content".equals(documentUri.getScheme())) throw invalid("SAF document uri must use content scheme: " + documentUri);
        if (!treeUri.getAuthority().equals(documentUri.getAuthority())) {
            throw invalid("SAF document uri authority is outside the configured tree: " + documentUri);
        }

        List<String> segments = documentUri.getPathSegments();
        if (segments.size() < 4
            || !"tree".equals(segments.get(0))
            || !"document".equals(segments.get(2))) {
            throw invalid("SAF document uri is not a tree document uri: " + documentUri);
        }

        String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        String uriTreeDocumentId = DocumentsContract.getTreeDocumentId(documentUri);
        String documentId = DocumentsContract.getDocumentId(documentUri);
        if (!treeDocumentId.equals(uriTreeDocumentId)) {
            throw invalid("SAF document uri belongs to a different tree: " + documentUri);
        }
        if (!isDocumentIdInsideTree(treeDocumentId, documentId)) {
            throw invalid("SAF document uri is outside the configured tree: " + documentUri);
        }
        if (containsDotPathSegment(documentId.substring(treeDocumentId.length()))) {
            throw invalid("SAF document id contains a dot path segment: " + documentUri);
        }
    }

    public static boolean isDocumentIdInsideTree(String treeDocumentId, String documentId) {
        if (treeDocumentId == null || documentId == null) return false;
        if (documentId.equals(treeDocumentId) || documentId.startsWith(treeDocumentId + "/")) {
            return true;
        }
        return treeDocumentId.endsWith(":") && documentId.startsWith(treeDocumentId);
    }

    private static boolean containsDotPathSegment(String suffix) {
        if (suffix == null || suffix.isEmpty()) return false;
        String[] parts = suffix.split("/");
        for (String part : parts) {
            if (".".equals(part) || "..".equals(part)) return true;
        }
        return false;
    }

    private static FileOperationException invalid(String message) {
        return new FileOperationException(FileOperationException.Code.INVALID_PATH, message);
    }
}
