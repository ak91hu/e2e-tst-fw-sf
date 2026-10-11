package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
public final class ReportExporter {
    private ReportExporter() {}
    public static void write(Path root,RunReport report) {
        Json.write(root.resolve("run-report.json"),report);
        HtmlSummary.write(root,report.runId(),report.exitCode()==0?"PASSED":"FAILED",report.results().stream().map(r -> Map.of("case",r.caseInfo(),"status",r.status(),"businessVerdict",r.businessVerdict(),"evidenceVerdict",r.evidenceVerdict(),"durationMillis",r.durationMillis(),"code",Objects.toString(r.errorCode(),""),"diagnostic",Objects.toString(r.errorMessage(),""),"secondaryErrors",r.secondaryErrors(),"maskedScreenshots",r.artifacts().size())).toList(),"Final verdict includes user and container teardown, dependency outcomes, browser quality and evidence integrity. Full details and masked screenshots are in the Allure report.");
        Json.write(root.resolve("completion.json"),Map.of("runId",report.runId(),"complete",report.complete(),"selected",report.selection().size(),"completed",report.results().size(),"counts",report.counts(),"exitCode",report.exitCode()));
        StringBuilder md=new StringBuilder("# Salesforce E2E run "+report.runId()+"\n\nSelected: "+report.selection().size()+"; completed: "+report.results().size()+"; exit code: "+report.exitCode()+"\n\n| Case | Result | Evidence |\n| --- | --- | --- |\n");
        StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"Salesforce E2E\" id=\""+escape(report.runId())+"\" tests=\""+report.results().size()+"\" failures=\""+report.results().stream().filter(r->r.status().equals("FAILED")).count()+"\" errors=\""+report.results().stream().filter(r->Set.of("BLOCKED","EVIDENCE_FAILED").contains(r.status())).count()+"\" skipped=\""+report.results().stream().filter(r->Set.of("SKIPPED","CANCELLED","NOT_RUN_DEPENDENCY").contains(r.status())).count()+"\">\n");
        StringBuilder html=new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Salesforce E2E report</title><style>body{font:16px system-ui;margin:2rem;max-width:1100px}table{border-collapse:collapse;width:100%}th,td{padding:.6rem;border-bottom:1px solid #ddd;text-align:left}.PASSED{color:#166534}.FAILED,.BLOCKED,.EVIDENCE_FAILED{color:#b91c1c}img{max-width:600px}</style><h1>Salesforce E2E "+escape(report.runId())+"</h1><p>Selected "+report.selection().size()+"; completed "+report.results().size()+"; exit "+report.exitCode()+"</p><table><tr><th>Case</th><th>Result</th><th>Artifacts and retained records</th></tr>");
        for(var result:report.results()) {
            md.append("| ").append(result.caseInfo().id()).append(" | ").append(result.status()).append(" | ").append(result.artifacts().size()).append(" PNG |\n");
            xml.append("<testcase classname=\"").append(escape(result.caseInfo().source())).append("\" name=\"").append(escape(result.caseInfo().id()+" | "+result.caseInfo().name())).append("\" time=\"").append(result.durationMillis()/1000.0).append("\">");
            String message=escape(Objects.toString(result.errorMessage(),""));
            if(result.status().equals("FAILED")) xml.append("<failure type=\"").append(escape(Objects.toString(result.errorCode(),"UI_ASSERTION_FAILED"))).append("\" message=\"").append(message).append("\"/>");
            else if(Set.of("BLOCKED","EVIDENCE_FAILED").contains(result.status())) xml.append("<error type=\"").append(escape(Objects.toString(result.errorCode(),"BLOCKED"))).append("\" message=\"").append(message).append("\"/>");
            else if(!result.status().equals("PASSED")) xml.append("<skipped message=\"").append(message).append("\"/>");
            xml.append("</testcase>\n");
            html.append("<tr><td>").append(escape(result.caseInfo().id()+" | "+result.caseInfo().name())).append("</td><td class=\"").append(result.status()).append("\">").append(result.status()).append("<p>").append(message).append("</p></td><td>");
            List<Map<String,String>> attachments=new ArrayList<>();
            attachments.add(QualityReports.attach(root,result.attemptId(),result.events()));
            String eventSource=result.attemptId()+"-events.json";Json.write(root.resolve("allure-results").resolve(eventSource),result.events());
            attachments.add(Map.of("name","Redacted event log","type","application/json","source",eventSource));
            Path allure=SafePaths.inside(root,"allure-results");
            try { Files.createDirectories(allure); } catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot create Allure results"); }
            for(var artifact:result.artifacts()) {
                html.append("<a href=\"").append(escape(artifact.path())).append("\">").append(escape(artifact.stage())).append("</a><br>");
                String attachment=artifact.evidenceId()+"-attachment.png";
                try { Files.copy(SafePaths.inside(root,artifact.path()),SafePaths.inside(allure,attachment),StandardCopyOption.REPLACE_EXISTING); }
                catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot copy verified Allure attachment"); }
                attachments.add(Map.of("name",artifact.stage(),"source",attachment,"type","image/png"));
            }
            List<Map<String,String>> links=new ArrayList<>();
            if(result.retainedRecords()!=null) for(var record:result.retainedRecords().records()) if(record.url!=null) {
                String name=record.objectType+" "+Objects.toString(record.displayName,record.currentName);
                html.append("<a href=\"").append(escape(record.url)).append("\">").append(escape(name)).append("</a><br>");
                links.add(Map.of("name",name,"url",record.url,"type","record"));
            }
            html.append("</td></tr>");
            String status=switch(result.status()) { case "PASSED"->"passed";case "FAILED"->"failed";case "BLOCKED","EVIDENCE_FAILED"->"broken";default->"skipped"; };
            Map<String,Object> allureResult=new LinkedHashMap<>();
            allureResult.put("uuid",result.attemptId());allureResult.put("historyId",Json.hash(result.caseInfo().id()));
            allureResult.put("testCaseId",result.caseInfo().id());allureResult.put("name",result.caseInfo().id()+" | "+result.caseInfo().name());
            allureResult.put("fullName",result.caseInfo().source()+"."+result.caseInfo().id());allureResult.put("status",status);allureResult.put("stage","finished");
            allureResult.put("statusDetails",Map.of("message",Objects.toString(result.errorMessage(),"")));allureResult.put("attachments",attachments);allureResult.put("links",links);
            allureResult.put("start",result.startedAt()==null?(result.completedAt()==null?0:result.completedAt().toEpochMilli()):result.startedAt().toEpochMilli());allureResult.put("stop",result.completedAt()==null?0:result.completedAt().toEpochMilli());
            List<Map<String,String>> labels=new ArrayList<>(List.of(Map.of("name","framework","value","sf-agentic-e2e-java"),Map.of("name","language","value","java"),Map.of("name","suite","value",report.runId())));
            for(String tag:result.caseInfo().tags()) labels.add(Map.of("name","tag","value",tag));
            allureResult.put("labels",labels);
            allureResult.put("steps",AllureResults.steps(result.events(),result.artifacts(),result.status(),result.errorMessage()));
            Json.write(allure.resolve(result.attemptId()+"-result.json"),allureResult);
        }
        xml.append("</testsuite>");html.append("</table></html>");
        writeText(root.resolve("summary.md"),md.toString());writeText(root.resolve("junit.xml"),xml.toString());writeText(root.resolve("summary.html"),html.toString());
        Json.write(root.resolve("quality-summary.json"),QualityReports.summarize(report.results().stream().flatMap(r -> r.events().stream()).toList()));
        HtmlSummary.render(root);
    }
    private static String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
    private static void writeText(Path path,String content) {
        try { Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,content,StandardCharsets.UTF_8); }
        catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Report export failed"); }
    }
}
