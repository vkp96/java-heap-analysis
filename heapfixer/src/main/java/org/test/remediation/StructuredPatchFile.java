package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured patch output for a single file.
 */
public class StructuredPatchFile {

    @JsonProperty("path")
    public String path;

    @JsonProperty("change_type")
    public String changeType;

    @JsonProperty("rationale")
    public String rationale;

    @JsonProperty("hunks")
    public List<StructuredPatchHunk> hunks = new ArrayList<>();
}

