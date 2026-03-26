package org.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * Watches a directory for newly created heap dump files (e.g. *.hprof) and invokes a
 * callback when a new heap dump is observed.
 *
 * Usage:
 *   HeapDumpWatcher watcher = new HeapDumpWatcher("./heapdumps");
 *   watcher.start();
 *   // ... when done: watcher.stop();
 */
public class HeapDumpWatcher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(HeapDumpWatcher.class);
    private final Path dir;
    private final WatchService watcher;
    private final Thread thread;
    private volatile boolean running = false;

    public HeapDumpWatcher(String path) throws IOException {
        this(Paths.get(path));
    }

    public HeapDumpWatcher(Path dir) throws IOException {
        this.dir = dir.toAbsolutePath().normalize();
        LOGGER.info("checking  dir: {}", this.dir);
        if (!Files.exists(this.dir)) {
            Files.createDirectories(this.dir);
        }
        this.watcher = FileSystems.getDefault().newWatchService();
        this.dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
        this.thread = new Thread(this::processEvents, "HeapDumpWatcher-Thread");
        this.thread.setDaemon(true);
    }

    public Path getDirectory() {
        return dir;
    }

    public void start() {
        if (running) return;
        running = true;
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            watcher.close();
        } catch (IOException ignored) {
        }
        thread.interrupt();
    }

    private void processEvents() {
        while (running) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                Path fullPath = dir.resolve(filename);

                // We only care about typical heap dump file extension
                String nameLower = filename.toString().toLowerCase();
                if (nameLower.endsWith(".hprof") || nameLower.endsWith(".phd")) {
                    // Wait until the file is stable (size stops increasing) before processing
                    waitForFileStable(fullPath);
                    try {
                        onHeapDumpCreated(fullPath.toFile());
                    } catch (Exception ex) {
                        LOGGER.error("Error handling heap dump: {}", ex.getMessage(), ex);
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                break;
            }
        }
    }

    /**
     * Override or subclass and implement this to take action when a new heap dump file appears.
     * By default this prints the path as a placeholder action.
     */
    protected void onHeapDumpCreated(File heapDumpFile) {
        LOGGER.info("Heap dump detected: {}", heapDumpFile.getAbsolutePath());
        // Placeholder: add your processing here (upload, analyze, move, etc.)
    }

    private void waitForFileStable(Path file) {
        try {
            long prev = -1;
            // wait up to a few seconds for file to settle
            for (int i = 0; i < 20; i++) {
                if (!Files.exists(file)) {
                    TimeUnit.MILLISECONDS.sleep(200);
                    continue;
                }
                long size = Files.size(file);
                if (size == prev) {
                    // size didn't change between checks -> likely finished
                    break;
                }
                prev = size;
                TimeUnit.MILLISECONDS.sleep(300);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Simple CLI entrypoint so this watcher can be run standalone.
     * Usage: java org.test.HeapDumpWatcher [path]
     * If no path is provided, "./heapdumps" is used.
     */
    public static void main(String[] args) {
        String dirArg = (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty()) ? args[0] : "./heapdumps";
        LOGGER.info("Starting HeapDumpWatcher for directory: {}", dirArg);

        final HeapDumpWatcher watcher;
        try {
            watcher = new HeapDumpWatcher(dirArg);
        } catch (IOException e) {
            LOGGER.error("Failed to create HeapDumpWatcher for '{}': {}", dirArg, e.getMessage(), e);
            return;
        }

        watcher.start();

        // Ensure watcher is stopped on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown requested, stopping HeapDumpWatcher...");
            watcher.stop();
        }));

        // Block main thread until interrupted
        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            watcher.stop();
        }
    }
}

