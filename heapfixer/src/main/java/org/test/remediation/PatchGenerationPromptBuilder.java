package org.test.remediation;

/**
 * Builds the provider prompt used by AI-backed patch generation backends.
 */
public class PatchGenerationPromptBuilder {

    /**
     * Builds the prompt text sent to an AI patch generation backend.
     *
     * @param request machine-oriented patch generation request
     * @return prompt string instructing the backend to return a single JSON object
     * @throws Exception if request serialization fails
     */
    public String build(PatchGenerationRequest request) throws Exception {
        return """
                You are a senior Java patch author working on an OutOfMemoryError remediation change.
                Read the structured patch-generation request below and return ONLY one valid JSON object.
                Do not include markdown fences, prose, or any extra text.

                GOALS:
                - Produce reviewable structured patch output for the candidate files only.
                - Stay within the file paths listed in the request.
                - Use the evidence and snippet contexts as anchors.
                - Do not invent repository files outside the request.
                - Keep changes minimal and focused on the memory issue.

                REQUIRED OUTPUT JSON SHAPE:
                {
                  "provider": "<string>",
                  "model": "<string or null>",
                  "summary": "<string>",
                  "structured_patch_files": [
                    {
                      "path": "<repo-relative path>",
                      "change_type": "<UPDATE|ADD|DELETE|CONFIGURE>",
                      "rationale": "<why this file should change>",
                      "hunks": [
                        {
                          "start_line": <number>,
                          "end_line": <number>,
                          "current_snippet": "<existing snippet>",
                           "current_text": "<exact existing text for the targeted line range>",
                          "proposed_edit_description": "<what edit should happen>",
                           "replacement_preview": "<preview text of the intended replacement>",
                           "replacement_text": "<exact replacement code/text for the targeted line range>"
                        }
                      ]
                    }
                  ],
                  "notes": ["<note 1>", "<note 2>"]
                }

                 IMPORTANT PATCH RULES:
                 - current_text must match the exact current file content for the specified line range.
                 - replacement_text must contain the exact code/text to write for that range.
                 - replacement_preview can be shorter, but replacement_text must be directly applicable.

                PATCH GENERATION REQUEST JSON:
                %s
                """.formatted(request.toJson());
    }
}

