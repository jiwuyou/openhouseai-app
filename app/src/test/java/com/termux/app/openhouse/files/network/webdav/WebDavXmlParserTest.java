package com.termux.app.openhouse.files.network.webdav;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class WebDavXmlParserTest {

    @Test
    public void parsesFilesAndDirectoriesFromMultistatus() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<D:multistatus xmlns:D=\"DAV:\">"
            + "<D:response>"
            + "<D:href>/dav/root/</D:href>"
            + "<D:propstat><D:prop><D:displayname>root</D:displayname>"
            + "<D:resourcetype><D:collection/></D:resourcetype>"
            + "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
            + "</D:response>"
            + "<D:response>"
            + "<D:href>/dav/root/report.md</D:href>"
            + "<D:propstat><D:prop><D:displayname>report.md</D:displayname>"
            + "<D:getcontentlength>42</D:getcontentlength>"
            + "<D:getcontenttype>text/markdown</D:getcontenttype>"
            + "<D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified>"
            + "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
            + "</D:response>"
            + "</D:multistatus>";

        List<WebDavResource> resources = WebDavXmlParser.parse(xml);

        Assert.assertEquals(2, resources.size());
        Assert.assertTrue(resources.get(0).isDirectory());
        Assert.assertEquals("report.md", resources.get(1).getDisplayName());
        Assert.assertFalse(resources.get(1).isDirectory());
        Assert.assertEquals(42, resources.get(1).getSize());
        Assert.assertEquals("text/markdown", resources.get(1).getContentType());
        Assert.assertTrue(resources.get(1).getLastModifiedMillis() > 0);
    }
}
