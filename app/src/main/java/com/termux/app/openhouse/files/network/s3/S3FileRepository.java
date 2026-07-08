package com.termux.app.openhouse.files.network.s3;

import com.termux.app.openhouse.files.model.FileItem;
import com.termux.app.openhouse.files.model.FileOperation;
import com.termux.app.openhouse.files.model.FileOperationException;
import com.termux.app.openhouse.files.model.FileRepository;
import com.termux.app.openhouse.files.model.FileSpace;
import com.termux.app.openhouse.files.model.FileSpaceType;
import com.termux.app.openhouse.files.model.UnsupportedFileOperationException;
import com.termux.app.openhouse.files.network.HttpResponseInputStream;
import com.termux.app.openhouse.files.network.InputStreamRequestBody;
import com.termux.app.openhouse.files.network.NetworkRepositoryUtils;
import com.termux.app.openhouse.files.storage.FileRepositoryUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class S3FileRepository implements FileRepository {

    private final OkHttpClient client;
    private final S3ObjectStoreConfig config;
    private final FileSpace space;

    public S3FileRepository(OkHttpClient client, S3ObjectStoreConfig config) {
        if (client == null) throw new IllegalArgumentException("client == null");
        if (config == null) throw new IllegalArgumentException("config == null");
        this.client = client;
        this.config = config;
        this.space = FileSpace.builder(config.getId(), FileSpaceType.S3, config.getDisplayName())
            .rootLabel(config.getBucket())
            .locationSummary(config.getEndpoint().toString())
            .supportedOperations(EnumSet.of(
                FileOperation.LIST,
                FileOperation.OPEN_INPUT,
                FileOperation.OPEN_OUTPUT,
                FileOperation.UPLOAD,
                FileOperation.DOWNLOAD,
                FileOperation.DELETE,
                FileOperation.CREATE_DIRECTORY))
            .metadata("endpoint", config.getEndpoint().toString())
            .metadata("bucket", config.getBucket())
            .metadata("region", config.getRegion())
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
            .nativeLocation(bucketUrl().toString())
            .build();
    }

    @Override
    public List<FileItem> list(String parentId) throws FileOperationException {
        String prefix = NetworkRepositoryUtils.normalizeDirectoryId(parentId);
        List<FileItem> items = new ArrayList<>();
        String continuationToken = "";
        do {
            HttpUrl.Builder urlBuilder = bucketUrl().newBuilder()
                .addQueryParameter("list-type", "2")
                .addQueryParameter("delimiter", "/");
            if (!prefix.isEmpty()) urlBuilder.addQueryParameter("prefix", prefix);
            if (!continuationToken.isEmpty()) urlBuilder.addQueryParameter("continuation-token", continuationToken);
            HttpUrl url = urlBuilder.build();
            Request request = sign(new Request.Builder().url(url).get().build(), S3V4Signer.EMPTY_SHA256);
            try (Response response = client.newCall(request).execute()) {
                expectSuccessful(response, "ListObjectsV2 " + url);
                ResponseBody body = response.body();
                if (body == null) {
                    throw new FileOperationException(FileOperationException.Code.NETWORK, "S3 list returned no body");
                }
                S3ListBucketResult result = S3ListBucketXmlParser.parse(body.string());
                for (S3ObjectEntry entry : result.getEntries()) {
                    if (entry.getKey().isEmpty() || entry.getKey().equals(prefix)) continue;
                    items.add(toItem(entry, prefix));
                }
                continuationToken = result.isTruncated() ? result.getNextContinuationToken() : "";
                if (continuationToken.isEmpty()) break;
            } catch (FileOperationException e) {
                throw e;
            } catch (IOException e) {
                throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot list S3 prefix: " + prefix, e);
            }
        } while (true);
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
        String key = NetworkRepositoryUtils.requireNonRootObjectId(fileId, "Open S3 object");
        HttpUrl url = objectUrl(key);
        Request request = sign(new Request.Builder().url(url).get().build(), S3V4Signer.EMPTY_SHA256);
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
                throw new FileOperationException(FileOperationException.Code.NETWORK, "S3 GET returned no body");
            }
            return new HttpResponseInputStream(response, body.byteStream());
        } catch (FileOperationException e) {
            throw e;
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot open S3 object: " + key, e);
        }
    }

    @Override
    public OutputStream openOutputStream(final String fileId, final String mimeType) {
        return new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                try {
                    putBytes(NetworkRepositoryUtils.normalizeObjectId(fileId), toByteArray(), mimeType);
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
        String key = NetworkRepositoryUtils.childId(parentId, safeName, false);
        HttpUrl url = objectUrl(key);
        RequestBody body = new InputStreamRequestBody(NetworkRepositoryUtils.mediaType(mimeType), input, size);
        Request unsigned = new Request.Builder()
            .url(url)
            .put(body)
            .header("Content-Type", mimeType == null || mimeType.trim().isEmpty() ? "application/octet-stream" : mimeType)
            .build();
        Request request = sign(unsigned, S3V4Signer.UNSIGNED_PAYLOAD);
        try (Response response = client.newCall(request).execute()) {
            expectSuccessful(response, "PUT " + url);
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot upload S3 object: " + key, e);
        }
        return itemForKnownKey(key, false, mimeType, size);
    }

    @Override
    public void download(String fileId, OutputStream output) throws FileOperationException {
        try (InputStream input = openInputStream(fileId)) {
            FileRepositoryUtils.copy(input, output);
        } catch (FileOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot download S3 object: " + fileId, e);
        }
    }

    @Override
    public FileItem createDirectory(String parentId, String displayName) throws FileOperationException {
        String safeName = FileRepositoryUtils.requireDisplayName(displayName);
        String key = NetworkRepositoryUtils.childId(parentId, safeName, true);
        putBytes(key, new byte[0], "application/x-directory");
        return itemForKnownKey(key, true, "vnd.android.document/directory", -1);
    }

    @Override
    public void delete(String fileId) throws FileOperationException {
        String key = NetworkRepositoryUtils.requireNonRootObjectId(fileId, "Delete S3 bucket root");
        HttpUrl url = objectUrl(key);
        Request request = sign(new Request.Builder().url(url).delete().build(), S3V4Signer.EMPTY_SHA256);
        try (Response response = client.newCall(request).execute()) {
            expectSuccessful(response, "DELETE " + url);
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot delete S3 object: " + key, e);
        }
    }

    @Override
    public FileItem rename(String fileId, String newDisplayName) throws FileOperationException {
        throw new UnsupportedFileOperationException(FileOperation.RENAME, space.getId());
    }

    @Override
    public boolean supports(FileOperation operation) {
        return space.supports(operation);
    }

    private void putBytes(String key, byte[] bytes, String mimeType) throws FileOperationException {
        String safeKey = NetworkRepositoryUtils.requireNonRootObjectId(key, "Write S3 object");
        HttpUrl url = objectUrl(safeKey);
        String hash = S3V4Signer.sha256Hex(bytes);
        Request request = sign(new Request.Builder()
            .url(url)
            .put(RequestBody.create(bytes, NetworkRepositoryUtils.mediaType(mimeType)))
            .header("Content-Type", mimeType == null || mimeType.trim().isEmpty() ? "application/octet-stream" : mimeType)
            .build(), hash);
        try (Response response = client.newCall(request).execute()) {
            expectSuccessful(response, "PUT " + url);
        } catch (IOException e) {
            throw new FileOperationException(FileOperationException.Code.NETWORK, "Cannot write S3 object: " + safeKey, e);
        }
    }

    private Request sign(Request request, String payloadSha256) throws FileOperationException {
        return S3V4Signer.sign(request, config, payloadSha256, new Date());
    }

    private HttpUrl bucketUrl() {
        HttpUrl endpoint = config.getEndpoint();
        HttpUrl.Builder builder = endpoint.newBuilder();
        if (config.isPathStyleAccess()) {
            builder.addPathSegment(config.getBucket());
        } else {
            builder.host(config.getBucket() + "." + endpoint.host());
        }
        return builder.build();
    }

    private HttpUrl objectUrl(String key) throws FileOperationException {
        HttpUrl.Builder builder = bucketUrl().newBuilder();
        String normalized = NetworkRepositoryUtils.normalizeObjectId(key);
        if (!normalized.isEmpty()) {
            String[] parts = normalized.split("/", -1);
            for (String part : parts) builder.addPathSegment(part);
        }
        return builder.build();
    }

    private FileItem toItem(S3ObjectEntry entry, String parentId) throws FileOperationException {
        return FileItem.builder(space.getId(), entry.getKey(), NetworkRepositoryUtils.displayNameForId(entry.getKey()), entry.isDirectory())
            .parentId(parentId)
            .size(entry.isDirectory() ? -1 : entry.getSize())
            .lastModifiedMillis(entry.getLastModifiedMillis())
            .mimeType(FileRepositoryUtils.guessMimeType(entry.getKey(), entry.isDirectory()))
            .nativeLocation(objectUrl(entry.getKey()).toString())
            .build();
    }

    private FileItem itemForKnownKey(String key, boolean directory, String mimeType, long size) throws FileOperationException {
        return FileItem.builder(space.getId(), key, NetworkRepositoryUtils.displayNameForId(key), directory)
            .parentId(NetworkRepositoryUtils.parentIdForId(key))
            .size(directory ? -1 : size)
            .lastModifiedMillis(System.currentTimeMillis())
            .mimeType(mimeType == null ? FileRepositoryUtils.guessMimeType(key, directory) : mimeType)
            .nativeLocation(objectUrl(key).toString())
            .build();
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
