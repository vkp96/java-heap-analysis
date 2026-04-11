package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured change-plan artifact prepared for a future PR authoring step.
 */
public class PrChangePlan {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("generated_at")
    public String generatedAt = Instant.now().toString();

    @JsonProperty("branch_name")
    public String branchName;

    @JsonProperty("pr_title")
    public String prTitle;

    @JsonProperty("planned_file_changes")
    public List<PlannedFileChange> plannedFileChanges = new ArrayList<>();

    @JsonProperty("authoring_notes")
    public List<String> authoringNotes = new ArrayList<>();

    /**
     * Serializes this change plan to formatted JSON.
     *
     * @return formatted JSON representation of this change plan
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }
}

