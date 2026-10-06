package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Bounded, context-separated observational trends; neither historical failures nor unknown costs become zero. */
public final class TrendReports {
    private static final int MAX_FILE=16*1024*1024,MAX_HISTORY=1000;
    public record Metric(int schemaVersion,String runId,String kind,String cohort,boolean comparable,Instant startedAt,Instant completedAt,
                         String status,String durationBasis,int passed,int failed,int skipped,Map<String,Double> values,Map<String,String> sources) {
        public Metric {SafePaths.segment(runId);Failure.require(schemaVersion==1 && Set.of("suite","workflow","junit","release").contains(kind) && cohort!=null && cohort.matches("[a-f0-9]{64}") && startedAt!=null && completedAt!=null && !completedAt.isBefore(startedAt) && status!=null && Set.of("PASSED","FAILED","BLOCKED","SKIPPED","CANCELLED").contains(status) && Set.of("wall-clock","test-duration-sum").contains(durationBasis) && passed>=0 && failed>=0 && skipped>=0 && (long)passed+failed+skipped>0 && values!=null && values.size()<=100 && sources!=null && !sources.isEmpty() && sources.size()<=505,"TREND_INVALID","Invalid metric contract");values.values().forEach(v -> Failure.require(v==null || Double.isFinite(v) && v>=0,"TREND_INVALID","Invalid metric value"));}
    }
    private static class SourceSet extends TreeMap<String,String> {long bytes;}
    public record Document(Metric metric,String checksum) {}
    public record Percentiles(int samples,Double p50,Double p95) {}
    private TrendReports() {}
    public static void automatic(Path root) {
        if(!List.of("suite-report.json","case-report.json","run-report.json","release-report.json").stream().anyMatch(name -> Files.isRegularFile(root.resolve(name)))) return;
        try {capture(root,50);}catch(RuntimeException failure) {
            try {Json.write(root.resolve("trend-error.json"),Map.of("status","UNAVAILABLE","code",failure instanceof Failure f?f.code():"TREND_INVALID","observational",true));}catch(RuntimeException ignored) {}
        }
    }
    public static Map<String,Object> capture(Path root,int window) {
        Failure.require(window>=1 && window<=500,"TREND_INVALID","Trend window must be 1..500 runs");
        root=root.toAbsolutePath().normalize();AllureReports.verify(root);
        Metric current=metric(root);Json.write(root.resolve("run-metrics.json"),new Document(current,Json.hash(current)));
        List<Metric> candidates=new ArrayList<>();candidates.add(current);int excluded=0;Path parent=root.getParent();
        try(var paths=Files.list(parent)) {
            var directories=paths.filter(p -> !p.equals(rootPath(current,parent)) && !Files.isSymbolicLink(p) && Files.isDirectory(p) && Files.isRegularFile(p.resolve("run-metrics.json"))).limit(MAX_HISTORY+1L).toList();
            Failure.require(directories.size()<=MAX_HISTORY,"TREND_INVALID","Trend history exceeds 1000 indexed runs; use a narrower report archive");
            for(Path directory:directories) try {
                Metric value=read(directory);
                if(current.comparable() && value.comparable() && current.cohort().equals(value.cohort()) && !value.completedAt().isAfter(current.completedAt())) candidates.add(value);else excluded++;
            }catch(RuntimeException invalid) {excluded++;}
        }catch(java.io.IOException e) {throw new Failure("TREND_INVALID","Cannot scan trend history");}
        candidates.sort(Comparator.comparing(Metric::completedAt).reversed().thenComparing(Metric::runId));
        var history=candidates.stream().limit(window).sorted(Comparator.comparing(Metric::completedAt).thenComparing(Metric::runId)).toList();
        Set<String> keys=new TreeSet<>();history.forEach(m -> keys.addAll(m.values().keySet()));Map<String,Percentiles> stats=new TreeMap<>(),passedStats=new TreeMap<>();
        for(String key:keys) {stats.put(key,percentiles(history.stream().map(m -> m.values().get(key)).filter(Objects::nonNull).toList()));passedStats.put(key,percentiles(history.stream().filter(m -> m.status().equals("PASSED")).map(m -> m.values().get(key)).filter(Objects::nonNull).toList()));}
        Map<String,Object> report=new LinkedHashMap<>();report.put("schemaVersion",1);report.put("runId",current.runId());report.put("cohort",current.cohort());report.put("comparable",current.comparable());report.put("window",window);report.put("samples",history.size());report.put("excludedRuns",excluded);report.put("history",history);report.put("allOutcomePercentiles",stats);report.put("passedRunPercentiles",passedStats);report.put("quantileMethod","nearest-rank");report.put("observational",true);
        Json.write(root.resolve("trend-summary.json"),report);writeHtml(root,report);return Collections.unmodifiableMap(report);
    }
    private static Path rootPath(Metric metric,Path parent) {return parent.resolve(metric.runId());}
    public static Percentiles percentiles(List<Double> values) {
        Failure.require(values.stream().allMatch(v -> v!=null && Double.isFinite(v) && v>=0),"TREND_INVALID","Trend samples must be finite and nonnegative");
        var sorted=values.stream().sorted().toList();return sorted.isEmpty()?new Percentiles(0,null,null):new Percentiles(sorted.size(),sorted.get(Math.max(0,(int)Math.ceil(.50*sorted.size())-1)),sorted.get(Math.max(0,(int)Math.ceil(.95*sorted.size())-1)));
    }
    public static Metric read(Path root) {
        var document=readJson(root.resolve("run-metrics.json"),Document.class,1024*1024);Metric metric=document.metric();
        Failure.require(metric!=null && Json.hash(metric).equals(document.checksum()) && metric.schemaVersion()==1 && root.getFileName().toString().equals(metric.runId()) && metric.cohort()!=null && metric.cohort().matches("[a-f0-9]{64}") && metric.sources()!=null && !metric.sources().isEmpty() && metric.sources().size()<=505,"TREND_INVALID","Invalid metric identity/checksum");
        metric.values().values().forEach(v -> Failure.require(v==null || Double.isFinite(v) && v>=0,"TREND_INVALID","Invalid metric value"));
        long total=0;for(var source:metric.sources().entrySet()) {byte[] bytes=ownedBytes(root,source.getKey(),MAX_FILE);total+=bytes.length;Failure.require(total<=64L*1024*1024 && Json.hash(bytes).equals(source.getValue()),"TREND_INVALID","Historical source changed or exceeds its budget");}
        return metric;
    }
    @SuppressWarnings("unchecked") private static Metric metric(Path root) {
        String run=root.getFileName().toString(),kind,status,label,context=null,durationBasis="wall-clock";Instant started=null,completed;List<Map<String,Object>> events=new ArrayList<>();Map<String,String> sources=new SourceSet();int passed=0,failed=0,skipped=0;long declaredCalls=0;
        if(Files.isRegularFile(root.resolve("suite-report.json"))) {
            SuiteReportExporter.verify(root);var state=source(root,"suite-report.json",sources);kind="suite";label=Objects.toString(state.get("suiteId"));status=Objects.toString(state.get("status"));context=(String)state.get("trendContext");started=instant(state.get("startedAt"));completed=instant(state.get("completedAt"));
            for(var row:(List<Map<String,Object>>)state.get("datasets")) {var child=source(root,row.get("directory")+"/case-report.json",sources);events.addAll(eventList(child));switch(child.get("status").toString()) {case "PASSED" -> passed++;case "SKIPPED","NOT_RUN_DEPENDENCY" -> skipped++;default -> failed++;}}
        }else if(Files.isRegularFile(root.resolve("case-report.json"))) {
            WorkflowReportExporter.verifyEvidence(root);var state=source(root,"case-report.json",sources);kind="workflow";label=Objects.toString(state.get("mode"))+"|"+state.get("caseId")+"|"+state.getOrDefault("datasetId","")+"|"+state.get("persona")+"|"+state.getOrDefault("trendScope",state.getOrDefault("recipeChecksum",""));status=state.get("status").toString();context=(String)state.get("trendContext");started=instant(state.get("startedAt"));completed=instant(state.get("completedAt"));events.addAll(eventList(state));declaredCalls=((Number)state.getOrDefault("modelCalls",0)).longValue();if(status.equals("PASSED"))passed++;else if(status.equals("SKIPPED"))skipped++;else failed++;
            label+="|"+state.getOrDefault("trendParametersHash","");
        }else if(Files.isRegularFile(root.resolve("run-report.json"))) {
            var state=source(root,"run-report.json",sources);var report=Json.MAPPER.convertValue(state,RunReport.class);ReportIntegrityVerifier.verify(root,report,0);kind="junit";label=Json.hash(report.selection());status=report.exitCode()==0?"PASSED":"FAILED";started=report.startedAt();completed=report.completedAt();
            if(Files.isRegularFile(root.resolve("trend-context.json"))) context=source(root,"trend-context.json",sources).get("contextHash").toString();
            for(var result:report.results()) {events.addAll(result.events());if(result.status().equals("PASSED"))passed++;else if(Set.of("SKIPPED","NOT_RUN_DEPENDENCY").contains(result.status()))skipped++;else failed++;}
        }else {
            var state=source(root,"release-report.json",sources);var layers=ExternalLayers.read(root);kind="release";label=Json.hash(layers.stream().map(l -> Map.of("id",l.id(),"type",l.type(),"target",l.provenance().target(),"environment",l.provenance().environment(),"release",l.provenance().release(),"runner",l.provenance().runner(),"runnerVersion",l.provenance().runnerVersion(),"tests",l.tests().stream().map(t -> t.className()+"|"+t.name()).sorted().toList())).toList());context=label;status=state.get("status").toString();completed=instant(state.get("completedAt"));long duration=0;
            for(var layer:layers) for(var test:layer.tests()) {duration+=test.durationMillis();if(test.status().equals("PASSED"))passed++;else if(test.status().equals("SKIPPED"))skipped++;else failed++;}
            started=completed.minusMillis(duration);
            durationBasis="test-duration-sum";
            if(Files.isRegularFile(root.resolve("trend-context.json"))) {context=source(root,"trend-context.json",sources).get("contextHash").toString();label="explicit-release-plan";}
            if(Files.isRegularFile(root.resolve("run-timing.json"))) {var timing=source(root,"run-timing.json",sources);started=instant(timing.get("startedAt"));completed=instant(timing.get("completedAt"));durationBasis="wall-clock";}
        }
        Failure.require(started!=null && completed!=null && !completed.isBefore(started) && passed+failed+skipped>0,"TREND_INVALID","Incomplete or empty runs cannot provide trends");
        var quality=QualityReports.summarize(events);var ai=(Map<String,Object>)quality.get("aiWorkflow");Map<String,Double> values=new TreeMap<>();values.put("durationMs",(double)Duration.between(started,completed).toMillis());values.put("locatorRetries",number(quality.get("locatorRetries")));values.put("healingEvents",number(quality.get("healingEvents")));double calls=Math.max(declaredCalls,Math.max(number(quality.get("modelCallAttempts")),number(ai.get("providerCalls"))));values.put("modelCalls",calls);
        boolean usageKnown=calls<=number(ai.get("providerCalls")) && number(ai.get("unknownUsageCalls"))==0;
        Double tokens=null,cost=null;
        if(calls==0) {tokens=0.0;cost=0.0;} else if(usageKnown) {tokens=number(ai.get("chargedTokens"));if(Boolean.TRUE.equals(ai.get("costRatesConfigured"))) cost=number(ai.get("estimatedCostUsd"));}
        values.put("chargedTokens",tokens);values.put("estimatedCostUsd",cost);
        ((Map<String,Object>)quality.get("durationNanosByPhase")).forEach((phase,time) -> values.put("phase:"+phase+":ms",number(time)/1_000_000));
        if(Files.isRegularFile(root.resolve("render-timing.json"))) {var render=source(root,"render-timing.json",sources);values.put("allureRenderMs",number(render.get("durationNanos"))/1_000_000);}
        boolean comparable=context!=null && context.matches("[a-f0-9]{64}");return new Metric(1,run,kind,Json.hash(Map.of("kind",kind,"label",label,"context",Objects.toString(context,"unknown"),"durationBasis",durationBasis)),comparable,started,completed,status,durationBasis,passed,failed,skipped,Collections.unmodifiableMap(values),Collections.unmodifiableMap(sources));
    }
    private static List<Map<String,Object>> eventList(Map<String,Object> state) {return Json.MAPPER.convertValue(state.getOrDefault("events",List.of()),new TypeReference<List<Map<String,Object>>>(){});}
    private static double number(Object value) {double result=value==null?0:value instanceof Number n?n.doubleValue():Double.parseDouble(value.toString());Failure.require(Double.isFinite(result) && result>=0,"TREND_INVALID","Invalid duration/count/cost metric");return result;}
    private static Instant instant(Object value) {return value==null?null:Instant.parse(value.toString());}
    private static Map<String,Object> source(Path root,String name,Map<String,String> sources) {byte[] bytes=ownedBytes(root,name,MAX_FILE);if(sources instanceof SourceSet set) {set.bytes+=bytes.length;Failure.require(set.bytes<=64L*1024*1024,"TREND_INVALID","Run sources exceed the trend budget");}sources.put(name,Json.hash(bytes));try {return Json.MAPPER.readValue(bytes,new TypeReference<Map<String,Object>>(){});}catch(Exception e) {throw new Failure("TREND_INVALID","Invalid source report");}}
    private static byte[] ownedBytes(Path root,String name,int limit) {try {Path file=SafePaths.inside(root,name);Failure.require(file.toRealPath().startsWith(root.toRealPath()),"TREND_INVALID","Trend source redirects outside the report");return bytes(file,limit);}catch(java.io.IOException e) {throw new Failure("TREND_INVALID","Cannot verify trend ownership");}}
    private static byte[] bytes(Path file,int limit) {try {Failure.require(Files.isRegularFile(file) && !Files.isSymbolicLink(file),"TREND_INVALID","Trend source must be a regular owned file");try(var input=Files.newInputStream(file)) {byte[] bytes=input.readNBytes(limit+1);Failure.require(bytes.length<=limit,"TREND_INVALID","Trend input exceeds its size limit");return bytes;}}catch(java.io.IOException e) {throw new Failure("TREND_INVALID","Cannot read trend input");}}
    private static <T> T readJson(Path file,Class<T> type,int limit) {try{return Json.MAPPER.readValue(bytes(file,limit),type);}catch(Failure f){throw f;}catch(Exception e){throw new Failure("TREND_INVALID","Invalid trend JSON");}}
    private static void writeHtml(Path root,Map<String,Object> report) {
        String data=Json.string(report).replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029");
        String html="""
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Run trends</title>
            <style>body{font:16px system-ui;background:#f6f8fc;color:#192940;margin:2rem}main{max-width:1100px;margin:auto}select{padding:.5rem}svg{background:white;width:100%;height:260px;border-radius:10px}table{border-collapse:collapse;width:100%;background:white}td,th{padding:.7rem;text-align:left;border-bottom:1px solid #dde4ef}.failed{color:#b91c1c}</style>
            <main><h1>Run trends</h1><p id="scope"></p><label>Metric <select id="metric"></select></label><p id="stats"></p><svg id="chart" viewBox="0 0 1000 260" role="img" aria-label="Metric over comparable completed runs"></svg><table><thead><tr><th>Run</th><th>Status</th><th>Value</th><th>Passed / failed / skipped</th></tr></thead><tbody id="rows"></tbody></table><p>Observational only. Unknown values are gaps. Percentiles use nearest rank; failed outcomes remain visible. Open <a href="index.html">Allure</a> for verdict evidence.</p></main>
            <script>const data=DATA;const choose=document.querySelector('#metric');for(const key of Object.keys(data.allOutcomePercentiles)){const o=document.createElement('option');o.value=key;o.textContent=key;choose.append(o)}document.querySelector('#scope').textContent=data.samples+' run(s), '+data.excludedRuns+' excluded; context '+(data.comparable?'verified':'unknown; no historical comparison');function draw(){const key=choose.value,s=data.allOutcomePercentiles[key],p=data.passedRunPercentiles[key];document.querySelector('#stats').textContent='All outcomes: n='+s.samples+' p50='+s.p50+' p95='+s.p95+' | Passed runs: n='+p.samples+' p50='+p.p50+' p95='+p.p95;const chart=document.querySelector('#chart'),rows=document.querySelector('#rows');chart.replaceChildren();rows.replaceChildren();const values=data.history.map(m=>m.values[key]??null),max=Math.max(1,...values.filter(v=>v!==null));let previous=null;data.history.forEach((m,i)=>{const v=values[i],x=30+i*940/Math.max(1,data.history.length-1),y=v===null?null:230-v/max*200;if(y!==null){if(previous){const line=document.createElementNS('http://www.w3.org/2000/svg','line');for(const [k,a] of Object.entries({x1:previous.x,y1:previous.y,x2:x,y2:y,stroke:'#2563eb','stroke-width':3}))line.setAttribute(k,a);chart.append(line)}const dot=document.createElementNS('http://www.w3.org/2000/svg','circle');for(const [k,a] of Object.entries({cx:x,cy:y,r:5,fill:m.status==='PASSED'?'#16a34a':'#dc2626'}))dot.setAttribute(k,a);chart.append(dot);previous={x,y}}else previous=null;const tr=document.createElement('tr');for(const value of [m.runId,m.status,v===null?'unknown':v,m.passed+' / '+m.failed+' / '+m.skipped]){const td=document.createElement('td');td.textContent=value;tr.append(td)}if(m.status!=='PASSED')tr.className='failed';rows.append(tr)})}choose.onchange=draw;draw();</script></html>
            """.replace("DATA",data);
        try {Files.writeString(root.resolve("trends.html"),html);}catch(java.io.IOException e) {throw new Failure("TREND_INVALID","Cannot publish trend dashboard");}
    }
}
