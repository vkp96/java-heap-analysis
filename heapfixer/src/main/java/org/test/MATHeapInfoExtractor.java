package org.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

/**
 * Small utility to extract/generate a heap analysis report zip using Eclipse Memory Analyzer (MAT) CLI when available.
 *<p></p>
 * Behavior of extractHeapReport:
 * - Validates inputs.
 * - Creates a temporary working directory for MAT output.
 * - If a plausible MAT executable is found under the provided matTool path, attempts to run MAT headlessly to produce a report in the working dir.
 *   - The command attempted is:
 *     <matExec> -consoleLog -application org.eclipse.mat.api.parse <heapDump> -output <workDir>
 *   - NOTE: MAT CLI invocation may vary across MAT versions; if your MAT uses a different application id or arguments, adjust the call site or this method.
 * - If MAT run fails or MAT not found, writes a small fallback report and packages the .hprof and minimal metadata into a zip.
 * - Always creates a zip at destDir/{heap-file-name}-report.zip containing the generated output (MAT outputs or fallback files).
 *
 * This is a conservative helper: the MAT invocation is best-effort and there is a fallback so callers always get a zip artifact.
 */
public class MATHeapInfoExtractor {

    private static final Logger LOG = Logger.getLogger(MATHeapInfoExtractor.class.getName());

    /**
     * Generate a heap report zip for the given heap dump.
     *
     * @param heapDumpPath Path to the heapdump (.hprof) file
     * @param matToolPath  Path to the Eclipse Memory Analyzer tool (either the executable / script or the MAT installation directory)
     * @param destDirPath  Destination directory to write the final report zip to
     * @return Path to the created zip file
     * @throws Exception on fatal errors (I/O, interrupted, etc.)
     */
    public static Path extractHeapReport(Path heapDumpPath, Path matToolPath, Path destDirPath) throws Exception {
        // Validate inputs
        if (heapDumpPath == null) throw new IllegalArgumentException("heapDumpPath must not be null");

        // Normalize heapDumpPath to an absolute, normalized path so we use consistent paths everywhere
        heapDumpPath = heapDumpPath.toAbsolutePath().normalize();

        if (!Files.exists(heapDumpPath) || !Files.isRegularFile(heapDumpPath))
            throw new IllegalArgumentException("heap dump not found: " + heapDumpPath);

        if (destDirPath == null) throw new IllegalArgumentException("destDirPath must not be null");

        // Normalize destDirPath to an absolute, normalized path so we operate on a consistent destination
        destDirPath = destDirPath.toAbsolutePath().normalize();
        Files.createDirectories(destDirPath);

        LOG.info("extractHeapReport called with:\n  heapDumpPath=" + heapDumpPath.toAbsolutePath() + "\n  matToolPath=" + matToolPath + "\n  destDirPath=" + destDirPath.toAbsolutePath());

        String baseName = stripExtension(heapDumpPath.getFileName().toString());

        destDirPath = destDirPath.resolve(baseName);
        Files.createDirectories(destDirPath);
        LOG.info("Created destination directory for report: " + destDirPath.toAbsolutePath());

        String timeStamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');

        // Use the destination subdirectory as the working directory and copy the heap there
        Path workDir = destDirPath;
        LOG.info("Working dir (destination): " + workDir.toAbsolutePath());
        Path heapCopyTarget = workDir.resolve(heapDumpPath.getFileName());
        LOG.info("Copying heap dump into working directory: " + heapCopyTarget.toAbsolutePath());
        Files.copy(heapDumpPath, heapCopyTarget, StandardCopyOption.REPLACE_EXISTING);

        boolean matInvoked = false;
        String matOutputNote = "";

        try {
            if (matToolPath != null && Files.exists(matToolPath)) {
                LOG.info(() -> "MAT tool path exists: " + matToolPath.toAbsolutePath());
                Path matExec = resolveMatExecutable(matToolPath);
                if (matExec != null && Files.exists(matExec)) {
                    LOG.info(() -> "Found MAT executable: " + matExec);
                    List<String> cmd = constructCommand(matExec, heapCopyTarget, workDir);

                    LOG.info(() -> "Invoking MAT: " + String.join(" ", cmd));
                    ProcessBuilder pb = new ProcessBuilder(cmd)
                            .redirectErrorStream(true)
                            .directory(workDir.toFile());

                    Process p = pb.start();
                    // capture output to log (non-blocking)
                    Thread t = new Thread(() -> {
                        try (InputStream is = p.getInputStream()) {
                            byte[] buf = new byte[4096];
                            int r;
                            while ((r = is.read(buf)) != -1) {
                                String s = new String(buf, 0, r);
                                LOG.fine(() -> s);
                            }
                        } catch (IOException e) {
                            LOG.warning("Error reading MAT process output: " + e.getMessage());
                        }
                    }, "mat-output-reader");
                    t.setDaemon(true);
                    t.start();

                    LOG.info(() -> "Waiting up to 10 minutes for MAT to finish...");
                    boolean finished = p.waitFor(10, TimeUnit.MINUTES);
                    if (!finished) {
                        p.destroyForcibly();
                        throw new RuntimeException("MAT invocation timed out after 10 minutes");
                    }
                    int exit = p.exitValue();
                    matInvoked = (exit == 0);
                    matOutputNote = "MAT exit code: " + exit;
                    LOG.info(() -> "MAT finished with exit code " + exit);
                } else {
                    LOG.info(() -> "No MAT executable found under provided matToolPath: " + matToolPath);
                }
            } else {
                LOG.info("No MAT tool path provided or MAT path not found; skipping MAT invocation.");
            }
        } catch (Exception e) {
            LOG.warning("MAT invocation failed: " + e.getMessage());
            // fall through to fallback packaging
        }

        // If MAT didn't produce anything, write a fallback placeholder (heap already copied above)
        if (!matInvoked || !hasNonTrivialOutput(workDir)) {
            LOG.info("Using fallback report content (writing metadata in working directory).");
            // Write a README / metadata file
            String meta = "Fallback heap report generated by MATHeapInfoExtractor\n" +
                    "Original heap: " + heapDumpPath.toAbsolutePath() + "\n" +
                    "Requested MAT path: " + (matToolPath != null ? matToolPath.toAbsolutePath() : "null") + "\n" +
                    "Time: " + Instant.now().toString() + "\n" +
                    matOutputNote + "\n\n" +
                    "If you have a MAT installation, pass the path to the MAT executable or installation directory.\n" +
                    "MAT CLI invocation used by this helper (may differ by MAT version):\n" +
                    "  <matExec> -consoleLog -application org.eclipse.mat.api.parse <heapDump> -output <workDir>\n";
            Files.writeString(workDir.resolve("README.txt"), meta, java.nio.charset.StandardCharsets.UTF_8);
            LOG.info("Wrote fallback README.txt in working directory: " + workDir.resolve("README.txt").toAbsolutePath());
        }

        // Create zip
        String zipName = baseName + "-report-" + timeStamp + ".zip";
        // Place the zip alongside the destination root (so the zip file is not included inside the archive)
        Path zipPath = destDirPath.getParent().resolve(zipName);
        LOG.info("Creating report zip: " + zipPath.toAbsolutePath());
        zipDirectoryContents(workDir, zipPath);

        LOG.info("Created report zip: " + zipPath.toAbsolutePath());
        return zipPath;
    }

    private static List<String> constructCommand(Path matExec, Path heapCopyTarget, Path workDir) {
        List<String> cmd = new ArrayList<>();
        String execName = matExec.getFileName().toString().toLowerCase();
        // If the MAT distribution provides ParseHeapDump.bat/.sh, prefer invoking it in headless mode
        if (execName.contains("parseheapdump")) {
            cmd.add(matExec.toString());
            // Use headless launcher that accepts the heap file followed by report ids.
            // Point to the copied heap in workDir and request multiple built-in reports.
            cmd.add(heapCopyTarget.toString());
            // Request suspects, overview and dominator tree reports (some ParseHeapDump launchers accept multiple report ids)
            cmd.add("org.eclipse.mat.api:suspects");
            cmd.add("org.eclipse.mat.api:overview");
            cmd.add("org.eclipse.mat.api:dominators");
            //cmd.add("-output");
            //cmd.add(workDir.toString());
        } else {
            // Best-effort default arguments; some MAT installs use different application ids.
            cmd.add(matExec.toString());
            cmd.add("-consoleLog");
            cmd.add("-application");
            cmd.add("org.eclipse.mat.api.parse");
            cmd.add(heapCopyTarget.toString());
            //cmd.add("-output");
            //cmd.add(workDir.toString()+"/");
        }
        return cmd;
    }

    // ---------------------- helpers ------------------------------

    private static String stripExtension(String s) {
        int idx = s.lastIndexOf('.');
        return (idx > 0) ? s.substring(0, idx) : s;
    }

    /**
     * Try to locate a plausible MAT executable inside the supplied path.
     * - If matToolPath is a file we return it.
     * - If matToolPath is a directory we look for common launcher names.
     * Returns null if nothing found.
     */
    private static Path resolveMatExecutable(Path matToolPath) {
        try {
            if (Files.isRegularFile(matToolPath)) return matToolPath;

            if (Files.isDirectory(matToolPath)) {
                String[] candidates = {
                        "ParseHeapDump.bat", "ParseHeapDump.sh", "MemoryAnalyzer.exe", "MemoryAnalyzer", "mat.bat", "mat", "MemoryAnalyzer.sh", "mat.sh"
                };
                for (String c : candidates) {
                    Path p = matToolPath.resolve(c);
                    if (Files.exists(p) && Files.isRegularFile(p)) return p;
                }
                // Some distributions put the launcher in "tools" or "bin"
                Path bin = matToolPath.resolve("bin");
                if (Files.isDirectory(bin)) {
                    for (String c : candidates) {
                        Path p = bin.resolve(c);
                        if (Files.exists(p) && Files.isRegularFile(p)) return p;
                    }
                }
            }
        } catch (Exception e) {
            LOG.fine(() -> "Error resolving MAT executable: " + e.getMessage());
        }
        return null;
    }

    private static boolean hasNonTrivialOutput(Path workDir) {
        try (Stream<Path> s = Files.list(workDir)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase();
                // look for typical MAT outputs: .html, .zip, .txt, or snapshot files
                return n.endsWith(".html") || n.endsWith(".zip") || n.endsWith(".xml") || n.endsWith(".txt") || n.endsWith(".hprof") || n.endsWith(".snapshot");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static void zipDirectoryContents(Path sourceDir, Path outZip) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceDir);
             OutputStream fos = Files.newOutputStream(outZip);
             ZipOutputStream zs = new ZipOutputStream(fos)) {

            paths.filter(p -> !Files.isDirectory(p)).forEach(p -> {
                Path rel = sourceDir.relativize(p);
                String entryName = rel.toString().replace('\\', '/'); // zip standard
                try {
                    ZipEntry ze = new ZipEntry(entryName);
                    zs.putNextEntry(ze);
                    Files.copy(p, zs);
                    zs.closeEntry();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * CLI entrypoint. Usage:
     *   java org.test.MATHeapInfoExtractor <heapDumpPath> <matToolPath> <destDirPath>
     */
    public static void main(String[] args) {
        LOG.info(() -> "MATHeapInfoExtractor main invoked with args: " + java.util.Arrays.toString(args));
        if (args == null || args.length < 3) {
            System.err.println("Usage: java org.test.MATHeapInfoExtractor <heapDumpPath> <matToolPath> <destDirPath>");
            System.exit(2);
        }

        Path heap = Paths.get(args[0]);
        Path mat  = Paths.get(args[1]);
        Path dest = Paths.get(args[2]);

        try {
            Path zip = extractHeapReport(heap, mat, dest);
            System.out.println("Report created at: " + zip.toAbsolutePath());
            System.exit(0);
        } catch (Exception e) {
            System.err.println("Failed to create report: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()) // children first
                .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            LOG.fine(() -> "Failed to delete " + p + ": " + e.getMessage());
                        }
                    });
        }
    }
}
