package hu.ak91.sf.e2e.reporting;

import hu.ak91.sf.e2e.core.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** The real, bundled Allure CLI renders offline reports. No shell, server, model or runtime download. */
public final class AllureReports {
    public static final String VERSION="3.20.1";
    private static final String RESOURCE="/allure-engine/allure-commandline.zip";
    private static Path cachedHome;
    private AllureReports() {}
    public static Path generate(Path root) {
        long timingStart=System.nanoTime();
        Path absolute=root.toAbsolutePath().normalize(),results=absolute.resolve("allure-results");
        Failure.require(Files.isDirectory(results),"REPORT_GENERATION_FAILED","Allure result directory is missing");
        Path home=engineHome(),output=absolute.resolve("allure-report"),log=absolute.resolve("allure-generation.log");
        Path temporary=absolute.resolve(".allure-render-"+UUID.randomUUID());Process process=null;
        try {
            Files.createDirectories(temporary);
            Path config=temporary.resolve("allurerc.json");
            Json.write(config,Map.of("name","Salesforce automation","open",false,"plugins",Map.of("awesome",Map.of("options",Map.of("singleFile",true,"reportLanguage","en")))));
            String node=System.getProperty("sf.e2e.nodeExecutable","node"),nodeVersion=nodeVersion(node);
            var builder=new ProcessBuilder(node,home.resolve("node_modules/allure/cli.js").toString(),"generate",results.toString(),
                "--config",config.toString(),"--output",temporary.resolve("html").toString());
            // Explicit static config and a private working directory prevent host-project plugin discovery.
            builder.directory(home.toFile());
            builder.environment().keySet().removeIf(key -> key.matches("(?i)^(SF_|SFDX_|SALESFORCE_).*|.*(TOKEN|PASSWORD|SECRET|PRIVATE_KEY|API_KEY|CLIENT_ID|USERNAME)$"));
            builder.redirectErrorStream(true).redirectOutput(log.toFile());process=builder.start();
            if(!process.waitFor(120,TimeUnit.SECONDS)) {
                process.destroyForcibly();throw new Failure("REPORT_GENERATION_FAILED","Allure generation exceeded 120 seconds; raw results were preserved");
            }
            Path generated=temporary.resolve("html/index.html");
            Failure.require(process.exitValue()==0 && Files.isRegularFile(generated) && Files.size(generated)>1000,
                "REPORT_GENERATION_FAILED","Allure could not generate HTML; inspect allure-generation.log (raw results preserved)");
            makeOffline(generated);
            Files.createDirectories(output);publish(generated,output.resolve("index.html"));publish(generated,absolute.resolve("index.html"));
            Json.write(absolute.resolve("allure-generation.json"),Map.of("engine","Allure","version",VERSION,"nodeVersion",nodeVersion,"status","PASSED",
                "generatedAt",Instant.now(),"singleFile",true,"report","allure-report/index.html","sha256",Json.hash(Files.readAllBytes(generated))));
            verify(absolute);
            // Only this successfully published render is temporary. Failed renders/raw evidence remain intact.
            try { removePublishedTemporary(absolute,temporary); } catch(IOException ignored) { /* Published report remains valid; temporary output can be retained. */ }
            return output.resolve("index.html");
        } catch(Failure failure) { throw failure; }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new Failure("CANCELLED","Allure generation interrupted; raw results preserved"); }
        catch(IOException failure) { throw new Failure("REPORT_GENERATION_FAILED","Cannot launch or publish Allure report; raw results preserved"); }
        finally { if(process!=null && process.isAlive()) process.destroyForcibly();try { Json.write(absolute.resolve("render-timing.json"),Map.of("runId",absolute.getFileName().toString(),"phase","allure-render","durationNanos",Math.max(0,System.nanoTime()-timingStart),"completedAt",Instant.now())); }catch(RuntimeException ignored) { /* Timing cannot alter the report verdict. */ } TrendReports.automatic(absolute); }
    }
    private static void makeOffline(Path html) throws IOException {
        String content=Files.readString(html),head="<head>";
        Failure.require(content.contains(head),"REPORT_GENERATION_FAILED","Allure HTML has an unexpected layout; raw results were preserved");
        // Allure 3's upstream template enables analytics. Embedded reports must work privately offline.
        String policy="<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' blob: data:; style-src 'unsafe-inline' blob: data:; img-src blob: data:; font-src blob: data:; connect-src blob: data:; frame-src blob: data:; worker-src blob: data:; base-uri 'none'; form-action 'none'\">";
        Files.writeString(html,content.replaceFirst(java.util.regex.Pattern.quote(head),java.util.regex.Matcher.quoteReplacement(head+policy)));
    }
    private static String nodeVersion(String executable) throws IOException,InterruptedException {
        Process process;
        try { process=new ProcessBuilder(executable,"--version").redirectErrorStream(true).start(); }
        catch(IOException unavailable) { throw new Failure("REPORT_RUNTIME_UNAVAILABLE","Install Node.js 26.10.0 or later on PATH, or set sf.e2e.nodeExecutable; raw results were preserved"); }
        try {
            Failure.require(process.waitFor(10,TimeUnit.SECONDS),"REPORT_RUNTIME_UNAVAILABLE","Node.js version check timed out");
            String version=new String(process.getInputStream().readNBytes(128),java.nio.charset.StandardCharsets.UTF_8).strip();
            Failure.require(process.exitValue()==0 && version.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"),"REPORT_RUNTIME_UNAVAILABLE","Install Node.js 26.10.0 or later to render Allure 3");
            String[] parts=version.substring(1).split("\\.");int major=Integer.parseInt(parts[0]),minor=Integer.parseInt(parts[1]);
            Failure.require(major>26 || major==26 && minor>=10,"REPORT_RUNTIME_UNAVAILABLE","Allure 3 requires Node.js 26.10.0 or later");
            return version;
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    private static void publish(Path source,Path destination) throws IOException {
        Path staged=Files.createTempFile(destination.getParent(),".allure-publish-",".tmp");Files.copy(source,staged,StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException unsupported) { Files.move(staged,destination,StandardCopyOption.REPLACE_EXISTING); }
    }
    static void removePublishedTemporary(Path root,Path temporary) throws IOException {
        Path base=root.toAbsolutePath().normalize(),target=temporary.toAbsolutePath().normalize();
        Failure.require(target.getParent().equals(base) && target.getFileName().toString().matches("\\.allure-render-[0-9a-f-]{36}") &&
            !Files.isSymbolicLink(target) && target.toRealPath().getParent().equals(base.toRealPath()),"REPORT_GENERATION_FAILED","Unsafe temporary report directory");
        // walkFileTree does not follow symbolic links; links inside the temporary output are deleted as links.
        Files.walkFileTree(target,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException { Files.delete(file);return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException failure) throws IOException {
                if(failure!=null) throw failure;Files.delete(directory);return FileVisitResult.CONTINUE;
            }
        });
    }
    static synchronized Path engineHome() {
        if(cachedHome!=null && Files.isRegularFile(cachedHome.resolve("node_modules/allure/cli.js"))) return cachedHome;
        try(var input=AllureReports.class.getResourceAsStream(RESOURCE)) {
            Failure.require(input!=null,"REPORT_GENERATION_FAILED","Bundled Allure engine is missing; build with Maven generate-resources/package");
            byte[] archive=input.readAllBytes();String hash=Json.hash(archive);
            Path cache=Path.of(System.getProperty("user.home"),".sf-e2e","tools").toAbsolutePath().normalize();Files.createDirectories(cache);
            Path installation=cache.resolve("allure-"+VERSION+"-"+hash.substring(0,16)),home=installation;
            if(Files.isRegularFile(installation.resolve("installed.json"))) { cachedHome=home;return home; }
            try(var channel=FileChannel.open(cache.resolve("allure-install.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
                if(Files.isRegularFile(installation.resolve("installed.json"))) { cachedHome=home;return home; }
                Path staged=Files.createTempDirectory(cache,".allure-install-").toAbsolutePath().normalize();extract(archive,staged);
                Failure.require(Files.isRegularFile(staged.resolve("node_modules/allure/cli.js")) && Files.isRegularFile(staged.resolve("package-lock.json")),
                    "REPORT_GENERATION_FAILED","Allure archive has an unexpected layout");
                Json.write(staged.resolve("installed.json"),Map.of("version",VERSION,"sha256",hash));
                Failure.require(installation.startsWith(cache) && staged.startsWith(cache),"REPORT_GENERATION_FAILED","Invalid Allure cache destination");
                try { Files.move(staged,installation,StandardCopyOption.ATOMIC_MOVE); }
                catch(AtomicMoveNotSupportedException unsupported) { Files.move(staged,installation); }
                cachedHome=home;return home;
            }
        } catch(Failure failure) { throw failure; }
        catch(Exception failure) { throw new Failure("REPORT_GENERATION_FAILED","Cannot initialize bundled Allure engine"); }
    }
    public static void verify(Path root) {
        var metadata=Json.read(root.resolve("allure-generation.json"),Map.class);
        try {
            Failure.require("Allure".equals(metadata.get("engine")) && "PASSED".equals(metadata.get("status")) &&
                Objects.equals(metadata.get("sha256"),Json.hash(Files.readAllBytes(root.resolve("allure-report/index.html")))) &&
                Objects.equals(metadata.get("sha256"),Json.hash(Files.readAllBytes(root.resolve("index.html")))),
                "REPORT_RUN_MISMATCH","Allure HTML is missing, changed or does not match the recorded generation");
        } catch(Failure f) { throw f; } catch(IOException e) { throw new Failure("REPORT_RUN_MISMATCH","Allure HTML is missing or unreadable"); }
    }
    static void extract(byte[] archive,Path directory) throws IOException {
        Path root=directory.toAbsolutePath().normalize();long bytes=0;int entries=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                Path target=root.resolve(entry.getName()).normalize();
                Failure.require(target.startsWith(root) && !entry.getName().contains("\\") && ++entries<=10000,"REPORT_GENERATION_FAILED","Unsafe Allure archive path or entry count");
                if(entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    try(var file=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer=new byte[8192];int read;
                        while((read=zip.read(buffer))!=-1) {
                            bytes+=read;Failure.require(bytes<=250L*1024*1024,"REPORT_GENERATION_FAILED","Allure archive exceeds extraction bounds");file.write(buffer,0,read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }
}
