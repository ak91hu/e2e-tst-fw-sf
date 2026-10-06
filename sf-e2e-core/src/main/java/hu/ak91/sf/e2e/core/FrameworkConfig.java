package hu.ak91.sf.e2e.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.*;
import java.util.*;
import java.time.*;

public record FrameworkConfig(int schemaVersion, String projectId, String profile, Org org, Ui ui,
    Auth auth, Map<String, PersonaConfig> personas, Execution execution, Agent agent, Cache cache,
    Retention retention, Evidence evidence, Report report, Locators locators, Quality quality) {
    public FrameworkConfig(int schemaVersion,String projectId,String profile,Org org,Ui ui,Auth auth,
        Map<String,PersonaConfig> personas,Execution execution,Agent agent,Cache cache,Retention retention,Evidence evidence,Report report,Locators locators) {
        this(schemaVersion,projectId,profile,org,ui,auth,personas,execution,agent,cache,retention,evidence,report,locators,Quality.defaults());
    }
    /** Compatibility constructor for integrations predating the locator repository. */
    public FrameworkConfig(int schemaVersion,String projectId,String profile,Org org,Ui ui,Auth auth,
        Map<String,PersonaConfig> personas,Execution execution,Agent agent,Cache cache,Retention retention,Evidence evidence,Report report) {
        this(schemaVersion,projectId,profile,org,ui,auth,personas,execution,agent,cache,retention,evidence,report,defaultLocators());
    }
    public record Org(String alias, String environmentType, String baseUrl, List<String> allowedOrigins, int uiProfileVersion, String recordTypeId) {}
    public record Ui(String language, String locale, String currency, String personaTimeZone, int width, int height,String browser,String shadowPolicy,String fileInputRoot) {
        public Ui(String language,String locale,String currency,String zone,int width,int height) { this(language,locale,currency,zone,width,height,"chromium","cdp",""); }
    }
    public record Auth(String mode,String clientIdRef,String privateKeyRef,String privateKeyFileRef,String audience,String tokenOrigin,String startRoute) {
        public Auth(String client,String key,String keyFile,String audience,String unusedAdmin) { this("jwt",client,key,keyFile,audience,"","/lightning/page/home"); }
    }
    public record PersonaConfig(String usernameRef,String storageStateRef) {}
    public record Execution(int workers, int retries, int testTimeoutSeconds, int actionTimeoutSeconds, int assertionTimeoutSeconds, boolean headed) {}
    public record Agent(String endpoint, String model, String apiKeyRef, int maxActions, int maxModelCalls, long maxTokens, int actTimeoutSeconds,AiWorkflow workflow) {
        public Agent(String endpoint,String model,String apiKeyRef,int actions,int calls,long tokens,int timeout) {
            this(endpoint,model,apiKeyRef,actions,calls,tokens,timeout,AiWorkflow.defaults());
        }
        public Agent { if(workflow==null) workflow=AiWorkflow.defaults(); }
    }
    public record AiTier(String endpoint,String model,String apiKeyRef,java.math.BigDecimal inputUsdPerMillionTokens,java.math.BigDecimal outputUsdPerMillionTokens) {}
    public record AiWorkflow(boolean reuseReadOnlyResponses,int maxInputBytes,int maxOutputTokens,String outputTokenParameter,
        int maxProtocolRetries,int maxEscalations,java.math.BigDecimal maxCostUsd,java.math.BigDecimal inputUsdPerMillionTokens,
        java.math.BigDecimal outputUsdPerMillionTokens,AiTier escalation,boolean compactRequests,boolean adaptiveOutputLimits,boolean batchCaseTargets,int maxTargetsPerBatch) {
        public AiWorkflow(boolean reuse,int input,int output,String parameter,int retries,int escalations,java.math.BigDecimal maxCost,java.math.BigDecimal inputRate,java.math.BigDecimal outputRate,AiTier tier) {
            this(reuse,input,output,parameter,retries,escalations,maxCost,inputRate,outputRate,tier,true,true,true,8);
        }
        public static AiWorkflow defaults() { return new AiWorkflow(true,131072,4096,"max_completion_tokens",1,0,java.math.BigDecimal.ZERO,null,null,null); }
        public void validate() {
            Failure.require(maxInputBytes>=1024 && maxInputBytes<=1048576 && maxOutputTokens>=128 && maxOutputTokens<=65536 &&
                Set.of("max_completion_tokens","max_tokens").contains(outputTokenParameter) && maxProtocolRetries>=0 && maxProtocolRetries<=1 &&
                maxEscalations>=0 && maxEscalations<=10 && maxCostUsd!=null && maxCostUsd.signum()>=0 && maxTargetsPerBatch>=2 && maxTargetsPerBatch<=16,"CONFIG_INVALID","Invalid AI workflow limits");
            rates(inputUsdPerMillionTokens,outputUsdPerMillionTokens,maxCostUsd.signum()>0);
            Failure.require(maxEscalations==0 || escalation!=null,"CONFIG_INVALID","AI escalation requires an explicit model tier");
            if(escalation!=null) {
                Failure.require(escalation.endpoint()!=null && escalation.model()!=null && !escalation.model().isBlank() &&
                    escalation.apiKeyRef()!=null && !escalation.apiKeyRef().isBlank(),"CONFIG_INVALID","AI escalation needs endpoint, model and credential reference");
                var uri=java.net.URI.create(escalation.endpoint());
                Failure.require(uri.getRawUserInfo()==null && uri.getFragment()==null && ("https".equals(uri.getScheme()) ||
                    "http".equals(uri.getScheme()) && Set.of("localhost","127.0.0.1").contains(uri.getHost())),"CONFIG_INVALID","AI escalation endpoint requires HTTPS or loopback HTTP");
                rates(escalation.inputUsdPerMillionTokens(),escalation.outputUsdPerMillionTokens(),maxCostUsd.signum()>0);
            }
        }
        private static void rates(java.math.BigDecimal input,java.math.BigDecimal output,boolean required) {
            Failure.require((input==null)==(output==null) && (!required || input!=null && input.signum()>0 && output.signum()>0) &&
                (input==null || input.signum()>=0 && output.signum()>=0),"CONFIG_INVALID","Configure both reviewed AI rates; a dollar budget requires positive rates");
        }
    }
    public record Cache(String mode, boolean strict, boolean requireRecorded, String dir, String onStoreFailure) {}
    public record Retention(String records, String journalDir) {}
    public record Evidence(int minPngPerPassedResult, List<String> piiLabels,boolean screenshotsAfterSteps,boolean privateTraceOnFailure,int privateRetentionHours) {
        public Evidence(int minimum,List<String> piiLabels,boolean steps) { this(minimum,piiLabels,steps,false,24); }
        public Evidence(int minimum,List<String> piiLabels) { this(minimum,piiLabels,true); }
    }
    public record Report(String root) {}
    public record Quality(boolean failOnPageErrors,boolean failOnConsoleErrors,boolean failOnHttpServerErrors,
        boolean failOnRequestFailures,int maxBrowserIssues,int maxHealingEvents,int maxLocatorRetries,List<IssueWaiver> waivers) {
        public Quality { waivers=waivers==null?List.of():List.copyOf(waivers); }
        public static Quality defaults() { return new Quality(true,true,true,true,200,-1,-1,List.of()); }
    }
    public record IssueWaiver(String kind,String path,String messageContains,String reason,String expiresOn) {}
    public record Locators(String dir,String mode,boolean healing,boolean strict,boolean aiFallback,
                           int retries,int retryDelayMillis,int timeoutSeconds,double minConfidence,double minMargin) {}
    public static Locators defaultLocators() {
        String ci=Objects.toString(System.getenv("CI"),"");
        boolean inCi=!ci.isBlank() && !ci.equals("0") && !ci.equalsIgnoreCase("false");
        return new Locators(".sf-e2e/locators",inCi?"read-only":"read-write",true,false,false,3,250,30,0.90,0.10);
    }
    public static FrameworkConfig defaults() {
        return new FrameworkConfig(1, "sf-agentic-e2e-java", "checks",
            new Org("default", "auto", "", List.of(), 1, ""),
            new Ui("", "", "", "", 1440, 1000),
            new Auth("jwt","SF_CLIENT_ID","SF_JWT_PRIVATE_KEY","SF_JWT_PRIVATE_KEY_FILE","","","/lightning/page/home"),
            Map.of("default",new PersonaConfig("SF_USERNAME","SF_STORAGE_STATE_FILE")),
            new Execution(1,0,600,45,30,false),
            new Agent("https://api.openai.com/v1/chat/completions","","E2E_MODEL_API_KEY",40,40,100000,120),
            new Cache("off",false,false,".sf-e2e/cache","fail"),
            new Retention("permanent",".sf-e2e-data"),new Evidence(2,List.of()),new Report("report"),defaultLocators());
    }
    public static FrameworkConfig load(Path file, Map<String,String> overrides) {
        try {
            ObjectNode tree = (ObjectNode) Json.MAPPER.valueToTree(defaults());
            if (file != null) {
                Failure.require(Files.isRegularFile(file),"CONFIG_INVALID","Configuration file does not exist: "+file.getFileName());
                merge(tree, YAMLMapper.builder().enable(com.fasterxml.jackson.dataformat.yaml.YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS).build().readTree(Files.readString(file)));
            }
            Map<String,String> env = Map.ofEntries(Map.entry("org.baseUrl","SF_BASE_URL"),Map.entry("profile","E2E_PROFILE"),Map.entry("agent.model","E2E_MODEL"),Map.entry("agent.endpoint","E2E_MODEL_ENDPOINT"),
                Map.entry("org.alias","SF_ORG_ALIAS"),Map.entry("auth.mode","SF_AUTH_MODE"),Map.entry("auth.audience","SF_JWT_AUDIENCE"),Map.entry("auth.tokenOrigin","SF_TOKEN_ORIGIN"),Map.entry("auth.startRoute","SF_START_ROUTE"),
                Map.entry("ui.language","SF_UI_LANGUAGE"),Map.entry("ui.locale","SF_UI_LOCALE"),Map.entry("ui.currency","SF_UI_CURRENCY"),Map.entry("ui.personaTimeZone","SF_TIME_ZONE"));
            env.forEach((key,name) -> { String value = System.getenv(name); if (value != null && !value.isBlank()) put(tree,key,value); });
            if (System.getenv("SF_LIGHTNING_ORIGIN") != null && !System.getenv("SF_LIGHTNING_ORIGIN").isBlank()) {
                ArrayNode origins = Json.MAPPER.createArrayNode();
                for(var origin:tree.path("org").path("allowedOrigins")) origins.add(origin);
                origins.add(System.getenv("SF_LIGHTNING_ORIGIN")); ((ObjectNode)tree.get("org")).set("allowedOrigins",origins);
            }
            System.getProperties().stringPropertyNames().stream().filter(k -> k.startsWith("sf.e2e."))
                .forEach(k -> put(tree,k.substring(7),System.getProperty(k)));
            overrides.forEach((key,value) -> put(tree,key,value));
            substitute(tree);
            Path projectRoot=file==null?Path.of("").toAbsolutePath():file.toAbsolutePath().getParent();
            for(String[] path:new String[][]{{"cache","dir"},{"retention","journalDir"},{"report","root"},{"locators","dir"}}) {
                ObjectNode section=(ObjectNode)tree.get(path[0]);
                Path resolved=projectRoot.resolve(section.path(path[1]).asText()).normalize();section.put(path[1],resolved.toString());
            }
            ObjectNode uiTree=(ObjectNode)tree.get("ui");if(!uiTree.path("fileInputRoot").asText().isBlank()) uiTree.put("fileInputRoot",projectRoot.resolve(uiTree.path("fileInputRoot").asText()).normalize().toString());
            FrameworkConfig config = Json.MAPPER.treeToValue(tree, FrameworkConfig.class);
            config.validate(); return config;
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("CONFIG_INVALID", "Configuration has an unknown key, invalid type, or malformed YAML"); }
    }
    private static void merge(ObjectNode target, JsonNode source) {
        Failure.require(source != null && source.isObject(), "CONFIG_INVALID", "Configuration must be an object");
        source.fields().forEachRemaining(e -> {
            Failure.require(target.has(e.getKey()),"CONFIG_INVALID","Unknown configuration key: " + e.getKey());
            if(e.getKey().equals("personas")) target.set(e.getKey(),e.getValue());
            else if (target.get(e.getKey()).isObject() && e.getValue().isObject()) merge((ObjectNode)target.get(e.getKey()), e.getValue());
            else target.set(e.getKey(),e.getValue());
        });
    }
    private static void put(ObjectNode tree,String key,String value) {
        String[] parts = key.split("\\."); ObjectNode current = tree;
        for (int i=0;i<parts.length-1;i++) {
            Failure.require(current.path(parts[i]).isObject(),"CONFIG_INVALID","Unknown configuration path: " + key);
            current = (ObjectNode)current.get(parts[i]);
        }
        String last=parts[parts.length-1]; JsonNode previous=current.get(last);
        Failure.require(previous != null,"CONFIG_INVALID","Unknown configuration path: " + key);
        if (previous.isBoolean()) { Failure.require(value.equals("true") || value.equals("false"),"CONFIG_INVALID","Invalid boolean: " + key); current.put(last,Boolean.parseBoolean(value)); }
        else if (previous.isIntegralNumber()) current.put(last,Long.parseLong(value));
        else if (previous.isFloatingPointNumber()) current.put(last,Double.parseDouble(value));
        else { Failure.require(previous.isTextual(),"CONFIG_INVALID","Only scalar overrides supported"); current.put(last,value); }
    }
    private static void substitute(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(e -> {
            if (e.getValue().isTextual()) {
                String value=e.getValue().asText();
                var matcher=java.util.regex.Pattern.compile("\\$\\{([A-Z0-9_]+)}").matcher(value);
                StringBuilder replaced=new StringBuilder();
                while(matcher.find()) {
                    String resolved=System.getenv(matcher.group(1));
                    Failure.require(resolved!=null,"CONFIG_INVALID","Missing environment reference: " + matcher.group(1));
                    matcher.appendReplacement(replaced,java.util.regex.Matcher.quoteReplacement(resolved));
                }
                matcher.appendTail(replaced); ((ObjectNode)node).put(e.getKey(),replaced.toString());
            } else substitute(e.getValue());
        });
        else if(node.isArray()) {
            for(int i=0;i<node.size();i++) {
                var child=node.get(i);
                if(child.isTextual()) {
                    var holder=Json.MAPPER.createObjectNode().set("value",child);substitute(holder);
                    ((ArrayNode)node).set(i,holder.get("value"));
                } else substitute(child);
            }
        }
    }
    public void validate() {
        hu.ak91.sf.e2e.core.quality.BrowserQuality.validate(quality);
        Failure.require(schemaVersion==1 && Set.of("checks","deterministic","mapping","smoke","regression","full-parity","ai-live","ai-cache").contains(profile),"CONFIG_INVALID","Unsupported schema or profile");
        SafePaths.segment(projectId); SafePaths.segment(org.alias());
        Failure.require(org.environmentType()!=null && org.environmentType().matches("[a-zA-Z0-9_-]{1,64}"),"CONFIG_INVALID","Environment type is descriptive metadata");
        Failure.require(execution.workers()>=1 && execution.workers()<=8 && execution.retries()==0,"CONFIG_INVALID","Use 1..8 isolated suite workers and zero business-action retries");
        Failure.require(evidence.privateRetentionHours()>=1 && evidence.privateRetentionHours()<=168,"CONFIG_INVALID","Private artifact retention must be 1..168 hours");
        Failure.require(ui.fileInputRoot()!=null && ui.fileInputRoot().length()<=1000,"CONFIG_INVALID","Invalid explicit file input root");
        Failure.require(Set.of("chromium","firefox","webkit").contains(ui.browser()) && Set.of("cdp","tracked").contains(ui.shadowPolicy()) && (ui.browser().equals("chromium") || ui.shadowPolicy().equals("tracked")),"CONFIG_INVALID","Non-Chromium engines require the explicit tracked-shadow coverage policy");
        Failure.require(locators!=null && Set.of("read-write","read-only").contains(locators.mode()) &&
            locators.retries()>=0 && locators.retries()<=20 && locators.retryDelayMillis()>=100 && locators.retryDelayMillis()<=5000 &&
            locators.timeoutSeconds()>0 && Double.isFinite(locators.minConfidence()) && locators.minConfidence()>=0.90 && locators.minConfidence()<=1 &&
            Double.isFinite(locators.minMargin()) && locators.minMargin()>=0.05 && locators.minMargin()<=1,
            "CONFIG_INVALID","Invalid locator retry or confidence policy");
        Failure.require(!profile.equals("deterministic") || !locators.aiFallback(),"CONFIG_INVALID","Deterministic profile cannot enable AI healing");
        Failure.require(execution.testTimeoutSeconds()>0 && execution.actionTimeoutSeconds()>0 && execution.assertionTimeoutSeconds()>0,"CONFIG_INVALID","Timeouts must be positive");
        if(!ui.locale().isBlank()) new Locale.Builder().setLanguageTag(ui.locale()).build();
        if(!ui.language().isBlank()) new Locale.Builder().setLanguageTag(ui.language()).build();
        if(!ui.currency().isBlank()) Currency.getInstance(ui.currency());
        if(!ui.personaTimeZone().isBlank()) ZoneId.of(ui.personaTimeZone());
        Failure.require(ui.width()>0 && ui.height()>0,"CONFIG_INVALID","Invalid viewport");
        Failure.require(retention.records().equals("permanent") && !Path.of(retention.journalDir()).toAbsolutePath().normalize().startsWith(Path.of("target").toAbsolutePath().normalize()),"RETENTION_VIOLATION","Journal must be permanent and outside target");
        Path journal=Path.of(retention.journalDir()).toAbsolutePath().normalize(),cacheRoot=Path.of(cache.dir()).toAbsolutePath().normalize(),reportRoot=Path.of(report.root()).toAbsolutePath().normalize();
        for(Path part:journal) Failure.require(!part.toString().equals("target"),"RETENTION_VIOLATION","Permanent journal cannot live under a Maven target directory");
        Failure.require(!journal.startsWith(cacheRoot) && !cacheRoot.startsWith(journal) && !journal.startsWith(reportRoot) && !reportRoot.startsWith(journal),
            "RETENTION_VIOLATION","Journal, cache and report roots must be separate");
        Path locatorRoot=Path.of(locators.dir()).toAbsolutePath().normalize();
        for(Path part:locatorRoot) Failure.require(!part.toString().equals("target"),"CONFIG_INVALID","Locators must survive Maven clean");
        Failure.require(!locatorRoot.startsWith(journal) && !journal.startsWith(locatorRoot) && !locatorRoot.startsWith(reportRoot) &&
            !reportRoot.startsWith(locatorRoot) && !locatorRoot.startsWith(cacheRoot) && !cacheRoot.startsWith(locatorRoot),
            "CONFIG_INVALID","Locator, replay, journal and report roots must be separate");
        Failure.require(evidence.minPngPerPassedResult()>=2,"CONFIG_INVALID","At least two captures are required");
        Failure.require(Set.of("off","read-write","read-only").contains(cache.mode()) && Set.of("live","fail").contains(cache.onStoreFailure()),"CONFIG_INVALID","Invalid cache policy");
        Failure.require(!Set.of("full-parity","regression","smoke").contains(profile) || cache.mode().equals("off"),"CONFIG_INVALID","Parity and deterministic profiles require cache off");
        new Budget(agent.maxActions(),agent.maxModelCalls(),agent.maxTokens());
        agent.workflow().validate();
        Failure.require(agent.actTimeoutSeconds()>0,"CONFIG_INVALID","Invalid agent timeout");
        Failure.require(Set.of("jwt","storage-state").contains(auth.mode()),"CONFIG_INVALID","Auth mode must be jwt or storage-state");
        if(!auth.audience().isBlank()) validateOrigin(auth.audience());
        if(!auth.tokenOrigin().isBlank()) validateOrigin(auth.tokenOrigin());
        validateRoute(auth.startRoute());
        Failure.require(!personas.isEmpty(),"CONFIG_INVALID","Configure at least one identity alias");
        personas.forEach((alias,identity) -> {
            Failure.require(alias.equals(Persona.valueOf(alias).name()),"CONFIG_INVALID","Identity aliases must be lowercase stable names");
            Failure.require(identity!=null && identity.usernameRef()!=null && identity.storageStateRef()!=null,"CONFIG_INVALID","Invalid identity configuration");
            if(auth.mode().equals("jwt")) SafePaths.segment(identity.usernameRef());else SafePaths.segment(identity.storageStateRef());
        });
        if (!org.baseUrl().isBlank()) {
            validateOrigin(org.baseUrl());
        }
        org.allowedOrigins().forEach(FrameworkConfig::validateOrigin);
    }
    public static void validateOrigin(String origin) {
        java.net.URI uri=java.net.URI.create(origin);
        Failure.require("https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getRawUserInfo()==null && (uri.getPath()==null || uri.getPath().isEmpty()) && uri.getQuery()==null && uri.getFragment()==null,"CONFIG_INVALID","Expected an exact trusted HTTPS origin");
    }
    public static void validateRoute(String route) {
        Failure.require(route!=null && route.startsWith("/") && !route.startsWith("//") && !route.contains("\\"),"CONFIG_INVALID","Expected an app-relative route");
        java.net.URI uri=java.net.URI.create(route);
        Failure.require(!uri.isAbsolute() && uri.getRawAuthority()==null && !isAuthenticationRoute(route),"CONFIG_INVALID","Route must identify an application page");
    }
    public static boolean isAuthenticationRoute(String route) {
        var uri=java.net.URI.create(route);String path=Objects.toString(uri.getPath(),"");String query=Objects.toString(uri.getRawQuery(),"");
        return path.matches("(?i).*(?:/secur/|/services/oauth2/|/(?:login|changepassword|identity)(?:[/.]|$)).*") || query.matches("(?i).*(?:^|&)(?:sid|otp|cshc)=.*");
    }
    public PersonaConfig persona(Persona persona) {
        var configured=personas.get(persona.name());Failure.require(configured!=null,"CONFIG_INVALID","Identity alias is not configured: "+persona.name());return configured;
    }
    public String tokenOrigin() { return auth.tokenOrigin().isBlank()?org.baseUrl():auth.tokenOrigin(); }
    public String jwtAudience() { return auth.audience().isBlank()?tokenOrigin():auth.audience(); }
    public List<String> origins() {
        var origins=new LinkedHashSet<>(org.allowedOrigins());
        if(!org.baseUrl().isBlank()) {
            origins.add(org.baseUrl());
            if(java.net.URI.create(org.baseUrl()).getHost().endsWith(".my.salesforce.com")) origins.add(org.baseUrl().replace(".my.salesforce.com",".lightning.force.com"));
        }
        return List.copyOf(origins);
    }
}
