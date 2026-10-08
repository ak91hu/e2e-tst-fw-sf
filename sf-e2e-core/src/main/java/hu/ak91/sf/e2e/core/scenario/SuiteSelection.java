package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.*;
import java.util.*;

/** Dependency-complete failure selection and stable shards; a shard never splits a dependency component. */
public final class SuiteSelection {
    private SuiteSelection() {}
    /** New receipt hashes normalize unordered resource sets without changing persisted recipe/checksum contracts. */
    public static tools.jackson.databind.JsonNode rowSnapshot(CaseSuite.Row row) {
        var node=(tools.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(row);node.set("resources",Json.MAPPER.valueToTree(row.resources().stream().sorted().toList()));return node;
    }
    public static String sourceHash(CaseSuite suite) {
        var node=(tools.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(suite);node.set("cases",Json.MAPPER.valueToTree(suite.cases().stream().map(SuiteSelection::rowSnapshot).toList()));return Json.hash(node);
    }
    public static List<CaseSuite.Row> failed(CaseSuite suite,String sourceHash,Map<String,String> outcomes) {
        Failure.require(sourceHash(suite).equals(sourceHash),"SELECTION_STALE","Failure receipt belongs to a different suite manifest");
        Map<String,CaseSuite.Row> all=new LinkedHashMap<>();suite.cases().forEach(r -> all.put(r.id(),r));
        Failure.require(!outcomes.isEmpty() && all.keySet().containsAll(outcomes.keySet()),"CASE_INVALID","Failure receipt contains unknown datasets");
        Set<String> selected=new HashSet<>();
        outcomes.forEach((id,status) -> {
            Failure.require(Set.of("PASSED","FAILED","BLOCKED","SKIPPED","CANCELLED").contains(status),"CASE_INVALID","Invalid recorded dataset outcome");
            if(!status.equals("PASSED")) closure(id,all,selected);
        });
        Failure.require(!selected.isEmpty(),"NO_TESTS","The recorded selection has no unsuccessful datasets");
        return suite.cases().stream().filter(r -> selected.contains(r.id())).toList();
    }
    private static void closure(String id,Map<String,CaseSuite.Row> all,Set<String> selected) {
        if(selected.add(id)) all.get(id).after().forEach(dep -> closure(dep,all,selected));
    }
    public static List<CaseSuite.Row> shard(List<CaseSuite.Row> rows,String shard) {
        if(shard==null) return rows;
        Failure.require(shard.matches("[1-9][0-9]{0,2}/[1-9][0-9]{0,2}"),"CASE_INVALID","Shard must use one-based index/count, for example 1/3");
        String[] parts=shard.split("/");int index=Integer.parseInt(parts[0]),count=Integer.parseInt(parts[1]);
        Failure.require(index<=count && count<=500,"CASE_INVALID","Shard index/count must satisfy 1 <= index <= count <= 500");
        Map<String,String> parents=new TreeMap<>();rows.forEach(r -> parents.put(r.id(),r.id()));
        for(var row:rows) for(String dep:row.after()) {
            Failure.require(parents.containsKey(dep),"CASE_INVALID","Select dependency closure before sharding");
            String a=find(parents,row.id()),b=find(parents,dep);parents.put(a.compareTo(b)<0?b:a,a.compareTo(b)<0?a:b);
        }
        var components=parents.keySet().stream().map(id -> find(parents,id)).distinct().sorted().toList();
        Set<String> owned=new HashSet<>();for(int i=0;i<components.size();i++) if(i%count==index-1) owned.add(components.get(i));
        var selected=rows.stream().filter(r -> owned.contains(find(parents,r.id()))).toList();
        Failure.require(!selected.isEmpty(),"NO_TESTS","Shard has no dependency components; no successful empty run is created");return selected;
    }
    private static String find(Map<String,String> parents,String id) { String p=parents.get(id);return p.equals(id)?id:find(parents,p); }
}
