package ca.uqam.patchpilot.demo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * XML report ingestion endpoint.
 *
 * VULNERABILITY 6 — XML External Entity Injection (SonarQube rule: java:S2755)
 *
 * The XML parser is created with its default configuration, which allows
 * processing of external entity references (XXE). An attacker can submit an XML
 * payload that references a local file or an internal network resource via an
 * external entity, causing the server to read and return that content.
 *
 * Example exploit payload:
 *   &lt;?xml version="1.0"?&gt;
 *   &lt;!DOCTYPE report [&lt;!ENTITY xxe SYSTEM "file:///etc/passwd"&gt;]&gt;
 *   &lt;report&gt;&amp;xxe;&lt;/report&gt;
 *
 * Secure fix: explicitly disable external entity processing on the factory:
 *   factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
 *   factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
 *   factory.setXIncludeAware(false);
 *   factory.setExpandEntityReferences(false);
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestBody String xmlPayload) throws Exception {
        // VULNERABILITY: DocumentBuilderFactory created without disabling external entities
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(
                new ByteArrayInputStream(xmlPayload.getBytes(StandardCharsets.UTF_8)));

        String rootElement = doc.getDocumentElement().getTagName();
        return ResponseEntity.ok("Parsed report with root element: " + rootElement);
    }
}
