package com.termux.app.openhouse.files.network.webdav;

import com.termux.app.openhouse.files.model.FileOperationException;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class WebDavFileRepositoryTest {

    @Test
    public void deleteRootIsRejectedBeforeNetwork() throws Exception {
        WebDavFileRepository repository = repository();

        try {
            repository.delete("");
            Assert.fail("Expected root delete rejection");
        } catch (FileOperationException e) {
            Assert.assertEquals(FileOperationException.Code.PERMISSION_DENIED, e.getCode());
        }

        try {
            repository.delete("/");
            Assert.fail("Expected root delete rejection");
        } catch (FileOperationException e) {
            Assert.assertEquals(FileOperationException.Code.PERMISSION_DENIED, e.getCode());
        }
    }

    @Test
    public void traversalIdIsRejectedBeforeNetwork() throws Exception {
        WebDavFileRepository repository = repository();

        try {
            repository.openInputStream("../secret.txt");
            Assert.fail("Expected traversal rejection");
        } catch (FileOperationException e) {
            Assert.assertEquals(FileOperationException.Code.INVALID_PATH, e.getCode());
        }
    }

    private static WebDavFileRepository repository() {
        return new WebDavFileRepository(failingClient(),
            new WebDavConfig("dav", "DAV", "https://example.com/dav/root/", "", ""));
    }

    private static OkHttpClient failingClient() {
        return new OkHttpClient.Builder()
            .addInterceptor(new Interceptor() {
                @Override
                public Response intercept(Chain chain) throws IOException {
                    throw new AssertionError("Network should not be called");
                }
            })
            .build();
    }
}
