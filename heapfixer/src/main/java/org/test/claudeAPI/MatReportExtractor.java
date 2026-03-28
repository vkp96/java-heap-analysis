package org.test.claudeAPI;

import org.test.MATRunner;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts structured text content from Eclipse MAT headless report output.
 *
 * MAT headless generates a reports/ directory containing:
 *   - leak_suspects.zip  →  leak_suspects.html (primary suspect report)
 *   - system_overview/   →  system_overview.html
 *   - dominator_tree/    →  optional detailed dominator data
 *
 * This class unzips, strips HTML tags, and segments the content into
 * named sections that the analyzer agent can reason over.
 */
public class MatReportExtractor {

    private static final Logger log = LoggerFactory.getLogger(MatReportExtractor.class);

    public static class MatReport {
        public final String rawLeakSuspectsText;
        public final String rawSystemOverviewText;
        public final String heapDumpPath;
        public final String extractedAt;
        public final List<String> suspectBlocks;   // each Problem Suspect as its own string
        public final String dominatorTreeText;

        public MatReport(String leakSuspectsText,
                         String systemOverviewText,
                         String heapDumpPath,
                         List<String> suspectBlocks,
                         String dominatorTreeText) {
            this.rawLeakSuspectsText  = leakSuspectsText;
            this.rawSystemOverviewText = systemOverviewText;
            this.heapDumpPath         = heapDumpPath;
            this.suspectBlocks        = suspectBlocks;
            this.dominatorTreeText    = dominatorTreeText;
            this.extractedAt          = java.time.Instant.now().toString();
        }

        /** Produces a single text block to pass as the LLM context. */
        public String toPromptContext() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== SYSTEM OVERVIEW ===\n")
              .append(rawSystemOverviewText).append("\n\n");

            sb.append("=== LEAK SUSPECTS REPORT ===\n")
              .append(rawLeakSuspectsText).append("\n\n");

            if (dominatorTreeText != null && !dominatorTreeText.isBlank()) {
                sb.append("=== DOMINATOR TREE (top entries) ===\n")
                  .append(dominatorTreeText).append("\n\n");
            }

            sb.append("=== INDIVIDUAL SUSPECT BLOCKS ===\n");
            for (int i = 0; i < suspectBlocks.size(); i++) {
                sb.append("--- Suspect ").append(i + 1).append(" ---\n")
                  .append(suspectBlocks.get(i)).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Primary entry point.
     * @param matReportsDir  Path to the directory MAT wrote its reports into
     *                       (the folder that contains leak_suspects.zip etc.)
     * @param heapDumpPath   Original .hprof file path, for provenance in the output
     */
    public MatReport extract(Path matReportsDir, String heapDumpPath) throws IOException {
        log.info("extract(): matReportsDir='{}' heapDumpPath='{}'", matReportsDir, heapDumpPath);
        String leakText    = extractLeakSuspectsReport(matReportsDir, heapDumpPath);
        String overviewText = extractSystemOverview(matReportsDir, heapDumpPath);
        String dominatorText = extractDominatorTree(matReportsDir);
        List<String> blocks = splitIntoSuspectBlocks(leakText);

        return new MatReport(leakText, overviewText, heapDumpPath, blocks, dominatorText);
    }

    // -------------------------------------------------------------------------
    //  Private extraction helpers
    // -------------------------------------------------------------------------

    private String extractLeakSuspectsReport(Path reportsDir, String heapDumpPath) throws IOException {
        // MAT writes a zip; look for it first, then fall back to a plain HTML file
        String heapDumpBaseName = MATRunner.stripExtension(heapDumpPath);
        Path zip  = reportsDir.resolve(heapDumpBaseName + "_Leak_Suspects.zip");
        Path html = reportsDir.resolve("leak_suspects.html");
        log.info("extractLeakSuspectsReport(): checking zip='{}' and html='{}'", zip, html);

        if (Files.exists(zip)) {
            log.info("Found leak suspects zip: {}", zip);
            String content = readHtmlFromZip(zip, "leak_suspects.html");
            if (content != null) {
                log.info("Read leak suspects content from zip ({} chars)", content.length());
                return stripHtml(content);
            } else {
                log.info("No leak_suspects.html entry found inside {}", zip);
            }
        } else {
            log.debug("Zip not found: {}", zip);
        }

        if (Files.exists(html)) {
            String content = Files.readString(html);
            log.info("Read leak suspects html file {} ({} chars)", html, content.length());
            return stripHtml(content);
        } else {
            log.debug("HTML not found: {}", html);
        }

        // Try recursive search one level down (MAT sometimes nests inside a subdir)
        log.info("Searching recursively (depth=2) under {} for leak_suspects.html", reportsDir);
        try (var stream = Files.walk(reportsDir, 2)) {
            Optional<Path> found = stream
                .filter(p -> p.getFileName().toString().equals("leak_suspects.html"))
                .findFirst();
            if (found.isPresent()) {
                Path p = found.get();
                String content = Files.readString(p);
                log.info("Found leak suspects at {} ({} chars)", p, content.length());
                return stripHtml(content);
            }
        }

        log.warn("Leak suspects report not found in {}", reportsDir);
        return "[leak_suspects report not found in " + reportsDir + "]";
    }

    private String extractSystemOverview(Path reportsDir, String heapDumpPath) throws IOException {
        // MAT often writes a heap-specific zip (based on the hprof basename) or a
        // generic system_overview.zip or folder. Try heap-specific zip names first.
        String heapDumpBaseName = MATRunner.stripExtension(heapDumpPath);
        String[] candidateZips = new String[] {
            heapDumpBaseName + "_System_Overview.zip",
            heapDumpBaseName + "_SystemOverview.zip",
            heapDumpBaseName + "_system_overview.zip",
            heapDumpBaseName + "_systemOverview.zip",
            "system_overview.zip"
        };

        for (String zname : candidateZips) {
            Path z = reportsDir.resolve(zname);
            log.debug("Checking candidate zip: {}", z);
            if (Files.exists(z)) {
                log.info("Found candidate system overview zip: {}", z);
                String c = readHtmlFromZip(z, "system_overview.html");
                if (c != null) {
                    log.info("Read system overview from {} ({} chars)", z, c.length());
                    return stripHtml(c);
                }
                // try any HTML inside the zip
                c = readHtmlFromZip(z, null);
                if (c != null) {
                    log.info("Read system overview (generic) from {} ({} chars)", z, c.length());
                    return stripHtml(c);
                }
            }
        }

        // Fallback to individual files/folders
        String[] candidates = {
            "system_overview/system_overview.html",
            "system_overview.html",
            "overview.html"
        };
        for (String rel : candidates) {
            Path p = reportsDir.resolve(rel);
            log.debug("Checking candidate file: {}", p);
            if (Files.exists(p)) {
                String content = Files.readString(p);
                log.info("Read system overview file {} ({} chars)", p, content.length());
                return stripHtml(content);
            }
        }

        // Try recursive search one level down
        log.info("Searching recursively (depth=2) under {} for system overview HTML", reportsDir);
        try (var stream = Files.walk(reportsDir, 2)) {
            Optional<Path> found = stream
                .filter(p -> p.getFileName().toString().equalsIgnoreCase("system_overview.html")
                          || p.getFileName().toString().equalsIgnoreCase("overview.html"))
                .findFirst();
            if (found.isPresent()) {
                Path p = found.get();
                String content = Files.readString(p);
                log.info("Found system overview at {} ({} chars)", p, content.length());
                return stripHtml(content);
            }
        }

        log.warn("System overview report not found in {}", reportsDir);
        return "[system_overview not found in " + reportsDir + "]";
    }

    private String extractDominatorTree(Path reportsDir) throws IOException {
        String[] candidates = {
            "dominator_tree/index.html",
            "dominator_tree.html"
        };
        for (String rel : candidates) {
            Path p = reportsDir.resolve(rel);
            log.debug("Checking dominator candidate: {}", p);
            if (Files.exists(p)) {
                String full = stripHtml(Files.readString(p));
                log.info("Read dominator tree from {} ({} chars)", p, full.length());
                return full.length() > 8_000 ? full.substring(0, 8_000) + "\n[truncated]" : full;
            }
        }
        log.debug("No dominator tree found in {}", reportsDir);
        return null;
    }

    /** Reads a named entry from a zip archive and returns its string content. */
    private String readHtmlFromZip(Path zipPath, String entryName) throws IOException {
        log.debug("readHtmlFromZip(): zip='{}' entryName='{}'", zipPath, entryName);
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = null;
            if (entryName != null) entry = zf.getEntry(entryName);
            if (entry == null) {
                // Try to find any HTML file if exact name misses
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String en = e.getName().toLowerCase(Locale.ROOT);
                    if (entryName == null && en.endsWith(".html")) {
                        entry = e;
                        break;
                    }
                    // if we asked for an entryName but didn't find it, fall back to first entry
                    if (entry == null) entry = e;
                }
            }
            if (entry != null) {
                log.debug("readHtmlFromZip(): using zip entry '{}'", entry.getName());
                try (InputStream is = zf.getInputStream(entry)) {
                    byte[] bytes = is.readAllBytes();
                    log.debug("Read {} bytes from zip entry {} in {}", bytes.length, entry.getName(), zipPath);
                    return new String(bytes);
                }
            }
        } catch (FileNotFoundException fnf) {
            log.warn("Zip file not found: {}", zipPath);
        }
        return null;
    }

    /**
     * Splits the full leak suspects text into one block per "Problem Suspect N" section.
     * MAT uses "Problem Suspect 1", "Problem Suspect 2", ... as section headers.
     */
    private List<String> splitIntoSuspectBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        // Matches "Problem Suspect 1", "Suspect 1:", "Leak Suspect 1" etc.
        Pattern header = Pattern.compile(
            "(?i)(problem\\s+suspect|leak\\s+suspect|suspect)\\s+\\d+", Pattern.MULTILINE);
        Matcher m = header.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) starts.add(m.start());

        for (int i = 0; i < starts.size(); i++) {
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            blocks.add(text.substring(starts.get(i), end).strip());
        }
        if (blocks.isEmpty() && !text.isBlank()) {
            // Couldn't identify sections – treat the whole report as one block
            blocks.add(text.strip());
        }
        log.info("splitIntoSuspectBlocks(): produced {} blocks", blocks.size());
        return blocks;
    }

    // -------------------------------------------------------------------------
    //  HTML → plain text
    // -------------------------------------------------------------------------

    private static final Pattern TAG_PATTERN   = Pattern.compile("<[^>]+>");
    private static final Pattern SPACES_PATTERN = Pattern.compile("[ \\t]{2,}");
    private static final Pattern BLANK_LINES   = Pattern.compile("(\\n\\s*){3,}");

    /** Strips HTML tags and normalises whitespace, preserving logical line breaks. */
    static String stripHtml(String html) {
        if (html == null) return "";
        log.debug("stripHtml(): input length {}", html.length());
        // Block-level tags → newlines before stripping
        String text = html
            .replaceAll("(?i)<br\\s*/?>",            "\n")
            .replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\n")
            .replaceAll("(?i)<(p|div|li|tr|h[1-6])[^>]*>", "\n");

        text = TAG_PATTERN.matcher(text).replaceAll("");
        // Decode common HTML entities
        text = text.replace("&nbsp;", " ")
                   .replace("&amp;",  "&")
                   .replace("&lt;",   "<")
                   .replace("&gt;",   ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;",  "'");

        text = SPACES_PATTERN.matcher(text).replaceAll(" ");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        log.debug("stripHtml(): output length {}", text.length());
        return text.strip();
    }
}
