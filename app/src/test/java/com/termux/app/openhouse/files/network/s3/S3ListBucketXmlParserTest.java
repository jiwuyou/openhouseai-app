package com.termux.app.openhouse.files.network.s3;

import org.junit.Assert;
import org.junit.Test;

public class S3ListBucketXmlParserTest {

    @Test
    public void parsesObjectsPrefixesAndContinuationToken() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
            + "<IsTruncated>true</IsTruncated>"
            + "<Contents><Key>notes/report.md</Key><LastModified>2026-07-08T12:00:00.000Z</LastModified>"
            + "<ETag>&quot;abc&quot;</ETag><Size>12</Size></Contents>"
            + "<CommonPrefixes><Prefix>notes/archive/</Prefix></CommonPrefixes>"
            + "<NextContinuationToken>next-token</NextContinuationToken>"
            + "</ListBucketResult>";

        S3ListBucketResult result = S3ListBucketXmlParser.parse(xml);

        Assert.assertTrue(result.isTruncated());
        Assert.assertEquals("next-token", result.getNextContinuationToken());
        Assert.assertEquals(2, result.getEntries().size());
        Assert.assertEquals("notes/report.md", result.getEntries().get(0).getKey());
        Assert.assertFalse(result.getEntries().get(0).isDirectory());
        Assert.assertEquals(12, result.getEntries().get(0).getSize());
        Assert.assertEquals("notes/archive/", result.getEntries().get(1).getKey());
        Assert.assertTrue(result.getEntries().get(1).isDirectory());
    }
}
