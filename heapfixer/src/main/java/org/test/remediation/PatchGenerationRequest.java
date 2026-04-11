package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Machine-oriented request payload for the patch generation phase.
 * <p>
 * It combines the authoring result with repository evidence and current file
 * snippets so patch backends can generate reviewable patch artifacts without
 * mutating repository files.
 */
public class PatchGenerationRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("generated_at")
    public String generatedAt = Instant.now().toString();

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("branch_name")
    public String branchName;

    @JsonProperty("pr_title")
    public String prTitle;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("files")
    public List<PatchTargetFile> files = new ArrayList<>();

    /**
     * Serializes this request to formatted JSON.
     *
     * @return formatted JSON representation of this request
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * Patch-generation input for a single candidate file.
     */
    public static class PatchTargetFile {
        @JsonProperty("path")
        public String path;

        @JsonProperty("change_type")
        public String changeType;

        @JsonProperty("intent")
        public String intent;

        @JsonProperty("justification")
        public String justification;

        @JsonProperty("evidence")
        public List<String> evidence = new ArrayList<>();

        @JsonProperty("suggested_tests")
        public List<String> suggestedTests = new ArrayList<>();

        @JsonProperty("snippet_contexts")
        public List<SnippetContext> snippetContexts = new ArrayList<>();
    }

    /**
     * Current-file snippet context carried into the patch generation phase.
     */
    public static class SnippetContext {
        @JsonProperty("start_line")
        public int startLine;

        @JsonProperty("end_line")
        public int endLine;

        @JsonProperty("content")
        public String content;

        @JsonProperty("matched_terms")
        public List<String> matchedTerms = new ArrayList<>();
    }
}

