package org.test.remediation;

/**
 * Builds the provider prompt used by AI-backed PR authoring backends.
 */
public class PrAuthorPromptBuilder {

    /**
     * Builds the prompt text sent to an AI authoring backend.
     *
     * @param request machine-oriented author request
     * @param changePlan deterministic file-level change plan
     * @return prompt string instructing the backend to return a single JSON object
     * @throws Exception if request or plan serialization fails
     */
    public String build(PrAuthorRequest request, PrChangePlan changePlan) throws Exception {
        return """
                You are a senior Java code change author working on an OutOfMemoryError remediation PR.
                Read the structured request and change plan below and return ONLY one valid JSON object.
                Do not include markdown fences, prose, or any extra text.

                GOALS:
                - Refine the candidate file changes into an implementation-oriented change proposal.
                - Stay within the candidate files only.
                - Base your reasoning on the remediation steps and evidence snippets.
                - Do not invent files outside the targeted retrieval set.
                - Keep suggestions minimal and focused on the OOM fix.

                REQUIRED OUTPUT JSON SHAPE:
                {
                  "provider": "<string>",
                  "model": "<string or null>",
                  "implementation_summary": "<string>",
                  "proposed_file_changes": [
                    {
                      "path": "<repo-relative path>",
                      "change_type": "<UPDATE|ADD|DELETE|CONFIGURE>",
                      "intent": "<what to change>",
                      "justification": "<why this file should change>",
                      "evidence": ["<evidence item 1>", "<evidence item 2>"],
                      "suggested_tests": ["<test or validation step>"]
                    }
                  ],
                  "validation_steps": ["<step 1>", "<step 2>"],
                  "risk_notes": ["<risk 1>", "<risk 2>"],
                  "confidence": "<HIGH|MEDIUM|LOW>"
                }

                AUTHOR REQUEST JSON:
                %s

                CHANGE PLAN JSON:
                %s
                """.formatted(request.toJson(), changePlan.toJson());
    }
}

