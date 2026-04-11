package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral result produced by the patch generation phase.
 */
public class PatchGenerationResult {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("generated_at")
    public String generatedAt = Instant.now().toString();

    @JsonProperty("provider")
    public String provider;

    @JsonProperty("model")
    public String model;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("structured_patch_files")
    public List<StructuredPatchFile> structuredPatchFiles = new ArrayList<>();

    @JsonProperty("notes")
    public List<String> notes = new ArrayList<>();

    /**
     * Serializes this result to formatted JSON.
     *
     * @return formatted JSON representation of this result
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    /**
     * Parses a JSON response into a normalized patch generation result.
     * <p>
     * The method tolerates markdown code fences to support model outputs that
     * wrap JSON in fenced blocks.
     *
     * @param raw raw model response text expected to contain a JSON object
     * @return parsed patch generation result
     * @throws Exception if parsing fails
     */
    public static PatchGenerationResult fromJson(String raw) throws Exception {
        return MAPPER.readValue(stripMarkdownCodeFences(raw), PatchGenerationResult.class);
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
}

