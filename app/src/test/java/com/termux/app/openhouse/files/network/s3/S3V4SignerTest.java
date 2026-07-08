package com.termux.app.openhouse.files.network.s3;

import org.junit.Assert;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.Request;

public class S3V4SignerTest {

    @Test
    public void canonicalRequestMatchesAwsReferenceShape() throws Exception {
        Request request = new Request.Builder()
            .url("https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08")
            .get()
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .header("Host", "iam.amazonaws.com")
            .header("X-Amz-Date", "20150830T123600Z")
            .build();

        String canonicalRequest = S3V4Signer.canonicalRequest(request, S3V4Signer.EMPTY_SHA256);

        Assert.assertEquals("GET\n"
                + "/\n"
                + "Action=ListUsers&Version=2010-05-08\n"
                + "content-type:application/x-www-form-urlencoded; charset=utf-8\n"
                + "host:iam.amazonaws.com\n"
                + "x-amz-date:20150830T123600Z\n"
                + "\n"
                + "content-type;host;x-amz-date\n"
                + S3V4Signer.EMPTY_SHA256,
            canonicalRequest);
        Assert.assertEquals("f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59",
            S3V4Signer.sha256Hex(canonicalRequest));
    }

    @Test
    public void signAddsRequiredS3Headers() throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        S3ObjectStoreConfig config = new S3ObjectStoreConfig(
            "s3",
            "S3",
            "https://s3.example.com",
            "us-east-1",
            "bucket",
            "AKIDEXAMPLE",
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "",
            true);
        Request unsigned = new Request.Builder()
            .url("https://s3.example.com/bucket/file.txt")
            .get()
            .build();

        Request signed = S3V4Signer.sign(unsigned, config, S3V4Signer.EMPTY_SHA256,
            format.parse("20130524T000000Z"));

        Assert.assertEquals("s3.example.com", signed.header("Host"));
        Assert.assertEquals("20130524T000000Z", signed.header("x-amz-date"));
        Assert.assertEquals(S3V4Signer.EMPTY_SHA256, signed.header("x-amz-content-sha256"));
        Assert.assertTrue(signed.header("Authorization").startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20130524/us-east-1/s3/aws4_request"));
        Assert.assertTrue(signed.header("Authorization").contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"));
    }
}
