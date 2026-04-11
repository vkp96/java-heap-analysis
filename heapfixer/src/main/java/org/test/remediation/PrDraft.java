package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Local PR draft payload generated from an {@code AnalysisResult} and targeted
 * retrieval context.
 * <p>
 * This DTO is persisted as an artifact for review and later consumption by a
 * future PR authoring agent or GitHub integration layer.
 */
public class PrDraft {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("branch_name")
    public String branchName;

    @JsonProperty("title")
    public String title;

    @JsonProperty("body")
    public String body;

    @JsonProperty("candidate_files")
    public List<String> candidateFiles = new ArrayList<>();

    @JsonProperty("rationale")
    public String rationale;

    @JsonProperty("generated_at")
    public String generatedAt = Instant.now().toString();

    /**
     * Serializes this draft payload to formatted JSON.
     *
     * @return formatted JSON representation of this draft
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }
}


