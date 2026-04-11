package org.test.remediation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Performs tightly scoped, evidence-based source retrieval for the remediation
 * workflow.
 * <p>
 * The service derives search terms from the {@link AnalysisResult}, scores
 * repository files, extracts limited snippets, and intentionally avoids broad
 * repository context by default.
 */
public class TargetedRetrievalService {

    private static final Logger LOG = LoggerFactory.getLogger(TargetedRetrievalService.class);

    /**
     * Collects targeted repository context based on the supplied analysis
     * result and workflow configuration.
     *
     * @param repoRoot repository root directory to scan
     * @param result structured heap analysis result used as retrieval evidence
     * @param config remediation workflow configuration controlling retrieval limits
     * @return retrieved context containing selected files, snippets, and warnings
     * @throws IOException if repository files cannot be read
     */
    public RetrievedContext collect(Path repoRoot,
                                    AnalysisResult result,
                                    RemediationWorkflowConfig config) throws IOException {
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(config, "config must not be null");

        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        RetrievedContext context = new RetrievedContext();
        context.repoRoot = normalizedRepoRoot.toString();
        context.queryTerms.addAll(buildQueryTerms(result));

        LOG.info("Collecting targeted retrieval context from repoRoot={} using {} query terms",
                normalizedRepoRoot, context.queryTerms.size());

        if (context.queryTerms.isEmpty()) {
            context.ambiguous = true;
            context.warnings.add("No query terms could be derived from AnalysisResult; targeted retrieval cannot proceed safely.");
            context.rationale = "No responsible class, method, keywords, or allocator hints were present in AnalysisResult.";
            return context;
        }

        List<FileCandidate> candidates = findCandidates(normalizedRepoRoot, context.queryTerms, config.retrieval);
        if (candidates.isEmpty()) {
            context.ambiguous = !config.retrieval.allowRepoWideFallback;
            context.warnings.add("No files matched the targeted retrieval query terms.");
            context.rationale = "No repository files matched the evidence-derived query terms."
                    + (config.retrieval.allowRepoWideFallback ? " Repository-wide fallback is enabled but not implemented in this MVP." : "");
            return context;
        }

        candidates.sort(Comparator
                .comparingInt(FileCandidate::score).reversed()
                .thenComparing(candidate -> candidate.relativePath));

        int fileLimit = Math.min(config.retrieval.maxFiles, candidates.size());
        List<FileCandidate> selected = candidates.subList(0, fileLimit);
        context.ambiguous = candidates.size() > fileLimit
                && candidates.get(fileLimit - 1).score == candidates.get(fileLimit).score;

        if (context.ambiguous) {
            context.warnings.add("Candidate ranking is ambiguous at the max_files boundary; review carefully before using this context.");
        }

        for (FileCandidate candidate : selected) {
            RetrievedContext.RetrievedFile file = new RetrievedContext.RetrievedFile();
            file.path = candidate.relativePath;
            file.score = candidate.score;
            file.matchedTerms.addAll(candidate.matchedTerms);
            file.snippets.addAll(extractSnippets(candidate, config.retrieval));
            context.files.add(file);
        }

        context.rationale = "Selected the highest-scoring files using responsible class/method names, code-search keywords, and allocator hints. "
                + "Repository-wide fallback is disabled by default to avoid noisy context.";
        LOG.info("Targeted retrieval selected {} file(s) and {} snippet(s)",
                context.files.size(), context.totalSnippetCount());
        return context;
    }

    /**
     * Builds a bounded list of query terms from the analysis result.
     *
     * @param result structured heap analysis result
     * @return ordered, de-duplicated query terms to use for file scoring
     */
    private List<String> buildQueryTerms(AnalysisResult result) {
        Set<String> terms = new LinkedHashSet<>();

        if (result.rootCause != null) {
            addTerm(terms, result.rootCause.responsibleClass);
            addTerm(terms, simpleName(result.rootCause.responsibleClass));
            addTerm(terms, result.rootCause.responsibleMethod);
            addAll(terms, result.rootCause.codeSearchKeywords);
        }

        if (result.dominantAllocatorStacks != null) {
            for (AnalysisResult.AllocatorStack stack : result.dominantAllocatorStacks) {
                if (stack == null) {
                    continue;
                }
                addTerm(terms, stack.allocatorMethod);
                if (stack.stackFrames != null) {
                    for (String frame : stack.stackFrames) {
                        addTerm(terms, extractFrameMethod(frame));
                        addTerm(terms, extractFrameClass(frame));
                    }
                }
            }
        }

        if (terms.isEmpty() && result.remediation != null) {
            for (String step : result.remediation) {
                if (step == null) {
                    continue;
                }
                for (String token : step.split("[^A-Za-z0-9_$.]+")) {
                    addTerm(terms, token);
                }
            }
        }

        return terms.stream()
                .filter(term -> term.length() >= 3)
                .limit(20)
                .collect(Collectors.toList());
    }

    /**
     * Scans the repository for files that satisfy the configured include/exclude
     * rules and assigns them a match score.
     *
     * @param repoRoot repository root directory
     * @param queryTerms evidence-derived query terms
     * @param retrievalConfig retrieval limits and path filters
     * @return scored file candidates with positive scores only
     * @throws IOException if repository traversal or file reads fail
     */
    private List<FileCandidate> findCandidates(Path repoRoot,
                                               List<String> queryTerms,
                                               RemediationWorkflowConfig.RetrievalConfig retrievalConfig) throws IOException {
        List<FileCandidate> candidates = new ArrayList<>();
        try (Stream<Path> pathStream = Files.walk(repoRoot)) {
            List<Path> files = pathStream
                    .filter(Files::isRegularFile)
                    .toList();

            for (Path file : files) {
                String relativePath = GlobSupport.normalizePath(repoRoot.relativize(file).toString());
                if (!GlobSupport.matchesAny(relativePath, retrievalConfig.includeGlobs)) {
                    continue;
                }
                if (GlobSupport.matchesAny(relativePath, retrievalConfig.excludeGlobs)) {
                    continue;
                }

                FileCandidate candidate = scoreCandidate(relativePath, Files.readAllLines(file), queryTerms);
                if (candidate.score > 0) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    /**
     * Scores a single repository file against the derived query terms.
     *
     * @param relativePath repository-relative path for the file
     * @param lines file contents split by line
     * @param queryTerms evidence-derived query terms
     * @return scored file candidate containing matched terms and line hits
     */
    private FileCandidate scoreCandidate(String relativePath, List<String> lines, List<String> queryTerms) {
        String lowerPath = relativePath.toLowerCase(Locale.ROOT);
        String fileName = Path.of(relativePath).getFileName().toString();
        int score = 0;
        Map<Integer, Set<String>> matchedTermsByLine = new LinkedHashMap<>();
        Set<String> matchedTerms = new LinkedHashSet<>();

        for (String term : queryTerms) {
            String lowerTerm = term.toLowerCase(Locale.ROOT);
            if (lowerTerm.isBlank()) {
                continue;
            }

            if (fileName.equalsIgnoreCase(term + ".java")) {
                score += 40;
                matchedTerms.add(term);
            }

            if (lowerPath.contains(lowerTerm.replace('.', '/')) || lowerPath.contains(lowerTerm)) {
                score += 12;
                matchedTerms.add(term);
            }

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.toLowerCase(Locale.ROOT).contains(lowerTerm)) {
                    matchedTermsByLine.computeIfAbsent(i, ignored -> new LinkedHashSet<>()).add(term);
                    matchedTerms.add(term);
                    score += term.contains(".") ? 10 : 5;
                }
            }
        }

        FileCandidate candidate = new FileCandidate();
        candidate.relativePath = relativePath;
        candidate.lines = lines;
        candidate.score = score;
        candidate.matchedTerms = new ArrayList<>(matchedTerms);
        candidate.matchedTermsByLine = matchedTermsByLine;
        return candidate;
    }

    /**
     * Extracts a bounded set of code snippets from the matched line ranges in a
     * file candidate.
     *
     * @param candidate scored file candidate
     * @param retrievalConfig retrieval limits controlling snippet extraction
     * @return extracted code snippets for the file candidate
     */
    private List<RetrievedContext.CodeSnippet> extractSnippets(FileCandidate candidate,
                                                               RemediationWorkflowConfig.RetrievalConfig retrievalConfig) {
        List<RetrievedContext.CodeSnippet> snippets = new ArrayList<>();
        int radius = retrievalConfig.snippetContextLines;
        List<Integer> matchingLines = new ArrayList<>(candidate.matchedTermsByLine.keySet());
        matchingLines.sort(Comparator.naturalOrder());

        List<int[]> ranges = mergeRanges(matchingLines, radius);
        int snippetLimit = Math.min(retrievalConfig.maxSnippetsPerFile, ranges.size());

        for (int i = 0; i < snippetLimit; i++) {
            int[] range = ranges.get(i);
            RetrievedContext.CodeSnippet snippet = new RetrievedContext.CodeSnippet();
            snippet.startLine = range[0] + 1;
            snippet.endLine = range[1] + 1;
            snippet.matchedTerms.addAll(collectTerms(candidate.matchedTermsByLine, range[0], range[1]));
            snippet.content = renderSnippet(candidate.lines, range[0], range[1]);
            snippets.add(snippet);
        }

        return snippets;
    }

    /**
     * Merges overlapping snippet ranges around matched lines into larger
     * contiguous regions.
     *
     * @param matchingLines sorted or unsorted list of matched line indexes
     * @param radius number of context lines to include around each match
     * @return merged inclusive start/end line ranges
     */
    private List<int[]> mergeRanges(List<Integer> matchingLines, int radius) {
        List<int[]> ranges = new ArrayList<>();
        for (Integer matchingLine : matchingLines) {
            int start = Math.max(0, matchingLine - radius);
            int end = matchingLine + radius;
            if (ranges.isEmpty()) {
                ranges.add(new int[]{start, end});
                continue;
            }
            int[] previous = ranges.get(ranges.size() - 1);
            if (start <= previous[1] + 1) {
                previous[1] = Math.max(previous[1], end);
            } else {
                ranges.add(new int[]{start, end});
            }
        }
        return ranges;
    }

    /**
     * Collects all matched terms whose hits fall within the provided line range.
     *
     * @param matchedTermsByLine mapping of line indexes to matched terms
     * @param startLine inclusive start line index
     * @param endLine inclusive end line index
     * @return unique matched terms present in the range
     */
    private List<String> collectTerms(Map<Integer, Set<String>> matchedTermsByLine, int startLine, int endLine) {
        Set<String> matchedTerms = new LinkedHashSet<>();
        for (Map.Entry<Integer, Set<String>> entry : matchedTermsByLine.entrySet()) {
            if (entry.getKey() >= startLine && entry.getKey() <= endLine) {
                matchedTerms.addAll(entry.getValue());
            }
        }
        return new ArrayList<>(matchedTerms);
    }

    /**
     * Renders a line-numbered snippet for the requested range.
     *
     * @param lines complete file contents
     * @param startLine inclusive start line index
     * @param endLine inclusive end line index
     * @return rendered multi-line snippet text with line numbers
     */
    private String renderSnippet(List<String> lines, int startLine, int endLine) {
        StringBuilder snippet = new StringBuilder();
        int boundedEnd = Math.min(lines.size() - 1, endLine);
        for (int i = startLine; i <= boundedEnd; i++) {
            snippet.append(i + 1)
                    .append(" | ")
                    .append(lines.get(i))
                    .append(System.lineSeparator());
        }
        return snippet.toString();
    }

    /**
     * Adds a non-blank term to the query-term set.
     *
     * @param terms destination term set
     * @param term candidate term to add
     */
    private void addTerm(Set<String> terms, String term) {
        if (term == null) {
            return;
        }
        String normalized = term.strip();
        if (normalized.isBlank()) {
            return;
        }
        terms.add(normalized);
    }

    /**
     * Adds all candidate values from the provided collection into the query-term
     * set using the same normalization rules as {@link #addTerm(Set, String)}.
     *
     * @param terms destination term set
     * @param values candidate values to add
     */
    private void addAll(Set<String> terms, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addTerm(terms, value);
        }
    }

    /**
     * Extracts a simple class name from a fully qualified class name.
     *
     * @param className fully qualified or simple class name
     * @return simple class name or {@code null} when unavailable
     */
    private String simpleName(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        int idx = className.lastIndexOf('.');
        return idx >= 0 && idx < className.length() - 1 ? className.substring(idx + 1) : className;
    }

    /**
     * Extracts the class portion from a stack-frame string.
     *
     * @param frame stack-frame text
     * @return class portion of the frame or {@code null} when unavailable
     */
    private String extractFrameClass(String frame) {
        if (frame == null || frame.isBlank()) {
            return null;
        }
        int paren = frame.indexOf('(');
        String withoutLine = paren >= 0 ? frame.substring(0, paren) : frame;
        int methodDot = withoutLine.lastIndexOf('.');
        return methodDot > 0 ? withoutLine.substring(0, methodDot) : withoutLine;
    }

    /**
     * Extracts the method portion from a stack-frame string.
     *
     * @param frame stack-frame text
     * @return method portion of the frame or {@code null} when unavailable
     */
    private String extractFrameMethod(String frame) {
        if (frame == null || frame.isBlank()) {
            return null;
        }
        int paren = frame.indexOf('(');
        String withoutLine = paren >= 0 ? frame.substring(0, paren) : frame;
        int methodDot = withoutLine.lastIndexOf('.');
        return methodDot >= 0 && methodDot < withoutLine.length() - 1
                ? withoutLine.substring(methodDot + 1)
                : withoutLine;
    }

    /**
     * Internal scored representation of a candidate repository file.
     */
    private static final class FileCandidate {
        private String relativePath;
        private List<String> lines = List.of();
        private int score;
        private List<String> matchedTerms = List.of();
        private Map<Integer, Set<String>> matchedTermsByLine = Map.of();

        /**
         * Returns the score assigned during retrieval ranking.
         *
         * @return candidate score
         */
        private int score() {
            return score;
        }
    }
}


