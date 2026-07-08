package com.termux.app.openhouse.files.network.s3;

import com.termux.app.openhouse.files.model.FileOperationException;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class S3FileRepositoryTest {

    @Test
    public void deleteRootIsRejectedBeforeNetwork() throws Exception {
        S3FileRepository repository = repository();

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
    public void traversalKeyIsRejectedBeforeNetwork() throws Exception {
        S3FileRepository repository = repository();

        try {
            repository.openInputStream("folder/../secret.txt");
            Assert.fail("Expected traversal rejection");
        } catch (FileOperationException e) {
            Assert.assertEquals(FileOperationException.Code.INVALID_PATH, e.getCode());
        }
    }

    private static S3FileRepository repository() {
        return new S3FileRepository(failingClient(),
            new S3ObjectStoreConfig(
                "s3",
                "S3",
                "https://s3.example.com",
                "us-east-1",
                "bucket",
                "access",
                "secret",
                "",
                true));
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
