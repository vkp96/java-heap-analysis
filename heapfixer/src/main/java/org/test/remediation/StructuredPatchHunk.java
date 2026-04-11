package org.test.remediation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reviewable structured patch hunk.
 * <p>
 * This hunk does not apply edits itself; it describes where a change should be
 * made, what current context it is anchored to, and what the intended edit is.
 */
public class StructuredPatchHunk {

    @JsonProperty("start_line")
    public int startLine;

    @JsonProperty("end_line")
    public int endLine;

    @JsonProperty("current_snippet")
    public String currentSnippet;

    @JsonProperty("current_text")
    public String currentText;

    @JsonProperty("proposed_edit_description")
    public String proposedEditDescription;

    @JsonProperty("replacement_preview")
    public String replacementPreview;

    @JsonProperty("replacement_text")
    public String replacementText;
}

