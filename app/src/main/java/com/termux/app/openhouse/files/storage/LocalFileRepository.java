package com.termux.app.openhouse.files.storage;

import com.termux.app.openhouse.files.model.FileItem;
import com.termux.app.openhouse.files.model.FileOperation;
import com.termux.app.openhouse.files.model.FileOperationException;
import com.termux.app.openhouse.files.model.FileRepository;
import com.termux.app.openhouse.files.model.FileSpace;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class LocalFileRepository implements FileRepository {

    private final FileSpace space;
    private final File root;

    public LocalFileRepository(FileSpace space, File root) {
        if (space == null) throw new IllegalArgumentException("space == null");
        if (root == null) throw new IllegalArgumentException("root == null");
        this.space = space;
        this.root = root.getAbsoluteFile();
    }

    @Override
    public FileSpace getSpace() {
        return space;
    }

    @Override
    public FileItem getRoot() throws FileOperationException {
        if (!root.isDirectory()) {
            if (root.exists()) {
                throw new FileOperationException(FileOperationException.Code.INVALID_PATH,
                    "Root is not a directory: " + root.getAbsolutePath());
            }
            throw new FileOperationException(FileOperationException.Code.NOT_FOUND,
                "Root does not exist: " + root.getAbsolutePath());
        }
        return toItem(FileItem.ROOT_ID, root, FileItem.ROOT_ID);
    }

    @Override
    public List<FileItem> list(String parentId) throws FileOperationException {
        File parent = resolveExisting(parentId);
        if (!parent.isDirectory()) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "Not a directory: " + parentId);
        }
        File[] files = parent.listFiles();
        if (files == null) {
            throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot list: " + parent.getAbsolutePath());
        }
        List<FileItem> items = new ArrayList<>();
        String normalizedParentId = normalizeId(parentId);
        for (File file : files) {
            String childId = childId(normalizedParentId, file.getName());
            items.add(toItem(childId, file, normalizedParentId));
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
        File file = resolveExisting(fileId);
        if (!file.isFile()) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "Not a file: " + fileId);
        }
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException e) {
            throw mapIOException("Cannot open file: " + file.getAbsolutePath(), e);
        }
    }

    @Override
    public OutputStream openOutputStream(String fileId, String mimeType) throws FileOperationException {
        String normalized = normalizeId(fileId);
        rejectSymlinkParentMutation(normalized, "write");
        File file = resolve(normalized);
        File parent = file.getParentFile();
        ensureDirectory(parent);
        try {
            return new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            throw mapIOException("Cannot write file: " + file.getAbsolutePath(), e);
        }
    }

    @Override
    public FileItem upload(String parentId, String displayName, InputStream input, long size, String mimeType)
        throws FileOperationException {
        String safeName = FileRepositoryUtils.requireDisplayName(displayName);
        String id = childId(normalizeId(parentId), safeName);
        File file = resolve(id);
        try (OutputStream output = openOutputStream(id, mimeType)) {
            FileRepositoryUtils.copy(input, output);
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw mapIOException("Cannot upload file: " + file.getAbsolutePath(), e);
        }
        return toItem(id, file, normalizeId(parentId));
    }

    @Override
    public void download(String fileId, OutputStream output) throws FileOperationException {
        try (InputStream input = openInputStream(fileId)) {
            FileRepositoryUtils.copy(input, output);
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw mapIOException("Cannot download file: " + fileId, e);
        }
    }

    @Override
    public FileItem createDirectory(String parentId, String displayName) throws FileOperationException {
        String safeName = FileRepositoryUtils.requireDisplayName(displayName);
        String normalizedParentId = normalizeId(parentId);
        String id = childId(normalizedParentId, safeName);
        rejectSymlinkParentMutation(id, "create directory");
        File directory = resolve(id);
        if (directory.exists()) {
            throw new FileOperationException(FileOperationException.Code.CONFLICT, "Directory already exists: " + id);
        }
        ensureDirectory(directory.getParentFile());
        if (!directory.mkdirs()) {
            throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot create directory: " + directory.getAbsolutePath());
        }
        return toItem(id, directory, normalizedParentId);
    }

    @Override
    public void delete(String fileId) throws FileOperationException {
        String normalized = normalizeId(fileId);
        if (FileItem.ROOT_ID.equals(normalized)) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "Cannot delete root");
        }
        rejectSymlinkParentMutation(normalized, "delete");
        File file = resolveExisting(normalized);
        deleteRecursively(file);
    }

    @Override
    public FileItem rename(String fileId, String newDisplayName) throws FileOperationException {
        String normalized = normalizeId(fileId);
        rejectSymlinkParentMutation(normalized, "rename");
        File source = resolveExisting(normalized);
        String safeName = FileRepositoryUtils.requireDisplayName(newDisplayName);
        String parentId = parentId(normalized);
        String targetId = childId(parentId, safeName);
        rejectSymlinkParentMutation(targetId, "rename target");
        File target = resolve(targetId);
        if (target.exists()) {
            throw new FileOperationException(FileOperationException.Code.CONFLICT, "Target already exists: " + safeName);
        }
        if (!source.renameTo(target)) {
            throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot rename: " + source.getAbsolutePath());
        }
        return toItem(targetId, target, parentId);
    }

    @Override
    public boolean supports(FileOperation operation) {
        return space.supports(operation);
    }

    private static void ensureDirectory(File dir) throws FileOperationException {
        if (dir == null || dir.isDirectory()) return;
        if (dir.exists()) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH,
                "Path is not a directory: " + dir.getAbsolutePath());
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED,
                "Cannot create directory: " + dir.getAbsolutePath());
        }
    }

    private File resolveExisting(String fileId) throws FileOperationException {
        File file = resolve(fileId);
        if (!file.exists()) {
            throw new FileOperationException(FileOperationException.Code.NOT_FOUND, "File not found: " + fileId);
        }
        return file;
    }

    private File resolve(String fileId) throws FileOperationException {
        String normalized = normalizeId(fileId);
        if (FileItem.ROOT_ID.equals(normalized)) return root;
        return new File(root, normalized.replace('/', File.separatorChar));
    }

    private String normalizeId(String id) throws FileOperationException {
        if (id == null || id.isEmpty() || "/".equals(id)) return FileItem.ROOT_ID;
        String normalized = id.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/") && normalized.length() > 1) normalized = normalized.substring(0, normalized.length() - 1);
        String[] parts = normalized.split("/");
        List<String> cleanParts = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "Path cannot escape root: " + id);
            }
            cleanParts.add(part);
        }
        if (cleanParts.isEmpty()) return FileItem.ROOT_ID;
        StringBuilder result = new StringBuilder();
        for (String part : cleanParts) {
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    private FileItem toItem(String id, File file, String parentId) {
        boolean directory = file.isDirectory();
        return FileItem.builder(space.getId(), id, FileItem.ROOT_ID.equals(id) ? space.getRootLabel() : file.getName(), directory)
            .parentId(parentId)
            .size(directory ? -1 : file.length())
            .lastModifiedMillis(file.lastModified())
            .mimeType(FileRepositoryUtils.guessMimeType(file.getName(), directory))
            .readable(file.canRead())
            .writable(file.canWrite())
            .deletable(!FileItem.ROOT_ID.equals(id) && file.getParentFile() != null && file.getParentFile().canWrite())
            .nativeLocation(file.toURI().toString())
            .build();
    }

    private static String childId(String parentId, String displayName) {
        if (parentId == null || parentId.isEmpty()) return displayName;
        return parentId + "/" + displayName;
    }

    private static String parentId(String id) {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? FileItem.ROOT_ID : id.substring(0, slash);
    }

    private void rejectSymlinkParentMutation(String normalizedId, String operation) throws FileOperationException {
        if (normalizedId == null || normalizedId.isEmpty()) return;
        String[] parts = normalizedId.split("/");
        if (parts.length <= 1) return;
        File current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            current = new File(current, parts[i]);
            if (!current.exists()) return;
            if (isSymlink(current)) {
                throw new FileOperationException(
                    FileOperationException.Code.PERMISSION_DENIED,
                    "Cannot " + operation + " through symlink parent: " + parts[i]);
            }
        }
    }

    private static void deleteRecursively(File file) throws FileOperationException {
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED, "Cannot delete: " + file.getAbsolutePath());
        }
    }

    private static boolean isSymlink(File file) throws FileOperationException {
        try {
            File absolute = file.getAbsoluteFile();
            File canonicalParent = absolute.getParentFile() == null ? null : absolute.getParentFile().getCanonicalFile();
            File fileInCanonicalParent = canonicalParent == null ? absolute : new File(canonicalParent, absolute.getName());
            return !fileInCanonicalParent.getAbsoluteFile().equals(fileInCanonicalParent.getCanonicalFile());
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.UNKNOWN, "Cannot inspect symlink: " + file.getAbsolutePath(), e);
        }
    }

    private static FileOperationException mapIOException(String message, Throwable cause) {
        return new FileOperationException(FileOperationException.Code.UNKNOWN, message, cause);
    }
}
