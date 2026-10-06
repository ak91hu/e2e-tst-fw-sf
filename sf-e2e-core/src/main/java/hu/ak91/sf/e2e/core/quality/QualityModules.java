package hu.ak91.sf.e2e.core.quality;

import hu.ak91.sf.e2e.core.*;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Independent quality verdicts; baseline creation is always an explicit review action. */
public final class QualityModules {
    public record Environment(String browser,int width,int height,String locale,String fontsHash,String dataHash) {
        public Environment { Failure.require(browser!=null && width>0 && height>0 && locale!=null && fontsHash!=null && fontsHash.matches("[a-f0-9]{64}") && dataHash!=null && dataHash.matches("[a-f0-9]{64}"),"CONFIG_INVALID","Visual environment requires browser, viewport, locale, font/data fingerprints"); }
    }
    public record VisualBaseline(int schemaVersion,Environment environment,String pngHash) {}
    public record Waiver(String rule,String targetHash,String reason,LocalDate expires) {
        public Waiver { Failure.require(rule!=null && !rule.isBlank() && targetHash!=null && targetHash.matches("[a-f0-9]{64}") && reason!=null && reason.length()>=10 && expires!=null,"CONFIG_INVALID","Quality waivers require exact rule/target, reason and expiry"); }
    }
    private QualityModules() {}
    public static void approveBaseline(Path png,byte[] maskedPng,Environment env) {
        Failure.require(!Files.exists(png) && !Files.exists(Path.of(png+".json")),"QUALITY_BASELINE_EXISTS","Baseline approval cannot overwrite an existing baseline");
        image(maskedPng);try { Files.createDirectories(png.toAbsolutePath().getParent());Files.write(png,maskedPng,StandardOpenOption.CREATE_NEW);Json.write(Path.of(png+".json"),new VisualBaseline(1,env,Json.hash(maskedPng))); } catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Cannot persist reviewed visual baseline"); }
    }
    public static double visual(Path png,byte[] actual,Environment env,int channelTolerance,double maximumChangedRatio) {
        Failure.require(channelTolerance>=0 && channelTolerance<=255 && Double.isFinite(maximumChangedRatio) && maximumChangedRatio>=0 && maximumChangedRatio<=1,"CONFIG_INVALID","Invalid visual thresholds");
        try { byte[] expected=Files.readAllBytes(png);var baseline=Json.read(Path.of(png+".json"),VisualBaseline.class);Failure.require(baseline.schemaVersion()==1 && env.equals(baseline.environment()) && Json.hash(expected).equals(baseline.pngHash()),"QUALITY_BASELINE_MISMATCH","Visual environment or approved baseline integrity changed");
            var a=image(actual);var b=image(expected);Failure.require(a.getWidth()==b.getWidth() && a.getHeight()==b.getHeight(),"QUALITY_GATE_FAILED","Visual viewport dimensions differ");long changed=0,total=(long)a.getWidth()*a.getHeight();
            for(int y=0;y<a.getHeight();y++) for(int x=0;x<a.getWidth();x++) { int first=a.getRGB(x,y),second=b.getRGB(x,y);for(int shift:new int[]{0,8,16,24}) if(Math.abs((first>>>shift&255)-(second>>>shift&255))>channelTolerance) { changed++;break; } }
            double ratio=(double)changed/total;Failure.require(ratio<=maximumChangedRatio,"QUALITY_GATE_FAILED","Visual difference exceeded its reviewed threshold");return ratio;
        } catch(IOException e) { throw new Failure("EVIDENCE_INCOMPLETE","Visual baseline is missing or unreadable"); }
    }
    private static java.awt.image.BufferedImage image(byte[] png) { try { Failure.require(png.length<=32*1024*1024,"PNG_INVALID","Quality image exceeds 32 MiB");var image=ImageIO.read(new ByteArrayInputStream(png));Failure.require(image!=null && (long)image.getWidth()*image.getHeight()<=16000000,"PNG_INVALID","Invalid or oversized quality image");return image; } catch(IOException e) { throw new Failure("PNG_INVALID","Quality image cannot be decoded"); } }
    public static void performance(Map<String,Double> measured,Map<String,Double> maximum) {
        Failure.require(maximum!=null && !maximum.isEmpty() && maximum.values().stream().allMatch(v -> v!=null && Double.isFinite(v) && v>=0),"CONFIG_INVALID","Performance requires explicit finite budgets");
        for(var entry:maximum.entrySet()) Failure.require(measured.containsKey(entry.getKey()) && Double.isFinite(measured.get(entry.getKey())) && measured.get(entry.getKey())>=0 && measured.get(entry.getKey())<=entry.getValue(),"QUALITY_GATE_FAILED","Missing or exceeded performance metric: "+entry.getKey());
    }
    /** axe output contains only exact target hashes/rule IDs, never DOM HTML or unredacted node text. */
    public static void accessibility(tools.jackson.databind.JsonNode result,Set<String> failImpacts,List<Waiver> waivers,Clock clock) {
        Failure.require(result.path("violations").isArray() && result.path("incomplete").isArray(),"QUALITY_GATE_FAILED","Accessibility audit did not complete");
        Failure.require(failImpacts!=null && !failImpacts.isEmpty() && Set.of("minor","moderate","serious","critical").containsAll(failImpacts),"CONFIG_INVALID","Declare accessibility severity thresholds");
        for(var violation:result.path("violations")) if(failImpacts.contains(violation.path("impact").asString())) for(var node:violation.path("nodes")) {
            String rule=violation.path("id").asString(),hash=node.path("targetHash").asString();
            boolean waived=waivers.stream().anyMatch(w -> w.rule().equals(rule) && w.targetHash().equals(hash) && !LocalDate.now(clock).isAfter(w.expires()));Failure.require(waived,"QUALITY_GATE_FAILED","Accessibility rule failed: "+rule+" target "+hash);
        }
        Failure.require(result.path("incomplete").isEmpty(),"QUALITY_REVIEW_REQUIRED","Accessibility audit has incomplete checks requiring review");
    }
}
