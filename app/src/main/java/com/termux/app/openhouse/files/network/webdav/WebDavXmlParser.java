package com.termux.app.openhouse.files.network.webdav;

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

public final class WebDavXmlParser {

    private WebDavXmlParser() {
    }

    public static List<WebDavResource> parse(String xml) throws FileOperationException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList responses = document.getElementsByTagNameNS("*", "response");
            List<WebDavResource> resources = new ArrayList<>();
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                String href = textOfFirst(response, "href");
                Element prop = findSuccessfulProp(response);
                if (prop == null) prop = firstDescendant(response, "prop");
                String displayName = prop == null ? "" : textOfFirst(prop, "displayname");
                boolean directory = prop != null && firstDescendant(firstDescendant(prop, "resourcetype"), "collection") != null;
                long size = prop == null ? -1 : parseLong(textOfFirst(prop, "getcontentlength"));
                long lastModified = prop == null ? -1 : parseHttpDate(textOfFirst(prop, "getlastmodified"));
                String contentType = prop == null ? "" : textOfFirst(prop, "getcontenttype");
                resources.add(new WebDavResource(href, displayName, directory, size, lastModified, contentType));
            }
            return resources;
        } catch (Exception e) {
            throw new FileOperationException(FileOperationException.Code.PARSE, "Cannot parse WebDAV XML", e);
        }
    }

    private static Element findSuccessfulProp(Element response) {
        NodeList propstats = response.getElementsByTagNameNS("*", "propstat");
        for (int i = 0; i < propstats.getLength(); i++) {
            Element propstat = (Element) propstats.item(i);
            String status = textOfFirst(propstat, "status");
            if (status.isEmpty() || status.contains(" 2")) {
                Element prop = firstDescendant(propstat, "prop");
                if (prop != null) return prop;
            }
        }
        return null;
    }

    private static String textOfFirst(Element element, String localName) {
        Element child = firstDescendant(element, localName);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static Element firstDescendant(Element element, String localName) {
        if (element == null) return null;
        NodeList nodes = element.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) return null;
        Node node = nodes.item(0);
        return node instanceof Element ? (Element) node : null;
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isEmpty() ? -1 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseHttpDate(String value) {
        if (value == null || value.isEmpty()) return -1;
        try {
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date date = format.parse(value);
            return date == null ? -1 : date.getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
        }
    }
}
