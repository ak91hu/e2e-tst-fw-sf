package hu.ak91.sf.e2e.engine;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import hu.ak91.sf.e2e.core.*;
import hu.ak91.sf.e2e.core.quality.BrowserQuality;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class PlaywrightEngine implements UiEngine {
    private final Thread owner = Thread.currentThread();
    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private Page page;
    private CDPSession domInspector;
    private final boolean borrowedRuntime;
    private static final ThreadLocal<WorkerRuntime> WORKER=new ThreadLocal<>();
    public static void ensureWorkerRuntime(FrameworkConfig config) { if(WORKER.get()==null) new WorkerRuntime(config); }
    public static void closeWorkerRuntime() { if(WORKER.get()!=null) WORKER.get().close(); }
    /** A scoped, owner-thread browser process. Every engine lease still creates an isolated context. */
    public static final class WorkerRuntime implements AutoCloseable {
        private final Thread owner=Thread.currentThread();
        private final Playwright playwright;
        private final Browser browser;
        private final String browserName;
        private final boolean headed;
        private int leases;
        public WorkerRuntime(FrameworkConfig config) {
            Failure.require(WORKER.get()==null,"ENGINE_THREAD_VIOLATION","Worker runtime scopes cannot nest");
            browserName=config.ui().browser();headed=config.execution().headed();playwright=Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
            try { registerSelectors(playwright);browser=browserType(playwright,browserName).launch(new BrowserType.LaunchOptions().setHeadless(!headed).setTimeout(90000));WORKER.set(this); }
            catch(RuntimeException e) { playwright.close();throw new Failure("ENVIRONMENT_UNAVAILABLE","Worker browser could not start"); }
        }
        public void close() {
            Failure.require(owner==Thread.currentThread() && leases==0,"ENGINE_THREAD_VIOLATION","Close all contexts on the worker thread before its runtime");WORKER.remove();try { browser.close(); } finally { playwright.close(); }
        }
    }
    private static BrowserType browserType(Playwright runtime,String name) { return switch(name) { case "firefox" -> runtime.firefox();case "webkit" -> runtime.webkit();default -> runtime.chromium(); }; }
    private static void registerSelectors(Playwright runtime) {
        runtime.selectors().register("sfnode","{query(root,id){return this.queryAll(root,id)[0] || null;},queryAll(root,id){const e=window.__sfE2e?.state.nodes.get(id);return e && e.isConnected ? [e] : [];}}",new Selectors.RegisterOptions().setContentScript(false));
        runtime.selectors().register("sfoption","{query(root,payload){return this.queryAll(root,payload)[0] || null;},queryAll(root,payload){const p=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(payload),c=>c.charCodeAt(0))));return window.__sfE2e.options(p.control,p.label);}}",new Selectors.RegisterOptions().setContentScript(false));
    }
    private final FrameworkConfig config;
    private final SecretRegistry secrets;
    private long revision;
    private final Map<String,Frame> frames = new HashMap<>();
    private final Map<String,String> localIds = new HashMap<>();
    private final Deadline attemptDeadline;
    private boolean closed;
    private boolean qualityMonitoring,issueOverflow;
    private final List<BrowserQuality.Issue> issues=new ArrayList<>();
    private java.nio.file.Path inputRoot,downloadRoot;
    private java.util.function.Consumer<Map<String,Object>> operationEvents=e -> {};
    private final Map<String,Page> pageHandles=new LinkedHashMap<>();
    private java.util.function.Consumer<Dialog> armedDialog;
    private java.nio.file.Path traceRoot;
    private String traceAttempt;
    private boolean tracing;
    private static final long MAX_FILE_BYTES=64L*1024*1024;
    private static final String SCRIPT = resource("semantic.js");
    public PlaywrightEngine(FrameworkConfig config, SecretRegistry secrets, Deadline deadline) {
        this(config,secrets,deadline,Persona.DEFAULT);
    }
    public PlaywrightEngine(FrameworkConfig config, SecretRegistry secrets, Deadline deadline,Persona persona) {
        this(config,secrets,deadline,persona,config.auth().mode().equals("storage-state")?BrowserState.load(BrowserState.path(config,persona),config.origins(),secrets):null);
    }
    PlaywrightEngine(FrameworkConfig config,SecretRegistry secrets,Deadline deadline,Persona persona,String storageState) {
        this.config=config; this.secrets=secrets; this.attemptDeadline=deadline;
        WorkerRuntime worker=WORKER.get();borrowedRuntime=worker!=null;
        if(worker!=null) Failure.require(worker.browserName.equals(config.ui().browser()) && worker.headed==config.execution().headed(),"CONFIG_INVALID","Worker browser options cannot change between datasets");
        playwright=borrowedRuntime?worker.playwright:Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD","1")));
        try {
            if(!borrowedRuntime) registerSelectors(playwright);
            browser=borrowedRuntime?worker.browser:browserType(playwright,config.ui().browser()).launch(new BrowserType.LaunchOptions().setHeadless(!config.execution().headed()).setTimeout(deadline.millis(Duration.ofSeconds(90))));
            var options=new Browser.NewContextOptions().setViewportSize(config.ui().width(),config.ui().height()).setAcceptDownloads(true);
            if(!config.ui().locale().isBlank()) options.setLocale(config.ui().locale());
            if(!config.ui().personaTimeZone().isBlank()) options.setTimezoneId(config.ui().personaTimeZone());
            if(config.auth().mode().equals("storage-state")) { Failure.require(storageState!=null,"AUTH_CREDENTIAL_UNAVAILABLE","Authenticated browser state is required");options.setStorageState(storageState); }
            context=browser.newContext(options);
            context.addInitScript(SCRIPT);
            context.onPage(this::monitorPage);
            context.onResponse(response -> { if(response.status()>=500 && response.status()<=599) recordIssue("HTTP_SERVER_ERROR",response.url(),"HTTP "+response.status(),response.status()); });
            context.onRequestFailed(request -> recordIssue("REQUEST_FAILED",request.url(),Objects.toString(request.failure(),"Request failed"),0));
            page=context.newPage(); page.setDefaultTimeout(config.execution().actionTimeoutSeconds()*1000.0);
            domInspector=config.ui().shadowPolicy().equals("cdp")?context.newCDPSession(page):null;
            pageHandles.put("main",page);
            if(borrowedRuntime) worker.leases++;
        } catch(Failure e) { if(!borrowedRuntime) playwright.close();throw e; }
        catch (RuntimeException e) { if(!borrowedRuntime) playwright.close(); throw new Failure("ENVIRONMENT_UNAVAILABLE","Configured browser/context launch failed; install its Playwright browser"); }
    }
    private static String resource(String name) {
        try(var input=PlaywrightEngine.class.getResourceAsStream(name)) {
            if(input==null) throw new IllegalStateException("Missing semantic script"); return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    private void monitorPage(Page observed) {
        observed.onDialog(dialog -> { if(observed==page && armedDialog!=null) armedDialog.accept(dialog);else dialog.dismiss(); });
        observed.onPageError(message -> recordIssue("PAGE_ERROR",observed.url(),message,0));
        observed.onConsoleMessage(message -> { if(message.type().equals("error")) recordIssue("CONSOLE_ERROR",observed.url(),message.text(),0); });
    }
    private void recordIssue(String kind,String url,String message,int status) {
        if(!qualityMonitoring || closed) return;
        try {
            URI uri=URI.create(url);String source=origin(url);
            boolean local=config.profile().equals("checks") && Set.of("localhost","127.0.0.1").contains(Objects.toString(uri.getHost(),""));
            if(!local && !config.origins().contains(source) || FrameworkConfig.isAuthenticationRoute(url)) return;
            String path=Objects.toString(uri.getPath(),"/").replaceAll("(?<=/)[a-zA-Z0-9]{15}(?:[a-zA-Z0-9]{3})?(?=/|$)","{record}");
            if(issues.size()>=config.quality().maxBrowserIssues()) {
                if(!issueOverflow) { issues.add(new BrowserQuality.Issue("DIAGNOSTICS_OVERFLOW",Instant.now(),source,path,"Browser diagnostic limit exceeded",0));issueOverflow=true; }
                return;
            }
            String safe=secrets.redact(Objects.toString(message,""));
            // URLs in error messages can contain tokens even when no credential was registered yet.
            safe=safe.replaceAll("https?://[^\\s'\"<>]+","[URL OMITTED]");
            issues.add(new BrowserQuality.Issue(kind,Instant.now(),source,secrets.redact(path),safe.length()>2000?safe.substring(0,2000):safe,status));
        } catch(RuntimeException ignored) { /* Non-HTTP/internal browser resources are outside the monitored app. */ }
    }
    @Override public void startQualityMonitoring() { check(attemptDeadline);issues.clear();issueOverflow=false;qualityMonitoring=true; }
    @Override public List<BrowserQuality.Issue> browserIssues() {
        Failure.require(Thread.currentThread()==owner && !closed,"ENGINE_FAILURE","Diagnostics require the open owner-thread context");
        page.isClosed();return List.copyOf(issues);
    }
    private void check(Deadline deadline) {
        Failure.require(Thread.currentThread()==owner,"ENGINE_THREAD_VIOLATION","Playwright must execute on its owner thread");
        Failure.require(!closed,"ENGINE_FAILURE","Engine is closed"); deadline.check(); attemptDeadline.check();
    }
    private double timeout(Deadline deadline) { return deadline.millis(Duration.ofSeconds(config.execution().actionTimeoutSeconds())); }
    private void allowed(String url) {
        URI uri=URI.create(url);
        String origin=uri.getScheme()+"://"+uri.getRawAuthority();
        boolean local=config.profile().equals("checks") && (url.equals("about:blank") || uri.getHost()!=null && Set.of("localhost","127.0.0.1").contains(uri.getHost()));
        Failure.require(local || config.origins().contains(origin),"POLICY_DENIED","Navigation origin is outside the configured org");
        Failure.require(uri.getRawUserInfo()==null && (local || !FrameworkConfig.isAuthenticationRoute(url)),"POLICY_DENIED","Business navigation cannot target authentication routes");
    }
    @Override public void open(String route,Deadline deadline) {
        check(deadline); String url=route.startsWith("/") ? config.org().baseUrl()+route : route; allowed(url);
        page.navigate(url,new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(timeout(deadline)));
        allowed(page.url()); revision++; frames.clear(); localIds.clear();
    }
    /** Trusted auth bootstrap; no public observation or screenshots are taken on credential routes. */
    public void bootstrap(String frontdoor,Deadline deadline) {
        check(deadline);
        if(config.auth().mode().equals("storage-state")) { open(frontdoor,deadline);safePage();registerSessionSecrets();return; }
        URI uri=URI.create(frontdoor);
        Failure.require((uri.getScheme()+"://"+uri.getRawAuthority()).equals(config.org().baseUrl()) && uri.getPath().equals("/secur/frontdoor.jsp"),"POLICY_DENIED","Invalid private bootstrap URL");
        page.navigate(frontdoor,new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(timeout(deadline)));
        page.waitForURL(url -> {
            try { return config.origins().contains(origin(url)) && !FrameworkConfig.isAuthenticationRoute(url); } catch(RuntimeException e) { return false; }
        },new Page.WaitForURLOptions().setTimeout(timeout(deadline)));
        allowed(page.url());
        registerSessionSecrets();
    }
    private void registerSessionSecrets() {
        for(var cookie:context.cookies())
            if(cookie.name.matches("(?i)(sid.*|.*token.*|.*session.*|BrowserId.*)")) secrets.register(cookie.value);
    }
    public void setHarnessHtml(String html) {
        check(attemptDeadline); Failure.require(config.profile().equals("checks"),"POLICY_DENIED","HTML fixtures are only available in checks");
        page.evaluate(SCRIPT); page.setContent(html); page.evaluate(SCRIPT);
    }
    public void harnessScript(String script) {
        check(attemptDeadline); Failure.require(config.profile().equals("checks"),"POLICY_DENIED","Harness instrumentation is checks-only"); page.evaluate(script);
    }
    @Override public String route() { check(attemptDeadline); return secrets.redact(page.url()); }
    private void safePage() {
        allowed(page.url());
        Failure.require(!FrameworkConfig.isAuthenticationRoute(page.url()),"EVIDENCE_WITHHELD","Authentication route cannot be observed");
    }
    private static int closedRoots(com.fasterxml.jackson.databind.JsonNode node) {
        int count=node.isObject() && "closed".equals(node.path("shadowRootType").asText())?1:0;
        if(node.isContainerNode()) for(var child:node) count+=closedRoots(child);
        return count;
    }
    private boolean shadowCoverage() {
        try {
            int tracked=0;
            for(Frame frame:page.frames()) {
                frame.evaluate(SCRIPT);
                if(!Boolean.TRUE.equals(frame.evaluate("() => window.__sfE2e.state.closedTracked"))) return false;
                tracked+=((Number)frame.evaluate("() => window.__sfE2e.trackedClosedCount()")).intValue();
            }
            if(domInspector==null) return true; // Explicit tracked policy cannot certify pre-existing untracked closed roots.
            // Trusted DOM capability probe only; raw CDP data is never exported or passed to a model.
            var params=new com.google.gson.JsonObject();params.addProperty("depth",-1);params.addProperty("pierce",true);
            var tree=Json.MAPPER.readTree(domInspector.send("DOM.getDocument",params).toString());
            return closedRoots(tree)<=tracked;
        } catch(Exception e) { return false; }
    }
    @Override public Observation observe(String container,Deadline deadline) {
        check(deadline); safePage(); long current=++revision; frames.clear(); localIds.clear();
        List<Observation.Node> nodes=new ArrayList<>(); boolean truncated=false; String coverage="COMPLETE",reason=null;
        int index=0,scopeMatches=0;
        for(Frame frame:page.frames()) {
            String prefix=(index++)+":";
            if(frame!=page.mainFrame() && !config.origins().contains(origin(frame.url()))) { coverage="PARTIAL"; reason="Unapproved iframe"; continue; }
            try {
                frame.evaluate(SCRIPT);
                Object result=frame.evaluate("args => window.__sfE2e.observe(args)",Map.of("container",container,"secrets",secrets.maskingValues(),"pii",config.evidence().piiLabels()));
                var tree=Json.MAPPER.valueToTree(result); truncated |= tree.path("truncated").asBoolean();
                scopeMatches+=tree.path("scopeMatches").asInt();
                for(var entry:tree.path("nodes")) {
                    var node=Json.MAPPER.convertValue(entry,Observation.Node.class);
                    String id=prefix+node.id(); frames.put(id,frame); localIds.put(id,node.id());
                    Map<String,String> attributes=new HashMap<>();
                    node.attributes().forEach((key,value) -> { String safe=secrets.redact(value); if(!safe.contains("[REDACTED]")) attributes.put(key,safe); });
                    attributes.put("frame",frame==page.mainFrame()?"main":hu.ak91.sf.e2e.core.locator.LocatorEntry.routeKey(frame.url()));
                    var t=node.target(); nodes.add(new Observation.Node(id,new SemanticTarget(t.role(),secrets.redact(t.name()),secrets.redact(t.label()),t.container()),
                        secrets.redact(node.text()),secrets.redact(node.value()),node.disabled(),node.checked(),attributes));
                }
            } catch(RuntimeException e) { throw new Failure("AUTOMATION_UNSUPPORTED","Cannot observe requested UI scope safely"); }
        }
        Failure.require(container==null || container.equals("page") || scopeMatches==1,"AUTOMATION_UNSUPPORTED",
            "Requested UI scope must occur exactly once across approved frames");
        if(!shadowCoverage()) { coverage="PARTIAL";reason="Untracked closed root or unavailable DOM capability probe"; }
        return new Observation(current,Instant.now(),route(),List.copyOf(nodes),truncated,coverage,"SAFE",reason);
    }
    private static String origin(String url) {
        try { var uri=URI.create(url); return uri.getScheme()+"://"+uri.getRawAuthority(); } catch(Exception e) { return ""; }
    }
    @Override public Observation.Node resolve(SemanticTarget target,Observation observation) {
        check(attemptDeadline); Failure.require(observation.revision()==revision,"NODE_STALE","Observation is stale");
        var matching=observation.nodes().stream().filter(n -> (target.container()==null || Objects.equals(target.container(),n.target().container()))
            && (target.role()==null || Objects.equals(target.role(),n.target().role()))
            && (target.name()==null || Objects.equals(target.name(),n.target().name()))
            && (target.label()==null || Objects.equals(target.label(),n.target().label()))).toList();
        Failure.require(matching.size()==1,matching.isEmpty()?"TARGET_NOT_FOUND":"TARGET_AMBIGUOUS","Exact semantic target must resolve once: " + target);
        return matching.getFirst();
    }
    private Locator locator(String id,Observation observation,Deadline deadline) {
        check(deadline); Failure.require(observation.revision()==revision && frames.containsKey(id),"NODE_STALE","Unknown or stale observation node");
        Locator locator=frames.get(id).locator("sfnode="+localIds.get(id));
        Failure.require(locator.count()==1 && locator.isVisible(),"NODE_STALE","Observed UI node is no longer visible");
        Failure.require(locator.isEnabled(),"POLICY_DENIED","UI node is disabled"); return locator;
    }
    @Override public Map<String,String> navigationRoutes(Observation observation,Deadline deadline) {
        check(deadline);safePage();
        Failure.require(observation.complete() && observation.revision()==revision,"NODE_STALE","Exploration needs a current complete observation");
        Map<String,String> routes=new TreeMap<>();
        for(var node:observation.nodes().stream().filter(n -> "link".equals(n.target().role())).limit(200).toList()) {
            deadline.check();
            if(!"link".equals(node.target().role()) || "true".equals(node.attributes().get("recordLink")) || node.disabled()) continue;
            // Read DOM properties only. No onclick, scripts, buttons, record links or authentication.
            var raw=frames.get(node.id()).locator("sfnode="+localIds.get(node.id())).evaluate("e => e.tagName === 'A' && !e.hasAttribute('download') && !e.hasAttribute('onclick') ? e.href : null");
            if(!(raw instanceof String href) || secrets.containsSecret(href)) continue;
            try {
                URI uri=URI.create(href);String targetOrigin=origin(href);
                boolean equivalent=targetOrigin.equals(config.org().baseUrl()) || config.org().baseUrl().contains(".my.salesforce.com") &&
                    targetOrigin.equals(config.org().baseUrl().replace(".my.salesforce.com",".lightning.force.com"));
                boolean viewQuery=uri.getRawQuery()==null || Arrays.stream(uri.getRawQuery().split("&")).allMatch(p ->
                    Set.of("filterName","view","tab","sort","order","page","pageSize","lang","language","locale").contains(p.split("=",2)[0]));
                if(!equivalent || !viewQuery || uri.getRawFragment()!=null || uri.getRawUserInfo()!=null || uri.getPath().matches(".*(?:/|^)[a-zA-Z0-9]{15}(?:[a-zA-Z0-9]{3})?(?:/|$).*") ||
                    uri.getPath().matches("(?i).*(?:delete|logout|signout|remove|download|export|frontdoor).*") || FrameworkConfig.isAuthenticationRoute(href)) continue;
                String route=uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
                FrameworkConfig.validateRoute(route);routes.put(node.id(),route);
            } catch(RuntimeException ignored) { /* Non-application hrefs are not exploration candidates. */ }
        }
        return Map.copyOf(routes);
    }
    @Override public void perform(Action action,Observation observation,Deadline deadline) {
        safePage(); Locator target=locator(action.nodeId(),observation,deadline); double timeout=timeout(deadline);
        switch(action.kind()) {
            case "click" -> target.click(new Locator.ClickOptions().setTimeout(timeout));
            case "fill","richText" -> target.fill(action.value(),new Locator.FillOptions().setTimeout(timeout));
            case "workspaceTab" -> { Failure.require("tab".equals(target.getAttribute("role")),"POLICY_DENIED","Workspace target must be a tab");target.click(new Locator.ClickOptions().setTimeout(timeout)); }
            case "gridFind" -> gridFind(target,action.value(),deadline);
            case "upload" -> upload(target,action.value(),deadline);
            case "download" -> download(target,action.value(),deadline);
            case "clickPopup" -> popup(target,action.value(),deadline);
            case "clickDialog" -> dialog(target,action.value(),deadline);
            case "lookup" -> {
                var value=structured(action.value(),Set.of("query","option"));var wait=deadline.child(Duration.ofSeconds(config.execution().actionTimeoutSeconds()));
                target.fill(value.path("query").asText(),new Locator.FillOptions().setTimeout(timeout(wait)));
                var options=picklistOptions(frames.get(action.nodeId()),target,value.path("option").asText());
                try { page.waitForCondition(() -> options.count()>0,new Page.WaitForConditionOptions().setTimeout(timeout(wait))); } catch(TimeoutError e) { throw new Failure("TARGET_NOT_FOUND","Lookup result did not appear after typing"); }
                Failure.require(options.count()==1,"TARGET_AMBIGUOUS","Lookup result must be unique within the control-owned container");options.click(new Locator.ClickOptions().setTimeout(timeout(wait)));
            }
            case "multiSelect" -> {
                var values=Json.MAPPER.valueToTree(parse(action.value()));Failure.require(values.isArray() && !values.isEmpty() && values.size()<=100,"CASE_INVALID","Multi-select requires a JSON label array");
                Set<String> unique=new HashSet<>();List<SelectOption> options=new ArrayList<>();for(var value:values) { Failure.require(value.isTextual() && unique.add(value.asText()),"CASE_INVALID","Multi-select labels must be unique");options.add(new SelectOption().setLabel(value.asText())); }
                if("SELECT".equals(target.evaluate("e => e.tagName"))) { Failure.require(Boolean.TRUE.equals(target.evaluate("e => e.multiple")),"AUTOMATION_UNSUPPORTED","Multi-select target is not a multiple select");target.selectOption(options.toArray(SelectOption[]::new),new Locator.SelectOptionOptions().setTimeout(timeout)); }
                else {
                    Failure.require("true".equals(target.getAttribute("aria-multiselectable")) || "true".equals(target.evaluate("e => {const ids=(e.getAttribute('aria-controls')||e.getAttribute('aria-owns')||'').split(/\\s+/);return window.__sfE2e.elements().find(n=>ids.includes(n.id))?.getAttribute('aria-multiselectable')||'';}")),"AUTOMATION_UNSUPPORTED","Custom multi-select must declare its state and owned listbox");
                    var wait=deadline.child(Duration.ofSeconds(config.execution().actionTimeoutSeconds()));var all=picklistOptions(frames.get(action.nodeId()),target,null);target.click(new Locator.ClickOptions().setTimeout(timeout(wait)));
                    try { page.waitForCondition(() -> all.count()>0,new Page.WaitForConditionOptions().setTimeout(timeout(wait))); } catch(TimeoutError e) { throw new Failure("TARGET_NOT_FOUND","Multi-select options did not appear"); }
                    for(String label:unique) Failure.require(picklistOptions(frames.get(action.nodeId()),target,label).count()==1,"TARGET_AMBIGUOUS","A multi-select label is absent or ambiguous");
                    int count=all.count();for(int i=0;i<count;i++) { Locator option=all.nth(i);String selected=option.getAttribute("aria-selected"),label=option.getAttribute("aria-label");if(label==null) label=option.textContent().trim();Failure.require(Set.of("true","false").contains(Objects.toString(selected,"")),"AUTOMATION_UNSUPPORTED","Multi-select options require explicit selected state");String size=option.getAttribute("aria-setsize");Failure.require(size==null || size.equals(Integer.toString(count)),"AUTOMATION_UNSUPPORTED","Virtualized multi-select cannot establish the complete selected set");boolean wanted=unique.contains(label);if(wanted!=selected.equals("true")) {option.click(new Locator.ClickOptions().setTimeout(timeout(wait)));Failure.require(Boolean.toString(wanted).equals(option.getAttribute("aria-selected")),"ASSERTION_FAILED","Multi-select choice did not persist");} }
                    Set<String> seen=new HashSet<>(),selectedNow=new HashSet<>();for(int i=0;i<all.count();i++) {Locator option=all.nth(i);String label=option.getAttribute("aria-label");if(label==null) label=option.textContent().trim();Failure.require(seen.add(label),"TARGET_AMBIGUOUS","Multi-select option labels became ambiguous");if("true".equals(option.getAttribute("aria-selected"))) selectedNow.add(label);}Failure.require(selectedNow.equals(unique),"ASSERTION_FAILED","Final multi-select state differs from the complete supplied choice set");
                }
            }
            case "press" -> target.press(action.value(),new Locator.PressOptions().setTimeout(timeout));
            case "select" -> {
                if ("SELECT".equals(target.evaluate("e => e.tagName"))) target.selectOption(new SelectOption().setLabel(action.value()),new Locator.SelectOptionOptions().setTimeout(timeout));
                else {
                    var wait=deadline.child(Duration.ofSeconds(config.execution().actionTimeoutSeconds()));
                    var option=picklistOptions(frames.get(action.nodeId()),target,action.value());
                    target.click(new Locator.ClickOptions().setTimeout(timeout(wait)));
                    try { page.waitForCondition(() -> option.count()>0,new Page.WaitForConditionOptions().setTimeout(timeout(wait))); }
                    catch(TimeoutError missing) { throw new Failure("TARGET_NOT_FOUND","Picklist option did not appear before its deadline; the opening click was dispatched"); }
                    Failure.require(option.count()==1,"TARGET_AMBIGUOUS","Picklist option must resolve exactly");
                    option.click(new Locator.ClickOptions().setTimeout(timeout(wait)));
                }
            }
            case "setChecked" -> target.setChecked(Boolean.parseBoolean(action.value()),new Locator.SetCheckedOptions().setTimeout(timeout));
            case "scroll" -> target.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(timeout));
            default -> throw new Failure("POLICY_DENIED","Unsupported UI action");
        }
        deadline.check(); revision++;
    }
    private static com.fasterxml.jackson.databind.JsonNode parse(String value) { try { return Json.MAPPER.readTree(value); } catch(Exception e) { throw new Failure("CASE_INVALID","Operation parameter must be valid JSON"); } }
    private static com.fasterxml.jackson.databind.JsonNode structured(String value,Set<String> required) {
        var tree=parse(value);Failure.require(tree!=null && tree.isObject() && tree.size()==required.size() && required.stream().allMatch(k -> tree.path(k).isTextual()),"CASE_INVALID","Operation parameter keys/types are invalid");return tree;
    }
    /** Opt-in local input root; outputs belong to one attempt and are never attached as public screenshots. */
    public void configureFiles(java.nio.file.Path inputs,java.nio.file.Path ownedOutputs,java.util.function.Consumer<Map<String,Object>> events) {
        check(attemptDeadline);try { inputRoot=inputs.toRealPath();Failure.require(java.nio.file.Files.isDirectory(inputRoot),"CONFIG_INVALID","File input root must be a directory");downloadRoot=PrivateArtifacts.create(ownedOutputs.toAbsolutePath().normalize(),ownedOutputs.getParent().getFileName().toString(),config.evidence().privateRetentionHours());operationEvents=events; }
        catch(java.io.IOException e) { throw new Failure("CONFIG_INVALID","File roots are unavailable"); }
    }
    public void operationEvents(java.util.function.Consumer<Map<String,Object>> events) { check(attemptDeadline);operationEvents=Objects.requireNonNull(events); }
    private void upload(Locator target,String value,Deadline deadline) {
        Failure.require(inputRoot!=null,"POLICY_DENIED","Configure an explicit file input root before upload");
        try { var path=SafePaths.inside(inputRoot,value).toRealPath();Failure.require(path.startsWith(inputRoot) && java.nio.file.Files.isRegularFile(path) && java.nio.file.Files.size(path)<=MAX_FILE_BYTES,"POLICY_DENIED","Upload file is outside its root or exceeds 64 MiB");
            String hash=Json.hash(java.nio.file.Files.readAllBytes(path));
            if("INPUT".equals(target.evaluate("e => e.tagName"))) target.setInputFiles(path,new Locator.SetInputFilesOptions().setTimeout(timeout(deadline)));
            else { var chooser=page.waitForFileChooser(new Page.WaitForFileChooserOptions().setTimeout(timeout(deadline)),() -> target.click(new Locator.ClickOptions().setTimeout(timeout(deadline))));chooser.setFiles(path,new FileChooser.SetFilesOptions().setTimeout(timeout(deadline))); }
            operationEvents.accept(Map.of("kind","file-transfer","direction","upload","sha256",hash,"bytes",java.nio.file.Files.size(path))); }
        catch(java.io.IOException e) { throw new Failure("FILE_VERIFICATION_FAILED","Upload file cannot be read"); }
    }
    private void download(Locator target,String value,Deadline deadline) {
        Failure.require(downloadRoot!=null,"POLICY_DENIED","Configure owned download output before dispatch");var expected=structured(value,Set.of("sha256","text"));
        Failure.require(expected.path("sha256").asText().matches("[a-f0-9]{64}"),"CASE_INVALID","Supply an exact expected download checksum");
        Download item=page.waitForDownload(new Page.WaitForDownloadOptions().setTimeout(timeout(deadline)),() -> target.click(new Locator.ClickOptions().setTimeout(timeout(deadline))));
        Failure.require(item.failure()==null,"FILE_VERIFICATION_FAILED","Download failed after dispatch");
        var output=SafePaths.inside(downloadRoot,UUID.randomUUID()+".download");
        try { var source=item.path();Failure.require(java.nio.file.Files.size(source)<=MAX_FILE_BYTES,"FILE_VERIFICATION_FAILED","Download exceeds 64 MiB");item.saveAs(output);byte[] bytes=java.nio.file.Files.readAllBytes(output);String hash=Json.hash(bytes);
            PrivateArtifacts.manifest(downloadRoot,downloadRoot.getParent().getFileName().toString(),output,MAX_FILE_BYTES);
            operationEvents.accept(Map.of("kind","file-transfer","direction","download","sha256",hash,"bytes",bytes.length,"path",output.getFileName().toString(),"confidential",true));
            Failure.require(hash.equals(expected.path("sha256").asText()) && (expected.path("text").asText().isEmpty() || new String(bytes,StandardCharsets.UTF_8).equals(expected.path("text").asText())),"FILE_VERIFICATION_FAILED","Downloaded checksum/content does not match the supplied expectation"); }
        catch(java.io.IOException e) { throw new Failure("FILE_VERIFICATION_FAILED","Owned download cannot be verified"); }
    }
    private void popup(Locator target,String expectedRoute,Deadline deadline) {
        FrameworkConfig.validateRoute(expectedRoute);Page previous=page;Page opened=page.waitForPopup(new Page.WaitForPopupOptions().setTimeout(timeout(deadline)),() -> target.click(new Locator.ClickOptions().setTimeout(timeout(deadline))));
        opened.waitForLoadState(LoadState.DOMCONTENTLOADED,new Page.WaitForLoadStateOptions().setTimeout(timeout(deadline)));allowed(opened.url());
        URI destination=URI.create(opened.url());String actualRoute=destination.getRawPath()+(destination.getRawQuery()==null?"":"?"+destination.getRawQuery())+(destination.getRawFragment()==null?"":"#"+destination.getRawFragment());Failure.require(actualRoute.equals(expectedRoute),"ASSERTION_FAILED","Popup route differs from the supplied expectation");
        String previousHandle=pageHandles.entrySet().stream().filter(e -> e.getValue()==previous).map(Map.Entry::getKey).findFirst().orElseThrow();String handle=UUID.randomUUID().toString();pageHandles.put(handle,opened);switchPage(handle,deadline);operationEvents.accept(Map.of("kind","page-opened","handle",handle,"previous",previousHandle,"route",secrets.redact(opened.url())));
    }
    public Set<String> pageHandles() { check(attemptDeadline);return Set.copyOf(pageHandles.keySet()); }
    public void startPrivateTrace(java.nio.file.Path ownedRoot,String attemptId) {
        check(attemptDeadline);safePage();Failure.require(qualityMonitoring && !tracing,"POLICY_DENIED","Trace starts only after trusted authentication and quality monitoring");
        traceRoot=PrivateArtifacts.create(ownedRoot,attemptId,config.evidence().privateRetentionHours());traceAttempt=attemptId;
        context.tracing().start(new Tracing.StartOptions().setScreenshots(false).setSnapshots(true).setSources(false));tracing=true;
    }
    public void finishPrivateTrace(boolean failure) {
        Failure.require(Thread.currentThread()==owner && !closed,"ENGINE_THREAD_VIOLATION","Finalize diagnostics on the open owner thread");
        if(!tracing) return;tracing=false;
        if(!failure) { context.tracing().stop();return; }
        var file=SafePaths.inside(traceRoot,UUID.randomUUID()+".zip");context.tracing().stop(new Tracing.StopOptions().setPath(file));
        try { if(java.nio.file.Files.size(file)>MAX_FILE_BYTES) {Failure.require(file.toRealPath().getParent().equals(traceRoot.toRealPath()),"POLICY_DENIED","Trace output ownership changed");java.nio.file.Files.delete(file);throw new Failure("EVIDENCE_INCOMPLETE","Private trace exceeded 64 MiB and the newly generated owned ZIP was discarded");} }
        catch(java.io.IOException e) {throw new Failure("EVIDENCE_INCOMPLETE","Private trace size cannot be verified");}
        PrivateArtifacts.manifest(traceRoot,traceAttempt,file,MAX_FILE_BYTES);
        operationEvents.accept(Map.of("kind","private-trace-retained","classification","CONFIDENTIAL_RAW","attemptId",traceAttempt,"publicAttachment",false));
    }
    /** Audits approved frames independently; axe cannot certify closed shadow roots. Incomplete audits fail review. */
    public com.fasterxml.jackson.databind.JsonNode accessibilityAudit(List<String> rules,Deadline deadline) {
        check(deadline);safePage();Failure.require(rules!=null && !rules.isEmpty() && rules.size()<=100 && rules.stream().allMatch(r -> r!=null && r.matches("[a-z0-9-]{1,100}")),"CONFIG_INVALID","Supply explicit axe rule IDs");
        var output=Json.MAPPER.createObjectNode();var violations=output.putArray("violations");var incomplete=output.putArray("incomplete");output.put("engine","axe-core-4.14.0");
        for(Frame frame:page.frames()) {
            Failure.require(frame==page.mainFrame() || config.origins().contains(origin(frame.url())),"QUALITY_GATE_FAILED","Accessibility frame is outside the authorized origin set");frame.evaluate(SCRIPT);
            Failure.require(((Number)frame.evaluate("() => window.__sfE2e.trackedClosedCount()")).intValue()==0,"AUTOMATION_UNSUPPORTED","axe cannot certify closed shadow trees");frame.evaluate(resource("axe.min.js"));
            var result=Json.MAPPER.valueToTree(frame.evaluate("rules => axe.run(document,{runOnly:{type:'rule',values:rules},iframes:false}).then(r => ({violations:r.violations.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.map(n=>({target:n.target}))})),incomplete:r.incomplete.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.map(n=>({target:n.target}))}))}))",rules));
            for(String kind:List.of("violations","incomplete")) for(var issue:result.path(kind)) { var safe=Json.MAPPER.createObjectNode();safe.put("id",issue.path("id").asText());safe.put("impact",issue.path("impact").asText());var nodes=safe.putArray("nodes");for(var node:issue.path("nodes")) nodes.addObject().put("targetHash",Json.hash(Map.of("frame",secrets.redact(frame.url()),"target",node.path("target"))));(kind.equals("violations")?violations:incomplete).add(safe); }
            deadline.check();
        }
        return output;
    }
    public Map<String,Double> performanceMetrics(Deadline deadline) {
        check(deadline);safePage();var values=Json.MAPPER.valueToTree(page.evaluate("() => { const n=performance.getEntriesByType('navigation')[0];const p=performance.getEntriesByType('paint');return {domContentLoadedMs:n?.domContentLoadedEventEnd || -1,responseMs:n?.responseEnd || -1,loadMs:n?.loadEventEnd || -1,elapsedMs:performance.now(),firstContentfulPaintMs:p.find(e=>e.name==='first-contentful-paint')?.startTime ?? -1}; }"));Map<String,Double> result=new LinkedHashMap<>();values.fields().forEachRemaining(e -> result.put(e.getKey(),e.getValue().asDouble()));return Map.copyOf(result);
    }
    @Override public void switchPage(String handle,Deadline deadline) {
        check(deadline);Page selected=pageHandles.get(handle);Failure.require(selected!=null && !selected.isClosed(),"TARGET_NOT_FOUND","Page handle is unknown or closed");allowed(selected.url());page=selected;
        if(domInspector!=null) domInspector.detach();domInspector=config.ui().shadowPolicy().equals("cdp")?context.newCDPSession(page):null;revision++;frames.clear();localIds.clear();
    }
    /** Locate one explicit row across bounded virtual scroll windows; never interpret visible rows as a total dataset. */
    private void gridFind(Locator grid,String value,Deadline deadline) {
        var plan=structured(value,Set.of("row","maxScrolls"));int limit;try { limit=Integer.parseInt(plan.path("maxScrolls").asText()); } catch(Exception e) { throw new Failure("CASE_INVALID","Grid scroll bound must be an integer"); }
        Failure.require(limit>=0 && limit<=100 && Set.of("grid","table","treegrid").contains(Objects.toString(grid.getAttribute("role"),"")),"CASE_INVALID","Grid requires an explicit role and 0..100 scroll bound");
        for(int i=0;i<=limit;i++) {
            deadline.check();int count=((Number)grid.evaluate("(e,name)=>window.__sfE2e.elements(e).filter(n=>n.getAttribute('role')==='row' && window.__sfE2e.visible(n) && (n.getAttribute('aria-label')||n.textContent.trim())===name).length",plan.path("row").asText())).intValue();
            Failure.require(count<=1,"TARGET_AMBIGUOUS","Virtual grid row name is ambiguous");if(count==1) return;
            if(i==limit) break;boolean moved=Boolean.TRUE.equals(grid.evaluate("e=>{const old=e.scrollTop;e.scrollTop+=Math.max(1,e.clientHeight);return e.scrollTop!==old;}"));if(!moved) break;poll(deadline);
        }
        throw new Failure("ASSERTION_FAILED","Expected virtual grid row was not found within its supplied scroll bound");
    }
    private void dialog(Locator target,String value,Deadline deadline) {
        var plan=structured(value,Set.of("type","message","decision","prompt"));Failure.require(Set.of("alert","confirm","prompt","beforeunload").contains(plan.path("type").asText()) && Set.of("accept","dismiss").contains(plan.path("decision").asText()),"CASE_INVALID","Invalid dialog expectation");
        final boolean[] received={false},matched={false};java.util.function.Consumer<Dialog> listener=d -> { received[0]=true;matched[0]=d.type().equals(plan.path("type").asText()) && d.message().equals(plan.path("message").asText());if(matched[0] && plan.path("decision").asText().equals("accept")) d.accept(plan.path("prompt").asText());else d.dismiss(); };
        armedDialog=listener;try { target.click(new Locator.ClickOptions().setTimeout(timeout(deadline)));Failure.require(received[0] && matched[0],"ASSERTION_FAILED","Native dialog did not match its explicit expectation"); }
        finally { armedDialog=null; }
    }
    private static Locator picklistOptions(Frame frame,Locator control,String value) {
        String id=(String)control.evaluate("e => [...window.__sfE2e.state.nodes].find(([id,node])=>node===e)?.[0]");
        var params=new LinkedHashMap<String,Object>();params.put("control",id);params.put("label",value);
        return frame.locator("sfoption="+Base64.getEncoder().encodeToString(Json.string(params).getBytes(StandardCharsets.UTF_8)));
    }
    /** Deterministic component adapter; consumers still have no raw Page access. */
    public void action(String kind,SemanticTarget target,String value,Deadline deadline) {
        var snapshot=observe(target.container()==null?"page":target.container(),deadline);
        perform(new Action(kind,resolve(target,snapshot).id(),value),snapshot,deadline);
    }
    public void fillNumeric(SemanticTarget target,String value,Deadline deadline) {
        var snapshot=observe(target.container(),deadline); var node=resolve(target,snapshot);
        Locator locator=locator(node.id(),snapshot,deadline); locator.focus();
        page.keyboard().press("ControlOrMeta+A"); page.keyboard().press("Backspace");
        locator.fill(value,new Locator.FillOptions().setTimeout(timeout(deadline))); revision++;
    }
    public String attribute(SemanticTarget target,String name,Deadline deadline) {
        var snapshot=observe(target.container(),deadline); return locator(resolve(target,snapshot).id(),snapshot,deadline).getAttribute(name);
    }
    @Override public Map<String,String> readVisibleFields(Deadline deadline) {
        check(deadline); safePage();
        try {
            var rows=Json.MAPPER.valueToTree(page.evaluate("() => window.__sfE2e.fields()"));
            Map<String,String> fields=new LinkedHashMap<>();
            for(var row:rows) {
                String label=row.path("label").asText(),value=secrets.redact(row.path("value").asText());
                Failure.require(!fields.containsKey(label),"TARGET_AMBIGUOUS","Duplicate visible Details field: " + label);
                fields.put(label,value);
                for(var link:row.path("links")) {
                    String key=label+"::link::"+link.path("name").asText();
                    Failure.require(!fields.containsKey(key),"TARGET_AMBIGUOUS","Duplicate field relationship link");
                    fields.put(key,link.path("href").asText());
                }
            }
            return Map.copyOf(fields);
        } catch(Failure e) { throw e; } catch(RuntimeException e) { throw new Failure("AUTOMATION_UNSUPPORTED","Visible Details reader failed"); }
    }
    @Override public byte[] captureMaskedPng(Deadline deadline) {
        check(deadline); safePage(); List<Locator> masks=new ArrayList<>();
        Failure.require(shadowCoverage(),"EVIDENCE_WITHHELD","Masking cannot cover an untracked closed shadow root");
        try {
            for(Frame frame:page.frames()) {
                Failure.require(frame==page.mainFrame() || config.origins().contains(origin(frame.url())),"EVIDENCE_WITHHELD","Mask coverage cannot be proven for iframe");
                frame.evaluate(SCRIPT);
                var ids=(List<?>)frame.evaluate("args => window.__sfE2e.masks(args)",Map.of("secrets",secrets.maskingValues(),"pii",config.evidence().piiLabels()));
                for(Object id:ids) masks.add(frame.locator("sfnode="+id));
            }
            revision++; frames.clear(); localIds.clear();
            return page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG).setMask(masks).setTimeout(timeout(deadline)));
        } catch(Failure e) { throw e; } catch(RuntimeException e) {
            throw new Failure("EVIDENCE_WITHHELD","Screenshot masking or capture failed ("+e.getClass().getSimpleName()+"): "+secrets.redact(Objects.toString(e.getMessage(),"No diagnostic")));
        }
    }
    @Override public void poll(Deadline deadline) {
        check(deadline);
        // Message-loop-aware bounded polling, never used as a business readiness condition.
        try { page.waitForCondition(() -> false,new Page.WaitForConditionOptions().setTimeout(deadline.millis(Duration.ofMillis(100)))); }
        catch (TimeoutError expected) { deadline.check(); }
    }
    @Override public String capabilityVersion() { return "playwright-1.63/semantic-5/query-context-and-frame-scopes"+(config.ui().browser().equals("chromium") && config.ui().shadowPolicy().equals("cdp")?"":"/"+config.ui().browser()+"/"+config.ui().shadowPolicy()); }
    @Override public void close() {
        Failure.require(Thread.currentThread()==owner,"ENGINE_THREAD_VIOLATION","Close on engine owner thread");
        if(!closed) { try { finishPrivateTrace(false); } finally { closed=true;try { context.close(); } finally { if(borrowedRuntime) WORKER.get().leases--;else try { browser.close(); } finally { playwright.close(); } } } }
    }
}
