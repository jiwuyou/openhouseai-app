package com.termux.app.openhouse.files.network.s3;

import com.termux.app.openhouse.files.model.FileOperationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilderFactory;

public final class S3ListBucketXmlParser {

    private S3ListBucketXmlParser() {
    }

    public static S3ListBucketResult parse(String xml) throws FileOperationException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            List<S3ObjectEntry> entries = new ArrayList<>();
            for (Element contents : directChildren(root, "Contents")) {
                String key = textOfFirst(contents, "Key");
                long size = parseLong(textOfFirst(contents, "Size"));
                boolean directory = key.endsWith("/") && size == 0;
                entries.add(new S3ObjectEntry(
                    key,
                    directory,
                    size,
                    parseIsoDate(textOfFirst(contents, "LastModified")),
                    textOfFirst(contents, "ETag")));
            }
            for (Element commonPrefix : directChildren(root, "CommonPrefixes")) {
                String prefix = textOfFirst(commonPrefix, "Prefix");
                if (!prefix.isEmpty()) entries.add(new S3ObjectEntry(prefix, true, -1, -1, ""));
            }
            boolean truncated = "true".equalsIgnoreCase(textOfFirst(root, "IsTruncated"));
            String nextToken = textOfFirst(root, "NextContinuationToken");
            return new S3ListBucketResult(entries, truncated, nextToken);
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.PARSE, "Cannot parse S3 ListObjectsV2 XML", e);
        }
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && localName.equals(localName(child))) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private static String textOfFirst(Element element, String localName) {
        for (Element child : directChildren(element, localName)) {
            return child.getTextContent().trim();
        }
        return "";
    }

    private static String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isEmpty() ? -1 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseIsoDate(String value) {
        if (value == null || value.isEmpty()) return -1;
        String[] patterns = new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = format.parse(value);
                if (date != null) return date.getTime();
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
        }
    }
}
