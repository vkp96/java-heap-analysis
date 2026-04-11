package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializable result of targeted source retrieval performed before any future
 * PR-authoring step.
 * <p>
 * The object captures the repo root, evidence-derived query terms, selected
 * files, extracted snippets, warnings, and whether the retrieval was deemed
 * ambiguous by policy.
 */
public class RetrievedContext {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("repo_root")
    public String repoRoot;

    @JsonProperty("query_terms")
    public List<String> queryTerms = new ArrayList<>();

    @JsonProperty("files")
    public List<RetrievedFile> files = new ArrayList<>();

    @JsonProperty("warnings")
    public List<String> warnings = new ArrayList<>();

    @JsonProperty("ambiguous")
    public boolean ambiguous;

    @JsonProperty("rationale")
    public String rationale;

    @JsonProperty("retrieved_at")
    public String retrievedAt = Instant.now().toString();

    /**
     * Counts the total number of snippets across all retrieved files.
     *
     * @return total snippet count in this retrieval result
     */
    public int totalSnippetCount() {
        return files.stream()
                .mapToInt(file -> file.snippets != null ? file.snippets.size() : 0)
                .sum();
    }

    /**
     * Serializes this retrieval result to formatted JSON.
     *
     * @return formatted JSON representation of this object
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * A single repository file selected by targeted retrieval, along with its
     * score and extracted snippets.
     */
    public static class RetrievedFile {
        @JsonProperty("path")
        public String path;

        @JsonProperty("score")
        public int score;

        @JsonProperty("matched_terms")
        public List<String> matchedTerms = new ArrayList<>();

        @JsonProperty("snippets")
        public List<CodeSnippet> snippets = new ArrayList<>();
    }

    /**
     * A contiguous code region extracted from a retrieved file because one or
     * more evidence-derived terms matched within the range.
     */
    public static class CodeSnippet {
        @JsonProperty("start_line")
        public int startLine;

        @JsonProperty("end_line")
        public int endLine;

        @JsonProperty("matched_terms")
        public List<String> matchedTerms = new ArrayList<>();

        @JsonProperty("content")
        public String content;
    }
}


