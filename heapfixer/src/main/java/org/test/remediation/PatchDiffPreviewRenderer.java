package org.test.remediation;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a pseudo unified-diff preview from structured patch output.
 * <p>
 * This renderer is shared by deterministic and future AI-backed patch
 * backends so the preview format remains consistent across providers.
 */
public class PatchDiffPreviewRenderer {

    /**
     * Renders a human-readable pseudo diff preview for the supplied structured patch result.
     *
     * @param result structured patch result to render
     * @return diff preview text
     */
    public String render(PatchGenerationResult result) {
        List<String> lines = new ArrayList<>();
        for (StructuredPatchFile patchFile : result.structuredPatchFiles) {
            lines.add("--- a/" + patchFile.path);
            lines.add("+++ b/" + patchFile.path);
            for (StructuredPatchHunk hunk : patchFile.hunks) {
                lines.add("@@ lines " + hunk.startLine + "," + hunk.endLine + " @@");
                for (String currentLine : safeSplitLines(preferredCurrentText(hunk))) {
                    lines.add("- " + currentLine);
                }
                for (String previewLine : safeSplitLines(preferredReplacementText(hunk))) {
                    lines.add("+ " + previewLine);
                }
            }
            lines.add("");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String preferredCurrentText(StructuredPatchHunk hunk) {
        if (hunk == null) {
            return null;
        }
        return hunk.currentSnippet != null && !hunk.currentSnippet.isBlank()
                ? hunk.currentSnippet
                : hunk.currentText;
    }

    private String preferredReplacementText(StructuredPatchHunk hunk) {
        if (hunk == null) {
            return null;
        }
        return hunk.replacementPreview != null && !hunk.replacementPreview.isBlank()
                ? hunk.replacementPreview
                : hunk.replacementText;
    }

    /**
     * Splits a multi-line block into individual lines while tolerating null input.
     *
     * @param content multi-line content
     * @return individual lines, or an empty list for null/blank input
     */
    private List<String> safeSplitLines(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return List.of(content.split("\\R"));
    }
}

