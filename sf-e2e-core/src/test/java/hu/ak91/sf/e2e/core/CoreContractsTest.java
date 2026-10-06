package hu.ak91.sf.e2e.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CoreContractsTest {
    @TempDir Path root;
    @Test void rejectsMalformedMoneyInsteadOfGuessing() {
        assertEquals(new BigDecimal("1000000.99"),UiMoneyParser.money("$1,000,000.99"));
        assertEquals(new BigDecimal("0.00"),UiMoneyParser.money("$0.00"));
        for(String text:List.of("$12,34.56","","EUR 1.00","1.234,56","1.001","NaN")) assertThrows(Failure.class,() -> UiMoneyParser.money(text));
    }
    @Test void rejectsInvalidCalendarDatesAndPreservesLeapDay() {
        assertEquals(LocalDate.of(2024,2,29),UiMoneyParser.date("2/29/2024"));
        assertThrows(Failure.class,() -> UiMoneyParser.date("2/29/2025"));
        assertThrows(Failure.class,() -> UiMoneyParser.date("13/1/2026"));
    }
    @Test void blankAndZeroHaveDifferentStates() {
        assertNotEquals(FieldValue.blank(),FieldValue.present(BigDecimal.ZERO));
        assertThrows(Failure.class,() -> new FieldValue<>(FieldValue.State.BLANK,"0"));
    }
    @Test void redactsLiteralEncodedAndNestedAuthSecrets() {
        var secrets=new SecretRegistry();String sentinel="token +/& sentinel";secrets.register(sentinel);
        String output=secrets.redact(sentinel+" "+java.net.URLEncoder.encode(sentinel,java.nio.charset.StandardCharsets.UTF_8)+" https://org.salesforce.com/secur/frontdoor.jsp?otp=private-value&cshc=checksum&startURL=%2Fsecret&x=public");
        assertFalse(output.contains(sentinel));assertFalse(output.contains("private-value"));assertFalse(output.contains("checksum"));assertTrue(output.contains("x=public"));
        assertFalse(secrets.redact("Authorization: Bearer abc123").contains("abc123"));
    }
    @Test void strictYamlRejectsTyposAndWrongWorkerCount() throws Exception {
        Path file=root.resolve("config.yaml");Files.writeString(file,"execution:\n  worker: 2\n");
        assertThrows(Failure.class,() -> FrameworkConfig.load(file,Map.of()));
        Files.writeString(file,"execution:\n  workers: 9\n");
        assertThrows(Failure.class,() -> FrameworkConfig.load(file,Map.of()));
    }
    @Test void scalarOverridesHavePriorityAndSecretsAreReferences() {
        var config=FrameworkConfig.load(null,Map.of("execution.actionTimeoutSeconds","12","profile","regression"));
        assertEquals(12,config.execution().actionTimeoutSeconds());assertEquals("regression",config.profile());
        assertEquals("E2E_MODEL_API_KEY",config.agent().apiKeyRef());
        assertThrows(Failure.class,() -> FrameworkConfig.load(null,Map.of("cache.mode","read-write","profile","full-parity")));
    }
    @Test void supportsProductionSandboxScratchAndCustomDomainsWhileRejectingMalformedOrigins() {
        for(String type:List.of("production","sandbox","scratch","developer-edition","government","auto"))
            assertDoesNotThrow(() -> FrameworkConfig.load(null,Map.of("org.environmentType",type,"org.baseUrl","https://configured.my.salesforce.com")));
        var production=FrameworkConfig.load(null,Map.of("org.environmentType","production","org.baseUrl","https://configured.my.salesforce.com"));
        assertTrue(production.origins().contains("https://configured.lightning.force.com"));
        assertDoesNotThrow(() -> FrameworkConfig.load(null,Map.of("org.baseUrl","https://portal.company.example","auth.startRoute","/s/")));
        assertDoesNotThrow(() -> FrameworkConfig.validateOrigin("https://org.develop.lightning.force.com"));
        for(String origin:List.of("http://org.salesforce.com","https://org.salesforce.com/","https://user@org.salesforce.com","https://org.salesforce.com?sid=secret"))
            assertThrows(Failure.class,() -> FrameworkConfig.validateOrigin(origin));
    }
    @Test void localizedConfigurationDoesNotRequireBusinessProfilesStagesOrCurrencyDefaults() throws Exception {
        Path file=root.resolve("localized.yaml");
        Files.writeString(file,"org:\n  environmentType: production\n  baseUrl: https://portal.company.example\n  allowedOrigins: [https://instance.salesforce.mil]\nui:\n  language: hu\n  locale: hu-HU\n  currency: HUF\n  personaTimeZone: Europe/Budapest\nauth:\n  audience: https://login.salesforce.com\n  tokenOrigin: https://instance.salesforce.mil\n  startRoute: /home/home.jsp\npersonas:\n  reviewer:\n    usernameRef: REVIEWER_USERNAME\n    storageStateRef: REVIEWER_STATE\n");
        var config=FrameworkConfig.load(file,Map.of());
        assertEquals(Set.of("reviewer"),config.personas().keySet());assertEquals("HUF",config.ui().currency());
        assertEquals("REVIEWER_USERNAME",config.persona(Persona.valueOf("reviewer")).usernameRef());
        assertEquals("https://instance.salesforce.mil",config.tokenOrigin());assertEquals("https://login.salesforce.com",config.jwtAudience());
        assertDoesNotThrow(() -> FrameworkConfig.validateRoute("/home/home.jsp"));assertDoesNotThrow(() -> FrameworkConfig.validateRoute("/s/custom-page"));
        for(String route:List.of("//foreign.example/page","https://foreign.example/page","/secur/frontdoor.jsp?sid=secret","/login","/s/login","/services/oauth2/token","/page?sid=secret"))
            assertThrows(Failure.class,() -> FrameworkConfig.validateRoute(route));
        assertEquals("\"reviewer\"",Json.string(Persona.valueOf("reviewer")));assertEquals(Persona.valueOf("reviewer"),Json.MAPPER.readValue("\"reviewer\"",Persona.class));
        assertFalse(FrameworkConfig.defaults().report().root().startsWith("target"));
    }
    @Test void optionalValueParsersAcceptExplicitCurrenciesAndCalendarFormats() {
        for(String[] pair:List.of(new String[]{"hu-HU","HUF"},new String[]{"de-DE","EUR"},new String[]{"ja-JP","JPY"})) {
            var locale=Locale.forLanguageTag(pair[0]);var currency=Currency.getInstance(pair[1]);var number=java.text.NumberFormat.getCurrencyInstance(locale);number.setCurrency(currency);
            number.setMinimumFractionDigits(currency.getDefaultFractionDigits());number.setMaximumFractionDigits(currency.getDefaultFractionDigits());
            var expected=new BigDecimal("1234");assertEquals(0,expected.compareTo(UiMoneyParser.money(number.format(expected),locale,currency)));
        }
        assertEquals(LocalDate.of(2026,10,5),UiMoneyParser.date("2026.10.05",java.time.format.DateTimeFormatter.ofPattern("uuuu.MM.dd")));
        assertThrows(Failure.class,() -> UiMoneyParser.money("€1,23",Locale.GERMANY,Currency.getInstance("EUR")));
    }
    @Test void locatorNamespacesSeparateOrgIdentityAndLocalizedUiProfiles() {
        var base=FrameworkConfig.load(null,Map.of("org.baseUrl","https://one.my.salesforce.com"));
        var otherOrg=FrameworkConfig.load(null,Map.of("org.baseUrl","https://two.my.salesforce.com"));
        var otherLocale=FrameworkConfig.load(null,Map.of("org.baseUrl","https://one.my.salesforce.com","ui.locale","hu-HU","ui.currency","HUF"));
        var first=hu.ak91.sf.e2e.core.locator.LocatorStore.namespace(base,Persona.DEFAULT);
        assertNotEquals(first,hu.ak91.sf.e2e.core.locator.LocatorStore.namespace(otherOrg,Persona.DEFAULT));
        assertNotEquals(first,hu.ak91.sf.e2e.core.locator.LocatorStore.namespace(otherLocale,Persona.DEFAULT));
    }
    @Test void deadlineCannotBeExtendedAndExpiresBeforeMutation() {
        Deadline deadline=Deadline.after(Duration.ofNanos(1));
        assertThrows(Failure.class,deadline::check);assertThrows(Failure.class,() -> deadline.child(Duration.ofMinutes(1)).check());
    }
    @Test void budgetCountsCallsActionsAndUnknownUsage() {
        Budget budget=new Budget(1,1,10);budget.call();budget.action();budget.usage(null);assertNull(budget.tokens());
        assertThrows(Failure.class,budget::call);assertThrows(Failure.class,budget::action);
        Budget tokens=new Budget(1,2,5);assertThrows(Failure.class,() -> tokens.usage(6L));
    }
    @Test void atomicJsonRoundTripAndPathContainment() {
        Path file=root.resolve("manifest.json");Json.write(file,Map.of("value","Unicode őű"));assertEquals("Unicode őű",Json.read(file,Map.class).get("value"));
        assertThrows(Failure.class,() -> SafePaths.inside(root,"../escape.png"));
        assertThrows(Failure.class,() -> SafePaths.segment("../attempt"));
    }
    @Test void exactMoneyProofDoesNotAcceptRoundingOrZeroForBlank() {
        var proof=UiVerification.compare("attempt","006000000000001",Map.of("Amount",new BigDecimal("0.01")),Map.of("Amount",new BigDecimal("0.010")),"route","evidence");
        assertEquals("006000000000001",proof.recordId());
        assertThrows(Failure.class,() -> UiVerification.compare("attempt","id",Map.of("Amount",BigDecimal.ZERO),Map.of("Amount",""),"route","evidence"));
    }
    @Test void extractionSchemaChecksNestedValuesAndFailsClosedOnUnsupportedKeywords() {
        var schema=Json.MAPPER.valueToTree(Map.of("type","object","properties",Map.of("status",Map.of("type","string","enum",List.of("Draft","Accepted"))),
            "required",List.of("status"),"additionalProperties",false));
        assertDoesNotThrow(() -> JsonSchema.validate(Json.MAPPER.valueToTree(Map.of("status","Accepted")),schema));
        assertThrows(Failure.class,() -> JsonSchema.validate(Json.MAPPER.valueToTree(Map.of("status","Unknown")),schema));
        assertThrows(Failure.class,() -> JsonSchema.validate(Json.MAPPER.valueToTree(Map.of("status","Draft","hidden","value")),schema));
        assertThrows(Failure.class,() -> JsonSchema.validate(Json.MAPPER.valueToTree("value"),Json.MAPPER.valueToTree(Map.of("type","string","unsupported",true))));
    }
}
