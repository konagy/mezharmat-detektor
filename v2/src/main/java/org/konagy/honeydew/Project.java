package org.konagy.honeydew;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Portable, inspectable project data. Candidate decisions never become reference labels implicitly. */
public final class Project {
    public int schemaVersion = 1;
    public String video;
    public long videoBytes;
    public long videoModifiedMillis;
    public List<Anchor> anchors = new ArrayList<>();
    public Settings settings = new Settings();
    public List<Event> candidates = new ArrayList<>();
    public List<Reference> references = new ArrayList<>();
    public List<Interval> unassessed = new ArrayList<>();
    public List<Interval> reviewed = new ArrayList<>();
    public String runStatus = "Not analyzed";
    public String detectorVersion;
    public String analyzedAtUtc;
    public double analyzedUntil = 0;
    public long processedFrames = 0;
    public double wallSeconds = 0;

    public record Point(double x, double y) {
        double distance(Point p) { return Math.hypot(x-p.x, y-p.y); }
    }
    public record Box(double x, double y, double width, double height) {}
    public record Anchor(double seconds, Box body, Point head, Point tail) {}
    public record Interval(double start, double end, String reason) {}
    public record Reference(double start, double end, String label, String notes) {}
    public static final class Event {
        public String id = UUID.randomUUID().toString();
        public double start, end, peak;
        public double x, y, evidence;
        public String decision = "Unreviewed";
        public String reason = "Tail-local temporal change";
        public Event(double start, double end, double peak, double x, double y, double evidence) {
            this.start=start; this.end=end; this.peak=peak; this.x=x; this.y=y; this.evidence=evidence;
        }
        @Override public String toString() { return String.format(Locale.ROOT, "%.3f–%.3f s | %s", start,end,decision); }
    }
    public static final class Settings {
        public double noiseMultiplier = 4.0;
        public double minContrast = 9;
        public double minVisibleSeconds = 0.045;
        public double maxGapSeconds = 0.12;
        public double warmupSeconds = 0.35;
    }
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static Project create(Path video) throws IOException {
        Project p = new Project();
        p.video = video.toAbsolutePath().toString();
        p.videoBytes = Files.size(video);
        p.videoModifiedMillis = Files.getLastModifiedTime(video).toMillis();
        return p;
    }
    public void validate() throws IOException {
        if (schemaVersion != 1) throw new IOException("Unsupported project schema: "+schemaVersion);
        if (video == null || !Files.isRegularFile(Path.of(video))) throw new IOException("Source video is missing.");
        if (Files.size(Path.of(video)) != videoBytes ||
            Files.getLastModifiedTime(Path.of(video)).toMillis() != videoModifiedMillis)
            throw new IOException("Source video has changed. Create a new project or restore the original video.");
        if (anchors == null || settings == null || candidates == null || references == null ||
            unassessed == null || reviewed == null) throw new IOException("Incomplete project.");
        if (!positive(settings.noiseMultiplier) || !positive(settings.minContrast) ||
            !positive(settings.minVisibleSeconds) || !positive(settings.maxGapSeconds) ||
            !positive(settings.warmupSeconds)) throw new IOException("Invalid detector settings.");
        double previous = -1;
        for (Anchor a : anchors) {
            if (a == null || a.body == null || a.head == null || a.tail == null ||
                !Double.isFinite(a.seconds) || a.seconds < 0 || a.seconds <= previous ||
                !positive(a.body.width) || !positive(a.body.height) ||
                !finite(a.body.x,a.body.y,a.head.x,a.head.y,a.tail.x,a.tail.y) ||
                a.head.distance(a.tail) < 10) throw new IOException("Invalid or duplicate tracking anchor.");
            previous = a.seconds;
        }
    }
    private static boolean finite(double... xs) { for(double x:xs) if(!Double.isFinite(x)) return false; return true; }
    private static boolean positive(double x) { return Double.isFinite(x) && x>0; }
    public void setAnchor(Anchor a) {
        anchors.removeIf(old -> Math.abs(old.seconds-a.seconds)<0.001);
        anchors.add(a); anchors.sort(Comparator.comparingDouble(Anchor::seconds));
        if(processedFrames>0) runStatus="Outdated — tracking anchors changed; rerun analysis";
    }
    public void save(Path path) throws IOException {
        Path full=path.toAbsolutePath();
        Files.createDirectories(full.getParent());
        Path temporary=Files.createTempFile(full.getParent(), "honeydew-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(this));
            try { Files.move(temporary,full,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temporary,full,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static Project load(Path path) throws IOException {
        try {
            Project p=JSON.fromJson(Files.readString(path),Project.class);
            if(p==null) throw new IOException("Empty project.");
            p.validate(); return p;
        } catch(JsonParseException | IllegalArgumentException e) { throw new IOException("Invalid project JSON.",e); }
    }
    public Project copy() { return JSON.fromJson(JSON.toJson(this),Project.class); }
    public void exportCsv(Path path) throws IOException {
        try (BufferedWriter out=Files.newBufferedWriter(path)) {
            out.write("id,start_seconds,end_seconds,peak_seconds,x,y,evidence_not_probability,decision,reason\n");
            for(Event e:candidates) out.write(String.format(Locale.ROOT,"%s,%.6f,%.6f,%.6f,%.3f,%.3f,%.3f,%s,%s%n",
                quote(e.id),e.start,e.end,e.peak,e.x,e.y,e.evidence,quote(e.decision),quote(e.reason)));
        }
    }
    private static String quote(String s) { return "\"" + s.replace("\"","\"\"") + "\""; }
}
