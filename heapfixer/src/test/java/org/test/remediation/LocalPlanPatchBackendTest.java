package org.test.remediation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalPlanPatchBackendTest {

    @Test
    void createsStructuredPatchResultAndDiffPreview() {
        PatchGenerationRequest request = new PatchGenerationRequest();
        request.provider = PatchProviderType.LOCAL_PLAN.name();
        request.branchName = "oom-fix/heapdumper-test";
        request.prTitle = "[OOM Fix] Mitigate OOM risk in HeapDumper";
        request.summary = "Reduce memory retention in HeapDumper.main.";

        PatchGenerationRequest.PatchTargetFile file = new PatchGenerationRequest.PatchTargetFile();
        file.path = "src/main/java/org/test/HeapDumper.java";
        file.changeType = "UPDATE";
        file.intent = "Replace the unbounded retained collection with a bounded or streaming approach.";
        file.justification = "The main method retains a large backing collection.";
        file.evidence.add("lines 11-23: list grows without release");
        file.suggestedTests.add("Run the heap dumper with bounded memory and confirm reduced retention.");

        PatchGenerationRequest.SnippetContext snippet = new PatchGenerationRequest.SnippetContext();
        snippet.startLine = 11;
        snippet.endLine = 24;
        snippet.content = """
                11 |     public static void main(String[] args) {
                12 |         LOGGER.info(\"Starting heap allocation test.\");
                13 |
                14 |         // Keep references to the arrays so they are not garbage-collected
                15 |         java.util.List<byte[]> list = new java.util.ArrayList<>();
                16 |         final int ONE_MB = 1024 * 1024;
                17 |         int allocatedMb = 0;
                18 |
                19 |         try {
                20 |             while (true) {
                21 |                 list.add(new byte[ONE_MB]);
                22 |                 allocatedMb++;
                23 |             }
                24 |         } catch (OutOfMemoryError oom) {
                """;
        snippet.matchedTerms.add("main");
        snippet.matchedTerms.add("ArrayList");
        file.snippetContexts.add(snippet);
        request.files.add(file);

        RemediationWorkflowConfig.PatchGenerationConfig config = RemediationWorkflowConfig.PatchGenerationConfig.defaults();
        PatchGenerationExecution execution = new LocalPlanPatchBackend().execute(request, config);

        assertNotNull(execution.result());
        assertNotNull(execution.diffPreview());
        assertEquals("LOCAL_PLAN", execution.result().provider);
        assertEquals(1, execution.result().structuredPatchFiles.size());
        assertEquals("src/main/java/org/test/HeapDumper.java", execution.result().structuredPatchFiles.get(0).path);
        assertFalse(execution.result().structuredPatchFiles.get(0).hunks.isEmpty());
        assertNotNull(execution.result().structuredPatchFiles.get(0).hunks.get(0).currentText);
        assertTrue(execution.result().structuredPatchFiles.get(0).hunks.get(0).replacementText.contains("latestChunk = new byte[ONE_MB];"));
        assertTrue(execution.diffPreview().contains("--- a/src/main/java/org/test/HeapDumper.java"));
    }
}

