package com.termux.filepicker;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import com.termux.R;
import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;
import com.termux.app.openhouse.files.core.OpenHouseUbuntuPaths;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * DocumentsProvider exposing stable OpenHouse file roots through Android SAF.
 */
public class TermuxDocumentsProvider extends DocumentsProvider {

    private static final String ALL_MIME_TYPES = "*/*";
    static final String ROOT_TERMUX_HOME = "termux-home";
    static final String ROOT_OPENHOUSE_WORKSPACE = "openhouse-workspace";
    static final String ROOT_UBUNTU_ROOT = "ubuntu-root";
    static final String DOC_ID_SEPARATOR = ":";
    private static final int MAX_SEARCH_RESULTS = 50;
    private static final int MAX_SEARCH_VISITED_DIRECTORIES = 2000;

    private static final File TERMUX_HOME_DIR = TermuxConstants.TERMUX_HOME_DIR;

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    };

    @Override
    public Cursor queryRoots(String[] projection) {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        for (RootConfig root : getRoots()) {
            includeRoot(result, root);
        }
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(parentDocumentId);
        File[] files = parent.listFiles();
        if (files == null) {
            return result;
        }
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, files);
        Collections.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                boolean leftDir = isBrowsableDirectory(left);
                boolean rightDir = isBrowsableDirectory(right);
                if (leftDir != rightDir) return leftDir ? -1 : 1;
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        for (File file : sorted) {
            try {
                includeFile(result, null, file);
            } catch (FileNotFoundException ignored) {
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, file.length());
    }

    @Override
    public boolean onCreate() {
        ensureOpenHouseWorkspaceRoot();
        return true;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = getFileForDocId(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Parent is not a directory: " + parentDocumentId);
        }
        String safeDisplayName = sanitizeDisplayName(displayName);
        File newFile = new File(parent, safeDisplayName);
        int noConflictId = 2;
        while (newFile.exists()) {
            newFile = new File(parent, safeDisplayName + " (" + noConflictId++ + ")");
        }
        try {
            boolean succeeded;
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                succeeded = newFile.mkdir();
            } else {
                succeeded = newFile.createNewFile();
            }
            if (!succeeded) {
                throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
        }
        return getDocIdForFile(newFile);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (isRootDocumentId(documentId)) {
            throw new FileNotFoundException("Cannot delete root document " + documentId);
        }
        if (!deleteRecursively(file)) {
            throw new FileNotFoundException("Failed to delete document with id " + documentId);
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (isRootDocumentId(documentId)) {
            throw new FileNotFoundException("Cannot rename root document " + documentId);
        }
        File target = new File(file.getParentFile(), sanitizeDisplayName(displayName));
        if (target.exists()) {
            throw new FileNotFoundException("Target already exists " + target.getAbsolutePath());
        }
        if (!file.renameTo(target)) {
            throw new FileNotFoundException("Failed to rename document with id " + documentId);
        }
        return getDocIdForFile(target);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return getMimeType(file);
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        RootConfig root = getRoot(rootId);
        if (root == null) {
            throw new FileNotFoundException("Unknown root " + rootId);
        }
        final File rootDir = root.baseDir;
        final LinkedList<File> pending = new LinkedList<>();
        final Set<String> visited = new HashSet<>();
        pending.add(rootDir);

        String normalizedQuery = query == null ? "" : query.toLowerCase();
        int visitedDirectories = 0;
        while (!pending.isEmpty()
            && result.getCount() < MAX_SEARCH_RESULTS
            && visitedDirectories < MAX_SEARCH_VISITED_DIRECTORIES) {
            final File file = pending.removeFirst();
            if (!isSameOrChild(rootDir, file)) {
                continue;
            }
            if (isBrowsableDirectory(file)) {
                String canonical = canonicalPath(file);
                if (canonical != null && !visited.add(canonical)) {
                    continue;
                }
                visitedDirectories++;
                File[] children = file.listFiles();
                if (children != null) Collections.addAll(pending, children);
            } else if (file.getName().toLowerCase().contains(normalizedQuery)) {
                try {
                    includeFile(result, null, file);
                } catch (FileNotFoundException ignored) {
                }
            }
        }

        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            RootConfig parentRoot = getRootForDocId(parentDocumentId);
            RootConfig childRoot = getRootForDocId(documentId);
            if (parentRoot == null || childRoot == null || !parentRoot.id.equals(childRoot.id)) {
                return false;
            }
            File parent = getFileForDocId(parentDocumentId);
            File child = getFileForDocId(documentId);
            return isSameOrChild(parent, child);
        } catch (Exception e) {
            return false;
        }
    }

    private void includeRoot(MatrixCursor result, RootConfig root) {
        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, root.id);
        row.add(Root.COLUMN_DOCUMENT_ID, getRootDocId(root.id));
        row.add(Root.COLUMN_SUMMARY, root.summary);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, root.title);
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
        row.add(Root.COLUMN_AVAILABLE_BYTES, root.baseDir.getFreeSpace());
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
    }

    private List<RootConfig> getRoots() {
        ensureOpenHouseWorkspaceRoot();
        List<RootConfig> roots = new ArrayList<>();
        roots.add(new RootConfig(
            ROOT_TERMUX_HOME,
            "Termux Home",
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            TERMUX_HOME_DIR));
        File workspace = OpenHouseWorkspacePaths.forTermuxHome(TERMUX_HOME_DIR).getTermuxWorkspaceDir();
        roots.add(new RootConfig(
            ROOT_OPENHOUSE_WORKSPACE,
            "OpenHouse Workspace",
            workspace.getAbsolutePath(),
            workspace));
        File ubuntuRoot = OpenHouseUbuntuPaths.findUbuntuHomeDir(TERMUX_HOME_DIR);
        if (ubuntuRoot != null) {
            roots.add(new RootConfig(
                ROOT_UBUNTU_ROOT,
                "Ubuntu Root",
                ubuntuRoot.getAbsolutePath(),
                ubuntuRoot));
        }
        return roots;
    }

    private RootConfig getRoot(String rootId) {
        for (RootConfig root : getRoots()) {
            if (root.id.equals(rootId)) return root;
        }
        return null;
    }

    static String buildDocumentId(String rootId, String relativePath) {
        String root = rootId == null ? "" : rootId.trim();
        String relative = relativePath == null ? "" : relativePath.replace('\\', '/');
        while (relative.startsWith("/")) relative = relative.substring(1);
        return root + DOC_ID_SEPARATOR + relative;
    }

    static String getRootIdFromDocumentId(String documentId) {
        int separator = documentId == null ? -1 : documentId.indexOf(DOC_ID_SEPARATOR);
        if (separator <= 0) return "";
        return documentId.substring(0, separator);
    }

    static String getRelativePathFromDocumentId(String documentId) {
        int separator = documentId == null ? -1 : documentId.indexOf(DOC_ID_SEPARATOR);
        if (separator < 0 || separator + DOC_ID_SEPARATOR.length() >= documentId.length()) return "";
        return documentId.substring(separator + DOC_ID_SEPARATOR.length());
    }

    private static String getRootDocId(String rootId) {
        return buildDocumentId(rootId, "");
    }

    private String getDocIdForFile(File file) throws FileNotFoundException {
        RootConfig bestRoot = null;
        String bestRelative = null;
        for (RootConfig root : getRoots()) {
            String relative = relativePath(root.baseDir, file);
            if (relative != null && (bestRelative == null || relative.length() < bestRelative.length())) {
                bestRoot = root;
                bestRelative = relative;
            }
        }
        if (bestRoot == null) {
            throw new FileNotFoundException("File is outside exposed roots: " + file.getAbsolutePath());
        }
        return buildDocumentId(bestRoot.id, bestRelative);
    }

    private File getFileForDocId(String docId) throws FileNotFoundException {
        if (docId == null || docId.trim().isEmpty()) {
            throw new FileNotFoundException("Empty document id");
        }
        if (docId.startsWith("/")) {
            return getLegacyFileForDocId(docId);
        }
        RootConfig root = getRootForDocId(docId);
        if (root == null) {
            throw new FileNotFoundException("Unknown root for document id " + docId);
        }
        String relative = getRelativePathFromDocumentId(docId);
        File file = relative.isEmpty() ? root.baseDir : new File(root.baseDir, relative);
        if (!isSameOrChild(root.baseDir, file)) {
            throw new FileNotFoundException("Document escapes root " + docId);
        }
        if (!file.exists()) {
            throw new FileNotFoundException(file.getAbsolutePath() + " not found");
        }
        return file;
    }

    private RootConfig getRootForDocId(String docId) {
        String rootId = getRootIdFromDocumentId(docId);
        return rootId.isEmpty() ? null : getRoot(rootId);
    }

    private File getLegacyFileForDocId(String docId) throws FileNotFoundException {
        final File f = new File(docId);
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath() + " not found");
        if (!isSameOrChild(TERMUX_HOME_DIR, f)) {
            throw new FileNotFoundException("Legacy document id outside Termux home: " + docId);
        }
        return f;
    }

    private static String getMimeType(File file) {
        if (isBrowsableDirectory(file)) {
            return Document.MIME_TYPE_DIR;
        }
        final String name = file.getName();
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1).toLowerCase();
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private void includeFile(MatrixCursor result, String docId, File file)
        throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        boolean directory = isBrowsableDirectory(file);
        int flags = 0;
        if (directory) {
            if (file.canWrite()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        File parentFile = file.getParentFile();
        if (!isRootDocumentId(docId) && parentFile != null && parentFile.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        }

        final String displayName = isRootDocumentId(docId) ? rootTitleForDocId(docId, file.getName()) : file.getName();
        final String mimeType = getMimeType(file);
        if (mimeType.startsWith("image/")) flags |= Document.FLAG_SUPPORTS_THUMBNAIL;

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, directory ? -1 : file.length());
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_ICON, R.mipmap.ic_launcher);
    }

    private String rootTitleForDocId(String docId, String fallback) {
        RootConfig root = getRootForDocId(docId);
        return root == null ? fallback : root.title;
    }

    private static boolean isRootDocumentId(String docId) {
        return docId != null && docId.endsWith(DOC_ID_SEPARATOR);
    }

    private static String sanitizeDisplayName(String displayName) throws FileNotFoundException {
        if (displayName == null) throw new FileNotFoundException("Empty display name");
        String name = displayName.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
            || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException("Invalid display name " + displayName);
        }
        return name;
    }

    private static boolean deleteRecursively(File file) {
        if (isBrowsableDirectory(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private static boolean isBrowsableDirectory(File file) {
        return file != null && file.isDirectory() && !isSymlink(file);
    }

    private static boolean isSameOrChild(File root, File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            String rootPath = root.getAbsolutePath();
            String filePath = file.getAbsolutePath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        }
    }

    private static String relativePath(File root, File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (filePath.equals(rootPath)) return "";
            if (filePath.startsWith(rootPath + File.separator)) {
                return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static boolean isSymlink(File file) {
        try {
            File absolute = file.getAbsoluteFile();
            File parent = absolute.getParentFile();
            File canonicalParent = parent == null ? null : parent.getCanonicalFile();
            File fileInCanonicalParent = canonicalParent == null ? absolute : new File(canonicalParent, absolute.getName());
            return !fileInCanonicalParent.getAbsoluteFile().equals(fileInCanonicalParent.getCanonicalFile());
        } catch (IOException e) {
            return true;
        }
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    private static void ensureOpenHouseWorkspaceRoot() {
        try {
            OpenHouseWorkspacePaths.forTermuxHome(TERMUX_HOME_DIR).ensureTermuxWorkspaceDirs();
        } catch (IOException ignored) {
        }
    }

    private static final class RootConfig {
        final String id;
        final String title;
        final String summary;
        final File baseDir;

        RootConfig(String id, String title, String summary, File baseDir) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.baseDir = baseDir;
        }
    }
}
