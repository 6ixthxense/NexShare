package org.xmlpull.v1;

import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/** Just enough of the XmlPull API (as used by WebDav) on top of the JDK's StAX parser. */
public class XmlPullParser {
    public static final int START_DOCUMENT = 0, END_DOCUMENT = 1, START_TAG = 2, END_TAG = 3, TEXT = 4;
    public static final String FEATURE_PROCESS_NAMESPACES = "http://xmlpull.org/v1/doc/features.html#process-namespaces";
    private XMLStreamReader r;
    private int event = START_DOCUMENT, depth;
    private final Deque<String[]> stack = new ArrayDeque<>();

    public void setFeature(String name, boolean on) { }
    public void setInput(Reader in) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        r = f.createXMLStreamReader(in);
    }
    public int getEventType() { return event; }
    public int next() throws Exception {
        if (event == END_TAG) depth--;
        while (r.hasNext()) {
            int e = r.next();
            if (e == XMLStreamConstants.START_ELEMENT) { depth++; event = START_TAG; return event; }
            if (e == XMLStreamConstants.END_ELEMENT) { event = END_TAG; return event; }
            if (e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) { event = TEXT; return event; }
        }
        event = END_DOCUMENT;
        return event;
    }
    public String getName() { return r.getLocalName(); }
    public String getNamespace() { String ns = r.getNamespaceURI(); return ns == null ? "" : ns; }
    public int getDepth() { return depth; }
    public String nextText() throws Exception {
        StringBuilder sb = new StringBuilder();
        while (next() == TEXT) sb.append(r.getText());
        return sb.toString();
    }
}
