package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalized result produced by an AI authoring backend.
 * <p>
 * This result is provider-agnostic so future authoring backends can emit the
 * same contract regardless of whether the implementation is Copilot, OpenAI,
 * Claude, Gemini, or a deterministic local fallback.
 */
public class PrAuthorResult {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("model")
    public String model;

    @JsonProperty("implementation_summary")
    public String implementationSummary;

    @JsonProperty("proposed_file_changes")
    public List<ProposedFileChange> proposedFileChanges = new ArrayList<>();

    @JsonProperty("validation_steps")
    public List<String> validationSteps = new ArrayList<>();

    @JsonProperty("risk_notes")
    public List<String> riskNotes = new ArrayList<>();

    @JsonProperty("confidence")
    public String confidence;

    /**
     * Serializes this authoring result to formatted JSON.
     *
     * @return formatted JSON representation
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * Parses a JSON response into a normalized authoring result.
     * <p>
     * The method tolerates markdown code fences to support model outputs that
     * wrap JSON in fenced blocks.
     *
     * @param raw raw model response text expected to contain a JSON object
     * @return parsed authoring result
     * @throws Exception if parsing fails
     */
    public static PrAuthorResult fromJson(String raw) throws Exception {
        return MAPPER.readValue(stripMarkdownCodeFences(raw), PrAuthorResult.class);
    }

    /**
     * Removes a surrounding markdown JSON code fence when present.
     *
     * @param raw raw response text
     * @return unfenced JSON text
     */
    private static String stripMarkdownCodeFences(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    /**
     * Provider-neutral file-level proposal produced by an authoring backend.
     */
    public static class ProposedFileChange {
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
    }
}

