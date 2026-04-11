package org.test.remediation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CopilotPrAuthorBackendTest {

    @Test
    void buildsPromptAndParsesFakeCopilotResponse() throws Exception {
        PrAuthorRequest request = new PrAuthorRequest();
        request.branchName = "oom-fix/heapdumper-test";
        request.prTitle = "[OOM Fix] Mitigate OOM risk in HeapDumper";
        request.prBody = "## Problem\nA retained list grows unbounded.";
        request.summary = "Heap analysis indicates a retained collection.";
        request.confidence = "HIGH";
        request.rootCauseDescription = "ArrayList retained in HeapDumper.main";
        request.responsibleClass = "org.test.HeapDumper";
        request.responsibleMethod = "main";
        request.remediationSteps.add("Avoid accumulating the entire dataset in memory.");

        PrAuthorRequest.AuthorFileContext file = new PrAuthorRequest.AuthorFileContext();
        file.path = "src/main/java/org/test/HeapDumper.java";
        file.score = 50;
        file.matchedTerms.add("HeapDumper");
        PrAuthorRequest.SnippetReference snippet = new PrAuthorRequest.SnippetReference();
        snippet.startLine = 11;
        snippet.endLine = 23;
        snippet.content = "11 | public static void main(String[] args) {";
        snippet.matchedTerms.add("main");
        file.snippets.add(snippet);
        request.candidateFiles.add(file);

        PrChangePlan changePlan = new PrChangePlan();
        changePlan.branchName = request.branchName;
        changePlan.prTitle = request.prTitle;
        PlannedFileChange plannedFileChange = new PlannedFileChange();
        plannedFileChange.path = file.path;
        plannedFileChange.changeType = "UPDATE";
        plannedFileChange.reason = "Main method retains too much data.";
        plannedFileChange.evidence.add("lines 11-23: list grows without release");
        plannedFileChange.suggestedRemediationSteps.add("Clear or bound the collection.");
        changePlan.plannedFileChanges.add(plannedFileChange);

        RemediationWorkflowConfig.AuthoringConfig config = RemediationWorkflowConfig.AuthoringConfig.defaults();
        config.provider = AuthoringProviderType.COPILOT.name();
        config.copilotModel = "gpt-4.1";

        CopilotPrAuthorBackend backend = new CopilotPrAuthorBackend(
                new PrAuthorPromptBuilder(),
                prompt -> {
                    assertTrue(prompt.contains("AUTHOR REQUEST JSON"));
                    assertTrue(prompt.contains("CHANGE PLAN JSON"));
                    return """
                            {
                              "provider": "COPILOT",
                              "model": "gpt-4.1",
                              "implementation_summary": "Update HeapDumper.main to avoid retaining the full dataset in memory.",
                              "proposed_file_changes": [
                                {
                                  "path": "src/main/java/org/test/HeapDumper.java",
                                  "change_type": "UPDATE",
                                  "intent": "Replace the unbounded retained collection with a bounded or streaming approach.",
                                  "justification": "The main method retains a large backing collection.",
                                  "evidence": ["lines 11-23: list grows without release"],
                                  "suggested_tests": ["Run the heap dumper with bounded memory and confirm reduced retention."]
                                }
                              ],
                              "validation_steps": ["Run the heap dumper with a low -Xmx and verify memory stabilizes."],
                              "risk_notes": ["Changing allocation behavior may alter the test semantics."],
                              "confidence": "HIGH"
                            }
                            """;
                },
                "gpt-4.1"
        );

        PrAuthorExecution execution = backend.execute(request, changePlan, config);

        assertNotNull(execution.promptText());
        assertNotNull(execution.rawResponse());
        assertEquals("COPILOT", execution.result().provider);
        assertEquals("gpt-4.1", execution.result().model);
        assertEquals(1, execution.result().proposedFileChanges.size());
        assertEquals("src/main/java/org/test/HeapDumper.java", execution.result().proposedFileChanges.get(0).path);
    }
}

