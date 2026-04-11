package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of evaluating whether a generated PR draft satisfies the configured
 * remediation policy gates.
 */
public class PrPolicyDecision {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("allowed")
    public boolean allowed;

    @JsonProperty("failures")
    public List<String> failures = new ArrayList<>();

    @JsonProperty("warnings")
    public List<String> warnings = new ArrayList<>();

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("evaluated_at")
    public String evaluatedAt = Instant.now().toString();

    /**
     * Serializes this policy decision to formatted JSON.
     *
     * @return formatted JSON representation of this decision
     * @throws Exception if serialization fails
     */
    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }
}


