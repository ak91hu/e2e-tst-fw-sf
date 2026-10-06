package hu.ak91.sf.e2e.salesforce.auth;

import hu.ak91.sf.e2e.core.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class FrontdoorSecretsTest {
    @Test void bridgeCredentialsAreMaskedWhileTheApplicationRouteRemainsUsable() {
        var secrets=new SecretRegistry();
        String origin="https://configured.example",start="lightning/page/home";
        String url=origin+"/secur/frontdoor.jsp?otp=PRIVATE_OTP_SENTINEL&cshc=PRIVATE_CSHC_SENTINEL&startURL=lightning%2Fpage%2Fhome";
        JwtAuthenticator.validateFrontdoor(url,origin,start);
        JwtAuthenticator.registerFrontdoorSecrets(url,secrets);
        assertEquals("[REDACTED]",secrets.redact("PRIVATE_OTP_SENTINEL"));
        assertEquals("[REDACTED]",secrets.redact("PRIVATE_CSHC_SENTINEL"));
        assertEquals("[REDACTED]",secrets.redact(url));
        assertEquals(origin+"/"+start,secrets.redact(origin+"/"+start));
        assertDoesNotThrow(() -> URI.create(secrets.redact(origin+"/"+start)));
    }
}
