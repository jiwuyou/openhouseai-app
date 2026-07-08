package com.termux.app.openhouse.files.network;

import com.termux.app.openhouse.files.model.FileItem;
import com.termux.app.openhouse.files.model.FileOperationException;
import com.termux.app.openhouse.files.storage.FileRepositoryUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class NetworkRepositoryUtils {

    private NetworkRepositoryUtils() {
    }

    public static MediaType mediaType(String mimeType) {
        String resolved = mimeType == null || mimeType.trim().isEmpty() ? "application/octet-stream" : mimeType;
        return MediaType.parse(resolved);
    }

    public static byte[] toByteArray(InputStream input) throws FileOperationException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            FileRepositoryUtils.copy(input, output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot read stream", e);
        }
    }

    public static RequestBody requestBody(byte[] bytes, String mimeType) {
        return RequestBody.create(bytes, mediaType(mimeType));
    }

    public static void copyResponseBody(Response response, OutputStream output) throws FileOperationException {
        try {
            ResponseBody body = response.body();
            if (body == null) {
                throw new FileOperationException(FileOperationException.Code.NETWORK, "Response has no body");
            }
            try (InputStream input = body.byteStream()) {
                FileRepositoryUtils.copy(input, output);
            }
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot copy response body", e);
        }
    }

    public static String normalizeDirectoryId(String id) throws FileOperationException {
        if (id == null || id.isEmpty() || "/".equals(id)) return FileItem.ROOT_ID;
        String result = normalizeObjectId(id);
        return result.isEmpty() || result.endsWith("/") ? result : result + "/";
    }

    public static String normalizeObjectId(String id) throws FileOperationException {
        if (id == null || id.isEmpty() || "/".equals(id)) return FileItem.ROOT_ID;
        if (id.indexOf('\\') >= 0) {
            throw invalidPath("Backslashes are not allowed in network file ids: " + id);
        }
        if (id.startsWith("/")) {
            throw invalidPath("Network file ids must be relative: " + id);
        }
        String[] parts = id.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean trailingDirectoryMarker = i == parts.length - 1 && part.isEmpty();
            if (trailingDirectoryMarker) break;
            if (part.isEmpty()) {
                throw invalidPath("Empty path segment is not allowed in network file id: " + id);
            }
            if (".".equals(part) || "..".equals(part)) {
                throw invalidPath("Dot path segments are not allowed in network file id: " + id);
            }
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        if (result.length() == 0 && id.endsWith("/")) {
            throw invalidPath("Root must be addressed as an empty id or /");
        }
        if (id.endsWith("/") && result.length() > 0) result.append('/');
        return result.toString();
    }

    public static String requireNonRootObjectId(String id, String operation) throws FileOperationException {
        String normalized = normalizeObjectId(id);
        if (normalized.isEmpty()) {
            throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, operation + " is not allowed for the root");
        }
        return normalized;
    }

    public static String displayNameForId(String id) throws FileOperationException {
        String normalized = normalizeObjectId(id);
        if (normalized.isEmpty()) return "";
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    public static String parentIdForId(String id) throws FileOperationException {
        String normalized = normalizeObjectId(id);
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        int slash = normalized.lastIndexOf('/');
        if (slash < 0) return FileItem.ROOT_ID;
        return normalized.substring(0, slash + 1);
    }

    public static String childId(String parentId, String displayName, boolean directory) throws FileOperationException {
        String parent = normalizeDirectoryId(parentId);
        String child = parent + displayName;
        return directory && !child.endsWith("/") ? child + "/" : child;
    }

    private static FileOperationException invalidPath(String message) {
        return new FileOperationException(FileOperationException.Code.INVALID_PATH, message);
    }
}
