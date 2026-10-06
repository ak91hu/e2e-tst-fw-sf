package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Secure JUnit interchange for Apex, Jest, API and mobile runners; no layer can overwrite another verdict. */
public final class ExternalLayers {
    public record Provenance(String target,String environment,String release,String runner,String runnerVersion,String commit,String metadataHash) {
        public Provenance { SafePaths.segment(target);Failure.require(Set.of("preview-sandbox","production-sandbox","scratch","component","device").contains(environment) && release!=null && !release.isBlank() && runner!=null && !runner.isBlank() && runnerVersion!=null && !runnerVersion.isBlank() && commit!=null && !commit.isBlank() && metadataHash!=null && metadataHash.matches("[a-f0-9]{64}"),"CONFIG_INVALID","External layers require explicit target, release, runner/version, commit and metadata provenance"); }
    }
    public record Test(String name,String className,String status,long durationMillis) {}
    public record Layer(String id,String type,Provenance provenance,String inputChecksum,List<Test> tests,String status) {}
    private ExternalLayers() {}
    public static Layer junit(Path file,String id,String type,Provenance provenance,SecretRegistry secrets) {
        SafePaths.segment(id);Failure.require(Set.of("apex","jest","api","mobile","web").contains(type),"CONFIG_INVALID","Unknown external test layer");
        try {
            Failure.require(Files.isRegularFile(file) && Files.size(file)<=8*1024*1024,"RESULT_IMPORT_INVALID","JUnit results must be a bounded regular file");byte[] bytes=Files.readAllBytes(file);
            var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() { @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; } });
            var document=builder.parse(new java.io.ByteArrayInputStream(bytes));var root=document.getDocumentElement();Failure.require(Set.of("testsuite","testsuites").contains(root.getTagName()),"RESULT_IMPORT_INVALID","Expected JUnit testsuite root");
            var nodes=root.getElementsByTagName("testcase");Failure.require(nodes.getLength()>0 && nodes.getLength()<=100000,"NO_TESTS","Empty or oversized external suite cannot pass");List<Test> tests=new ArrayList<>();Set<String> identities=new HashSet<>();
            for(int i=0;i<nodes.getLength();i++) {
                var test=(org.w3c.dom.Element)nodes.item(i);String name=test.getAttribute("name"),className=test.getAttribute("classname");Failure.require(!name.isBlank() && name.length()<=1000 && className.length()<=1000 && identities.add(className+"|"+name),"RESULT_IMPORT_INVALID","Test identities are missing or duplicated");
                String status=test.getElementsByTagName("failure").getLength()>0 || test.getElementsByTagName("error").getLength()>0?"FAILED":test.getElementsByTagName("skipped").getLength()>0?"SKIPPED":"PASSED";
                double seconds=test.hasAttribute("time")?Double.parseDouble(test.getAttribute("time")):0;Failure.require(Double.isFinite(seconds) && seconds>=0 && seconds<=86400,"RESULT_IMPORT_INVALID","Invalid external test duration");tests.add(new Test(secrets.redact(name),secrets.redact(className),status,(long)(seconds*1000)));
            }
            if(root.hasAttribute("tests")) Failure.require(Integer.parseInt(root.getAttribute("tests"))==tests.size(),"RESULT_IMPORT_INVALID","JUnit declared test count differs from actual results");
            String status=tests.stream().allMatch(t -> t.status().equals("PASSED"))?"PASSED":"FAILED";
            return new Layer(id,type,provenance,Json.hash(bytes),List.copyOf(tests),status);
        } catch(Failure e) { throw e; } catch(Exception e) { throw new Failure("RESULT_IMPORT_INVALID","Malformed or unsafe external JUnit results"); }
    }
    public static boolean publish(Path root,List<Layer> layers) {
        Failure.require(!layers.isEmpty() && layers.stream().map(l -> l.provenance().target()+"|"+l.id()).distinct().count()==layers.size(),"RESULT_IMPORT_INVALID","Release results need unique target/layer identities");
        Path results=root.resolve("allure-results");boolean passed=layers.stream().allMatch(l -> l.status().equals("PASSED"));
        for(var layer:layers) for(var test:layer.tests()) {
            String identity=layer.provenance().target()+"|"+layer.id()+"|"+test.className()+"|"+test.name();String uuid=UUID.randomUUID().toString();
            Map<String,Object> result=new LinkedHashMap<>();result.put("uuid",uuid);result.put("historyId",Json.hash(identity));result.put("testCaseId",identity);result.put("name",test.name());result.put("fullName",test.className()+"."+test.name());result.put("status",test.status().equals("PASSED")?"passed":test.status().equals("SKIPPED")?"skipped":test.status().equals("BLOCKED")?"broken":"failed");result.put("stage","finished");result.put("start",0);result.put("stop",test.durationMillis());result.put("labels",List.of(Map.of("name","parentSuite","value",layer.provenance().target()),Map.of("name","suite","value",layer.type()),Map.of("name","subSuite","value",layer.id()),Map.of("name","framework","value",layer.provenance().runner())));result.put("parameters",List.of(Map.of("name","release","value",layer.provenance().release()),Map.of("name","runnerVersion","value",layer.provenance().runnerVersion()),Map.of("name","inputChecksum","value",layer.inputChecksum())));Json.write(results.resolve(uuid+"-result.json"),result);
        }
        Json.write(root.resolve("release-report.json"),Map.of("schemaVersion",1,"completedAt",Instant.now(),"status",passed?"PASSED":"FAILED","layers",layers,"modelCalls",0));AllureReports.generate(root);return passed;
    }
    public static List<Layer> read(Path root) {
        var report=Json.read(root.resolve("release-report.json"),Map.class);var layers=Json.MAPPER.convertValue(report.get("layers"),new tools.jackson.core.type.TypeReference<List<Layer>>(){});
        Failure.require(((Number)report.getOrDefault("schemaVersion",0)).intValue()==1 && report.get("completedAt")!=null && !layers.isEmpty() && layers.stream().allMatch(l -> !l.tests().isEmpty() && l.status().equals(l.tests().stream().allMatch(t -> t.status().equals("PASSED"))?"PASSED":"FAILED")) && Objects.equals(report.get("status"),layers.stream().allMatch(l -> l.status().equals("PASSED"))?"PASSED":"FAILED"),"REPORT_RUN_MISMATCH","Release report has inconsistent layer verdicts");return layers;
    }
    public static void verify(Path root) { read(root);AllureReports.verify(root); }
}
