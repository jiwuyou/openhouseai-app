package com.termux.app.openhouse.files.network.webdav;

import com.termux.app.openhouse.files.model.FileItem;
import com.termux.app.openhouse.files.model.FileOperation;
import com.termux.app.openhouse.files.model.FileOperationException;
import com.termux.app.openhouse.files.model.FileRepository;
import com.termux.app.openhouse.files.model.FileSpace;
import com.termux.app.openhouse.files.model.FileSpaceType;
import com.termux.app.openhouse.files.network.HttpResponseInputStream;
import com.termux.app.openhouse.files.network.InputStreamRequestBody;
import com.termux.app.openhouse.files.network.NetworkRepositoryUtils;
import com.termux.app.openhouse.files.storage.FileRepositoryUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class WebDavFileRepository implements FileRepository {

    private static final String PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
            "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
            "<D:displayname/><D:resourcetype/><D:getcontentlength/>" +
            "<D:getcontenttype/><D:getlastmodified/>" +
            "</D:prop></D:propfind>";

    private final OkHttpClient client;
    private final WebDavConfig config;
    private final FileSpace space;

    public WebDavFileRepository(OkHttpClient client, WebDavConfig config) {
        if (client == null) throw new IllegalArgumentException("client == null");
        if (config == null) throw new IllegalArgumentException("config == null");
        this.client = client;
        this.config = config;
        this.space = FileSpace.builder(config.getId(), FileSpaceType.WEBDAV, config.getDisplayName())
            .rootLabel(config.getDisplayName())
            .locationSummary(config.getBaseUrl().toString())
            .metadata("baseUrl", config.getBaseUrl().toString())
            .build();
    }

    @Override
    public FileSpace getSpace() {
        return space;
    }

    @Override
    public FileItem getRoot() {
        return FileItem.builder(space.getId(), FileItem.ROOT_ID, space.getRootLabel(), true)
            .parentId(FileItem.ROOT_ID)
            .mimeType("vnd.android.document/directory")
            .deletable(false)
            .nativeLocation(config.getBaseUrl().toString())
            .build();
    }

    @Override
    public List<FileItem> list(String parentId) throws FileOperationException {
        String normalizedParent = NetworkRepositoryUtils.normalizeDirectoryId(parentId);
        HttpUrl url = urlForId(normalizedParent);
        RequestBody body = RequestBody.create(PROPFIND_BODY.getBytes(StandardCharsets.UTF_8), MediaType.parse("application/xml; charset=utf-8"));
        Request request = withAuth(new Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .header("Accept", "application/xml, text/xml")
            .build());
        try (Response response = client.newCall(request).execute()) {
            expectStatus(response, 207, "PROPFIND " + url);
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new FileOperationException(FileOperationException.Code.NETWORK, "WebDAV PROPFIND returned no body");
            }
            List<WebDavResource> resources = WebDavXmlParser.parse(responseBody.string());
            List<FileItem> items = new ArrayList<>();
            for (WebDavResource resource : resources) {
                String id = idFromHref(resource.getHref(), resource.isDirectory());
                if (sameDirectoryId(id, normalizedParent)) continue;
                if (id.isEmpty()) continue;
                items.add(toItem(id, normalizedParent, resource));
            }
            Collections.sort(items, new Comparator<FileItem>() {
                @Override
                public int compare(FileItem left, FileItem right) {
                    if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
                    return left.getDisplayName().compareToIgnoreCase(right.getDisplayName());
                }
            });
            return items;
        } catch (FileOperationException e) {
            throw e;
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot list WebDAV directory: " + url, e);
        }
    }

    @Override
    public InputStream openInputStream(String fileId) throws FileOperationException {
        String id = NetworkRepositoryUtils.requireNonRootObjectId(fileId, "Open WebDAV file");
        HttpUrl url = urlForId(id);
        Request request = withAuth(new Request.Builder().url(url).get().build());
        try {
            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) {
                FileOperationException error = statusException(response, "GET " + url);
                response.close();
                throw error;
            }
            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                throw new FileOperationException(FileOperationException.Code.NETWORK, "WebDAV GET returned no body");
            }
            return new HttpResponseInputStream(response, body.byteStream());
        } catch (FileOperationException e) {
            throw e;
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot open WebDAV file: " + url, e);
        }
    }

    @Override
    public OutputStream openOutputStream(final String fileId, final String mimeType) {
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                try {
                    putBytes(fileId, toByteArray(), mimeType);
                } catch (FileOperationException e) {
                    throw new IOException(e);
                }
            }
        };
    }

    @Override
    public FileItem upload(String parentId, String displayName, InputStream input, long size, String mimeType)
        throws FileOperationException {
        String safeName = FileRepositoryUtils.requireDisplayName(displayName);
        String id = NetworkRepositoryUtils.childId(parentId, safeName, false);
        HttpUrl url = urlForId(id);
        RequestBody body = new InputStreamRequestBody(NetworkRepositoryUtils.mediaType(mimeType), input, size);
        Request request = withAuth(new Request.Builder().url(url).put(body).build());
        try (Response response = client.newCall(request).execute()) {
            expectSuccessful(response, "PUT " + url);
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot upload WebDAV file: " + url, e);
        }
        return itemForKnownId(id, false, mimeType, size);
    }

    @Override
    public void download(String fileId, OutputStream output) throws FileOperationException {
        try (InputStream input = openInputStream(fileId)) {
            FileRepositoryUtils.copy(input, output);
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot download WebDAV file: " + fileId, e);
        }
    }

    @Override
    public FileItem createDirectory(String parentId, String displayName) throws FileOperationException {
        String safeName = FileRepositoryUtils.requireDisplayName(displayName);
        String id = NetworkRepositoryUtils.childId(parentId, safeName, true);
        HttpUrl url = urlForId(id);
        Request request = withAuth(new Request.Builder().url(url).method("MKCOL", RequestBody.create(new byte[0], null)).build());
        try (Response response = client.newCall(request).execute()) {
            expectSuccessful(response, "MKCOL " + url);
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot create WebDAV directory: " + url, e);
        }
        return itemForKnownId(id, true, "vnd.android.document/directory", -1);
    }

    @Override
    public void delete(String fileId) throws FileOperationException {
        String id = NetworkRepositoryUtils.requireNonRootObjectId(fileId, "Delete WebDAV root");
        HttpUrl url = urlForId(id);
        Request request = withAuth(new Request.Builder().url(url).delete().build());
        try (Response response = client.newCall(request).execute()) {
            expectSuccessful(response, "DELETE " + url);
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot delete WebDAV item: " + url, e);
        }
    }

    @Override
    public FileItem rename(String fileId, String newDisplayName) throws FileOperationException {
        String sourceId = NetworkRepositoryUtils.requireNonRootObjectId(fileId, "Rename WebDAV root");
        String safeName = FileRepositoryUtils.requireDisplayName(newDisplayName);
        boolean directory = sourceId.endsWith("/");
        String parentId = NetworkRepositoryUtils.parentIdForId(sourceId);
        String targetId = NetworkRepositoryUtils.childId(parentId, safeName, directory);
        HttpUrl sourceUrl = urlForId(sourceId);
        HttpUrl targetUrl = urlForId(targetId);
        Request request = withAuth(new Request.Builder()
            .url(sourceUrl)
            .method("MOVE", RequestBody.create(new byte[0], null))
            .header("Destination", targetUrl.toString())
            .header("Overwrite", "F")
            .build());
        try (Response response = client.newCall(request).execute()) {
            expectSuccessful(response, "MOVE " + sourceUrl);
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot rename WebDAV item: " + sourceUrl, e);
        }
        return itemForKnownId(targetId, directory, directory ? "vnd.android.document/directory" : null, -1);
    }

    @Override
    public boolean supports(FileOperation operation) {
        return space.supports(operation);
    }

    private void putBytes(String fileId, byte[] bytes, String mimeType) throws FileOperationException {
        String id = NetworkRepositoryUtils.requireNonRootObjectId(fileId, "Write WebDAV file");
        HttpUrl url = urlForId(id);
        Request request = withAuth(new Request.Builder()
            .url(url)
            .put(NetworkRepositoryUtils.requestBody(bytes, mimeType))
            .build());
        try (Response response = client.newCall(request).execute()) {
            expectSuccessful(response, "PUT " + url);
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot write WebDAV file: " + url, e);
        }
    }

    private Request withAuth(Request request) {
        if (!config.hasBasicAuth()) return request;
        return request.newBuilder()
            .header("Authorization", Credentials.basic(config.getUsername(), config.getPassword()))
            .build();
    }

    private HttpUrl urlForId(String id) throws FileOperationException {
        HttpUrl.Builder builder = config.getBaseUrl().newBuilder();
        String normalized = NetworkRepositoryUtils.normalizeObjectId(id);
        if (!normalized.isEmpty()) {
            String[] parts = normalized.split("/", -1);
            for (String part : parts) builder.addPathSegment(part);
        }
        return builder.build();
    }

    private String idFromHref(String href, boolean directory) throws FileOperationException {
        HttpUrl resolved = config.getBaseUrl().resolve(href);
        if (resolved == null) return NetworkRepositoryUtils.normalizeObjectId(href);
        if (!sameOrigin(config.getBaseUrl(), resolved)) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "WebDAV href is outside configured origin: " + href);
        }
        List<String> baseSegments = withoutTrailingEmpty(config.getBaseUrl().pathSegments());
        List<String> itemSegments = resolved.pathSegments();
        if (itemSegments.size() < baseSegments.size()) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "WebDAV href is outside configured base path: " + href);
        }
        for (int i = 0; i < baseSegments.size(); i++) {
            if (!baseSegments.get(i).equals(itemSegments.get(i))) {
                throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "WebDAV href is outside configured base path: " + href);
            }
        }
        StringBuilder id = new StringBuilder();
        for (int i = baseSegments.size(); i < itemSegments.size(); i++) {
            String segment = itemSegments.get(i);
            if (segment.isEmpty() && i == itemSegments.size() - 1) continue;
            if (id.length() > 0) id.append('/');
            id.append(segment);
        }
        if (directory && id.length() > 0 && id.charAt(id.length() - 1) != '/') id.append('/');
        return NetworkRepositoryUtils.normalizeObjectId(id.toString());
    }

    private static List<String> withoutTrailingEmpty(List<String> segments) {
        List<String> result = new ArrayList<>(segments);
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static boolean sameOrigin(HttpUrl left, HttpUrl right) {
        return left.scheme().equals(right.scheme())
            && left.host().equals(right.host())
            && left.port() == right.port();
    }

    private FileItem toItem(String id, String parentId, WebDavResource resource) throws FileOperationException {
        String displayName = resource.getDisplayName().isEmpty()
            ? NetworkRepositoryUtils.displayNameForId(id)
            : resource.getDisplayName();
        return FileItem.builder(space.getId(), id, displayName, resource.isDirectory())
            .parentId(parentId)
            .size(resource.isDirectory() ? -1 : resource.getSize())
            .lastModifiedMillis(resource.getLastModifiedMillis())
            .mimeType(resource.getContentType().isEmpty() ? FileRepositoryUtils.guessMimeType(displayName, resource.isDirectory()) : resource.getContentType())
            .nativeLocation(urlForId(id).toString())
            .build();
    }

    private FileItem itemForKnownId(String id, boolean directory, String mimeType, long size) throws FileOperationException {
        String displayName = NetworkRepositoryUtils.displayNameForId(id);
        return FileItem.builder(space.getId(), id, displayName, directory)
            .parentId(NetworkRepositoryUtils.parentIdForId(id))
            .size(directory ? -1 : size)
            .lastModifiedMillis(System.currentTimeMillis())
            .mimeType(mimeType == null ? FileRepositoryUtils.guessMimeType(displayName, directory) : mimeType)
            .nativeLocation(urlForId(id).toString())
            .build();
    }

    private static boolean sameDirectoryId(String left, String right) throws FileOperationException {
        return NetworkRepositoryUtils.normalizeDirectoryId(left).equals(NetworkRepositoryUtils.normalizeDirectoryId(right));
    }

    private static void expectStatus(Response response, int expectedStatus, String operation) throws FileOperationException {
        if (response.code() != expectedStatus) throw statusException(response, operation);
    }

    private static void expectSuccessful(Response response, String operation) throws FileOperationException {
        if (!response.isSuccessful()) throw statusException(response, operation);
    }

    private static FileOperationException statusException(Response response, String operation) {
        FileOperationException.Code code;
        if (response.code() == 404) code = FileOperationException.Code.NOT_FOUND;
        else if (response.code() == 401 || response.code() == 403) code = FileOperationException.Code.PERMISSION_DENIED;
        else if (response.code() == 409 || response.code() == 412) code = FileOperationException.Code.CONFLICT;
        else code = FileOperationException.Code.NETWORK;
        return new FileOperationException(code, operation + " failed with HTTP " + response.code());
    }
}
