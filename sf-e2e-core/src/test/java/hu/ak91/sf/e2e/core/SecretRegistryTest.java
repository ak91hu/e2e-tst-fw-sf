package hu.ak91.sf.e2e.core;

import org.junit.jupiter.api.Test;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecretRegistryTest {
    @Test void transformedValuesAreRedactedWithoutChangingOrdinaryText() {
        var secrets=new SecretRegistry();String value="Private \"Key\" <&> / alpha";secrets.register(value);
        String json=Json.string(value);String html=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
        String encoded=URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");
        for(String form:List.of(value.toUpperCase(Locale.ROOT),json.substring(1,json.length()-1),html,encoded)) {
            assertTrue(secrets.containsSecret(form),"Transformed values must be detected before persistence");
            assertEquals("prefix [REDACTED] suffix",secrets.redact("prefix "+form+" suffix"));
        }
        assertEquals("Public heading and value",secrets.redact("Public heading and value"));
    }
    @Test void collapsedWhitespaceAndTruncatedPrefixesCannotLeakThroughObservationLimits() {
        var secrets=new SecretRegistry();secrets.register("PRIVATE\n  LONG\tCREDENTIAL");
        assertEquals("[REDACTED]",secrets.redact("PRIVATE LONG CREDENTIAL"));
        assertEquals("label [REDACTED]",secrets.redact("label private long cred"));
        assertTrue(secrets.containsSecret("private long cred"));
        assertEquals("PRIVATE label",secrets.redact("PRIVATE label"),"A short partial match does not authorize whole-word removal");
    }
    @Test void redactsNestedKeysAndValuesAndRetainsExistingBase64Protection() {
        var secrets=new SecretRegistry();String value="Sentinel_private_123";secrets.register(value);
        var node=secrets.redactTree(Json.MAPPER.valueToTree(Map.of(value.toUpperCase(Locale.ROOT),List.of(value.toLowerCase(Locale.ROOT)))));
        assertEquals("{\"[REDACTED]\":[\"[REDACTED]\"]}",Json.string(node));
        assertEquals("[REDACTED]",secrets.redact(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
    }
    @Test void shortExistingSecretsRemainSupportedWithoutBroadPrefixMasking() {
        var secrets=new SecretRegistry();secrets.register("abc");
        assertEquals("[REDACTED]",secrets.redact("abc"));assertEquals("ab",secrets.redact("ab"));
    }
}
