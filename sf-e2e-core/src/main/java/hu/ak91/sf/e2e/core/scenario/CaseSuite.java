package hu.ak91.sf.e2e.core.scenario;

import hu.ak91.sf.e2e.core.*;
import java.nio.file.*;
import java.util.*;

/** User-owned dataset manifest. Values stay in private parameter files, outside exported reports. */
public record CaseSuite(int schemaVersion,String id,List<Row> cases) {
    public record Row(String id,String recipe,String params,String persona,List<String> tags,List<String> after,Set<String> resources) {
        public Row(String id,String recipe,String params,String persona,List<String> tags) { this(id,recipe,params,persona,tags,List.of(),Set.of()); }
        public Row {
            SafePaths.segment(id);Failure.require(id.length()<=70 && recipe!=null && !recipe.isBlank() && recipe.length()<=1000 &&
                (params==null || !params.isBlank() && params.length()<=1000),"CASE_INVALID","Dataset requires a recipe and optional parameter-file path");
            persona=persona==null?"default":persona;Persona.valueOf(persona);tags=tags==null?List.of():List.copyOf(tags);
            Failure.require(tags.size()<=20 && tags.stream().allMatch(t -> t!=null && t.matches("[A-Za-z0-9_.-]{1,70}")),"CASE_INVALID","Invalid dataset tags");
            after=after==null?List.of():List.copyOf(after);resources=resources==null?Set.of():Set.copyOf(resources);
            Failure.require(after.size()<=500 && resources.size()<=20 && after.stream().allMatch(s -> s!=null && s.matches("[A-Za-z0-9_.-]{1,70}")) && resources.stream().allMatch(s -> s!=null && s.matches("[A-Za-z0-9_.:-]{1,150}")),"CASE_INVALID","Invalid dataset dependencies or resource locks");
        }
    }
    public CaseSuite {
        SafePaths.segment(id);Failure.require(schemaVersion==1 && cases!=null && !cases.isEmpty() && cases.size()<=500 &&
            cases.stream().allMatch(Objects::nonNull) && cases.stream().map(Row::id).distinct().count()==cases.size(),"CASE_INVALID","Suite requires 1..500 uniquely identified datasets");
        cases=List.copyOf(cases);
        DatasetScheduler.validate(cases.stream().map(r -> new DatasetScheduler.Task<>(r.id(),r.after(),r.resources(),r)).toList());
    }
    public static CaseSuite load(Path file) {
        try { Failure.require(Files.isRegularFile(file) && Files.size(file)<=1024*1024,"CASE_INVALID","Suite must be a JSON file of at most 1 MiB");return Json.read(file,CaseSuite.class); }
        catch(Failure f) { throw f; } catch(Exception e) { throw new Failure("CASE_INVALID","Invalid suite manifest"); }
    }
    public List<Row> select(Set<String> tags,Set<String> datasets) {
        var selected=cases.stream().filter(r -> tags.isEmpty() || r.tags().stream().anyMatch(tags::contains)).filter(r -> datasets.isEmpty() || datasets.contains(r.id())).toList();
        Failure.require(datasets.isEmpty() || cases.stream().map(Row::id).toList().containsAll(datasets),"CASE_INVALID","A requested dataset ID does not exist");
        Failure.require(!selected.isEmpty(),"NO_TESTS","Suite selection is empty; no successful run can be reported");return selected;
    }
}
