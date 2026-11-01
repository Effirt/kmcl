package cd.cc.effirt.kinastminecraftlauncher.util;

import module java.base;
import module java.xml;

import cd.cc.effirt.util.Log;

public class XmlUtil {

    /** 读取 maven-metadata.xml 里的全部 <version> 文本，解析失败返回空 List。 */
    public static List<String> readVersionTags(String xml) {
        List<String> out = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var doc = dbf.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            var nodes = doc.getElementsByTagName("version");
            for (int i = 0; i < nodes.getLength(); i++) {
                String v = nodes.item(i).getTextContent().trim();
                if (!v.isEmpty()) out.add(v);
            }
        } catch (Exception e) {
            Log.warn("Failed to parse maven-metadata.xml: {}", e.getMessage());
        }
        return out;
    }
}