package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Planned change description for a single repository file.
 * <p>
 * This is intentionally a non-diff artifact: it explains why a file is likely
 * relevant, what kind of change is expected, and which evidence supports it.
 */
public class PlannedFileChange {

    @JsonProperty("path")
    public String path;

    @JsonProperty("change_type")
    public String changeType;

    @JsonProperty("reason")
    public String reason;

    @JsonProperty("matched_terms")
    public List<String> matchedTerms = new ArrayList<>();

    @JsonProperty("suggested_remediation_steps")
    public List<String> suggestedRemediationSteps = new ArrayList<>();

    @JsonProperty("evidence")
    public List<String> evidence = new ArrayList<>();
}

