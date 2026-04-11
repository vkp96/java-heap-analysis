package org.test.remediation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CopilotPatchBackendTest {

    @Test
    void buildsPromptAndParsesFakeCopilotPatchResponse() throws Exception {
        PatchGenerationRequest request = new PatchGenerationRequest();
        request.provider = PatchProviderType.COPILOT.name();
        request.branchName = "oom-fix/heapdumper-test";
        request.prTitle = "[OOM Fix] Mitigate OOM risk in HeapDumper";
        request.summary = "Generate a minimal patch for HeapDumper memory retention.";

        PatchGenerationRequest.PatchTargetFile file = new PatchGenerationRequest.PatchTargetFile();
        file.path = "src/main/java/org/test/HeapDumper.java";
        file.changeType = "UPDATE";
        file.intent = "Replace the retained unbounded collection with a bounded approach.";
        file.justification = "HeapDumper.main retains too much data in memory.";
        file.evidence.add("lines 11-23: list grows without release");
        file.suggestedTests.add("Run with low -Xmx and verify reduced retention.");

        PatchGenerationRequest.SnippetContext snippet = new PatchGenerationRequest.SnippetContext();
        snippet.startLine = 11;
        snippet.endLine = 23;
        snippet.content = "11 | public static void main(String[] args) {\n12 |     java.util.List<byte[]> list = new java.util.ArrayList<>();";
        snippet.matchedTerms.add("main");
        snippet.matchedTerms.add("ArrayList");
        file.snippetContexts.add(snippet);
        request.files.add(file);

        RemediationWorkflowConfig.PatchGenerationConfig config = RemediationWorkflowConfig.PatchGenerationConfig.defaults();
        config.provider = PatchProviderType.COPILOT.name();
        config.copilotModel = "gpt-4.1";

        CopilotPatchBackend backend = new CopilotPatchBackend(
                new PatchGenerationPromptBuilder(),
                new PatchDiffPreviewRenderer(),
                prompt -> {
                    assertTrue(prompt.contains("PATCH GENERATION REQUEST JSON"));
                    assertTrue(prompt.contains("HeapDumper.java"));
                    return """
                            {
                              "provider": "COPILOT",
                              "model": "gpt-4.1",
                              "summary": "Update HeapDumper.main to avoid retaining a large unbounded collection.",
                              "structured_patch_files": [
                                {
                                  "path": "src/main/java/org/test/HeapDumper.java",
                                  "change_type": "UPDATE",
                                  "rationale": "The main method retains a large backing collection.",
                                  "hunks": [
                                    {
                                      "start_line": 11,
                                      "end_line": 23,
                                      "current_snippet": "11 | public static void main(String[] args) {",
                                      "proposed_edit_description": "Replace the retained ArrayList with a bounded or streaming alternative.",
                                      "replacement_preview": "Use a bounded data structure and release references after processing."
                                    }
                                  ]
                                }
                              ],
                              "notes": ["Review the replacement preview before applying changes."]
                            }
                            """;
                },
                "gpt-4.1"
        );

        PatchGenerationExecution execution = backend.execute(request, config);

        assertNotNull(execution.promptText());
        assertNotNull(execution.rawResponse());
        assertNotNull(execution.diffPreview());
        assertEquals("COPILOT", execution.result().provider);
        assertEquals("gpt-4.1", execution.result().model);
        assertEquals(1, execution.result().structuredPatchFiles.size());
        assertTrue(execution.diffPreview().contains("--- a/src/main/java/org/test/HeapDumper.java"));
    }
}

