package org.test.remediation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Orchestrates the local-only remediation draft workflow.
 * <p>
 * The workflow gathers targeted context from the repository, produces a draft
 * PR payload, evaluates policy gates, and writes all artifacts to disk.
 */
public class PrAutomationDraftWorkflow {

    private static final Logger LOG = LoggerFactory.getLogger(PrAutomationDraftWorkflow.class);
    private static final String RETRIEVED_CONTEXT_FILE = "targeted_retrieval_context.json";
    private static final String PR_DRAFT_FILE = "pr_draft.json";
    private static final String PR_POLICY_FILE = "pr_policy_decision.json";

    private final TargetedRetrievalService retrievalService;
    private final PrDraftComposer draftComposer;
    private final PrPolicyChecker policyChecker;
    private final PrAuthorAgent prAuthorAgent;
    private final PatchGenerationRequestBuilder patchGenerationRequestBuilder;
    private final PatchApplicationRequestBuilder patchApplicationRequestBuilder;
    private final PrGenerationRequestBuilder prGenerationRequestBuilder;
    private final RemediationWorkflowConfig config;

    /**
     * Creates a workflow instance backed by the supplied configuration.
     *
     * @param config workflow configuration controlling retrieval and policy behavior
     */
    public PrAutomationDraftWorkflow(RemediationWorkflowConfig config) {
        this.retrievalService = new TargetedRetrievalService();
        this.draftComposer = new PrDraftComposer();
        this.policyChecker = new PrPolicyChecker();
        this.prAuthorAgent = new PrAuthorAgent();
        this.patchGenerationRequestBuilder = new PatchGenerationRequestBuilder();
        this.patchApplicationRequestBuilder = new PatchApplicationRequestBuilder();
        this.prGenerationRequestBuilder = new PrGenerationRequestBuilder();
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Executes the full draft workflow and persists its intermediate artifacts.
     *
     * @param result structured heap analysis result
     * @param repoRoot repository root to scan for targeted context
     * @param outputDir destination directory for generated workflow artifacts
     * @return final policy decision for the generated draft
     * @throws Exception if retrieval, serialization, or file writes fail
     */
    public PrPolicyDecision run(AnalysisResult result, Path repoRoot, Path outputDir) throws Exception {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");

        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOutputDir);

        LOG.info("Starting PR automation draft workflow. repoRoot={}, outputDir={}", normalizedRepoRoot, normalizedOutputDir);

        RetrievedContext context = retrievalService.collect(normalizedRepoRoot, result, config);
        Path contextFile = normalizedOutputDir.resolve(RETRIEVED_CONTEXT_FILE);
        Files.writeString(contextFile, context.toJson());
        LOG.info("Wrote targeted retrieval context to {}", contextFile);

        PrDraft draft = draftComposer.compose(result, context, config.prPolicy);
        Path draftFile = normalizedOutputDir.resolve(PR_DRAFT_FILE);
        Files.writeString(draftFile, draft.toJson());
        LOG.info("Wrote PR draft artifact to {}", draftFile);

        PrPolicyDecision decision = policyChecker.evaluate(result, context, draft, config);
        Path decisionFile = normalizedOutputDir.resolve(PR_POLICY_FILE);
        Files.writeString(decisionFile, decision.toJson());
        LOG.info("Wrote PR policy decision to {}", decisionFile);

        if (decision.allowed && config.authoring.enabled) {
            PrAuthorArtifacts authorArtifacts = prAuthorAgent.createArtifacts(result, context, draft, decision, config);

            Path authorRequestFile = normalizedOutputDir.resolve(config.authoring.requestFileName);
            Files.writeString(authorRequestFile, authorArtifacts.request().toJson());
            LOG.info("Wrote PR author request artifact to {}", authorRequestFile);

            Path changePlanFile = normalizedOutputDir.resolve(config.authoring.changePlanFileName);
            Files.writeString(changePlanFile, authorArtifacts.changePlan().toJson());
            LOG.info("Wrote PR change plan artifact to {}", changePlanFile);

            PrAuthorBackend backend = PrAuthorBackendFactory.create(config.authoring);
            LOG.info("Executing PR author backend '{}'", backend.backendName());
            PrAuthorExecution execution = backend.execute(authorArtifacts.request(), authorArtifacts.changePlan(), config.authoring);

            if (execution.promptText() != null && !execution.promptText().isBlank()) {
                Path promptFile = normalizedOutputDir.resolve(config.authoring.promptFileName);
                Files.writeString(promptFile, execution.promptText());
                LOG.info("Wrote PR author prompt to {}", promptFile);
            }

            if (execution.rawResponse() != null && !execution.rawResponse().isBlank()) {
                Path rawResponseFile = normalizedOutputDir.resolve(config.authoring.rawResponseFileName);
                Files.writeString(rawResponseFile, execution.rawResponse());
                LOG.info("Wrote PR author raw response to {}", rawResponseFile);
            }

            Path resultFile = normalizedOutputDir.resolve(config.authoring.resultFileName);
            Files.writeString(resultFile, execution.result().toJson());
            LOG.info("Wrote PR author normalized result to {}", resultFile);

            if (config.patchGeneration.enabled) {
                PatchGenerationRequest patchRequest = patchGenerationRequestBuilder.build(
                        authorArtifacts.changePlan(),
                        execution.result(),
                        context,
                        config.patchGeneration.provider,
                        config.patchGeneration);

                Path patchRequestFile = normalizedOutputDir.resolve(config.patchGeneration.requestFileName);
                Files.writeString(patchRequestFile, patchRequest.toJson());
                LOG.info("Wrote patch generation request to {}", patchRequestFile);

                PatchGenerationBackend patchBackend = PatchGenerationBackendFactory.create(config.patchGeneration);
                LOG.info("Executing patch generation backend '{}'", patchBackend.backendName());
                PatchGenerationExecution patchExecution = patchBackend.execute(patchRequest, config.patchGeneration);

                if (patchExecution.promptText() != null && !patchExecution.promptText().isBlank()) {
                    Path patchPromptFile = normalizedOutputDir.resolve(config.patchGeneration.promptFileName);
                    Files.writeString(patchPromptFile, patchExecution.promptText());
                    LOG.info("Wrote patch generation prompt to {}", patchPromptFile);
                }

                if (patchExecution.rawResponse() != null && !patchExecution.rawResponse().isBlank()) {
                    Path patchRawResponseFile = normalizedOutputDir.resolve(config.patchGeneration.rawResponseFileName);
                    Files.writeString(patchRawResponseFile, patchExecution.rawResponse());
                    LOG.info("Wrote patch generation raw response to {}", patchRawResponseFile);
                }

                Path patchResultFile = normalizedOutputDir.resolve(config.patchGeneration.resultFileName);
                Files.writeString(patchResultFile, patchExecution.result().toJson());
                LOG.info("Wrote patch generation result to {}", patchResultFile);

                if (patchExecution.diffPreview() != null && !patchExecution.diffPreview().isBlank()) {
                    Path diffPreviewFile = normalizedOutputDir.resolve(config.patchGeneration.diffPreviewFileName);
                    Files.writeString(diffPreviewFile, patchExecution.diffPreview());
                    LOG.info("Wrote patch diff preview to {}", diffPreviewFile);
                }

                if (config.patchApplication.enabled) {
                    PatchApplicationRequest patchApplicationRequest = patchApplicationRequestBuilder.build(
                            normalizedRepoRoot,
                            patchRequest,
                            patchExecution.result(),
                            config.patchApplication);

                    Path patchApplicationRequestFile = normalizedOutputDir.resolve(config.patchApplication.requestFileName);
                    Files.writeString(patchApplicationRequestFile, patchApplicationRequest.toJson());
                    LOG.info("Wrote patch application request to {}", patchApplicationRequestFile);

                    PatchApplicationBackend patchApplicationBackend = PatchApplicationBackendFactory.create(config.patchApplication);
                    LOG.info("Executing patch application backend '{}' on branch '{}'", patchApplicationBackend.backendName(), patchApplicationRequest.branchName);
                    PatchApplicationExecution patchApplicationExecution = patchApplicationBackend.execute(patchApplicationRequest, config.patchApplication);

                    Path patchApplicationResultFile = normalizedOutputDir.resolve(config.patchApplication.resultFileName);
                    Files.writeString(patchApplicationResultFile, patchApplicationExecution.result().toJson());
                    LOG.info("Wrote patch application result to {}", patchApplicationResultFile);

                    if (patchApplicationExecution.finalDiff() != null && !patchApplicationExecution.finalDiff().isBlank()) {
                        Path finalDiffFile = normalizedOutputDir.resolve(config.patchApplication.finalDiffFileName);
                        Files.writeString(finalDiffFile, patchApplicationExecution.finalDiff());
                        LOG.info("Wrote applied patch git diff to {}", finalDiffFile);
                    }

                    if (patchApplicationExecution.validationOutput() != null && !patchApplicationExecution.validationOutput().isBlank()) {
                        Path validationOutputFile = normalizedOutputDir.resolve(config.patchApplication.validationOutputFileName);
                        Files.writeString(validationOutputFile, patchApplicationExecution.validationOutput());
                        LOG.info("Wrote patch application validation output to {}", validationOutputFile);
                    }

                    if (!patchApplicationExecution.result().successful) {
                        throw new IllegalStateException("Patch application completed unsuccessfully: " + patchApplicationExecution.result().errors);
                    }

                    if (config.prGeneration.enabled) {
                        PrGenerationRequest prGenerationRequest = prGenerationRequestBuilder.build(
                                normalizedRepoRoot,
                                draft,
                                execution.result(),
                                patchApplicationExecution,
                                config.prGeneration);

                        Path prGenerationRequestFile = normalizedOutputDir.resolve(config.prGeneration.requestFileName);
                        Files.writeString(prGenerationRequestFile, prGenerationRequest.toJson());
                        LOG.info("Wrote PR generation request to {}", prGenerationRequestFile);

                        PrGenerationBackend prGenerationBackend = PrGenerationBackendFactory.create(config.prGeneration);
                        LOG.info("Executing PR generation backend '{}' for branch '{}'", prGenerationBackend.backendName(), prGenerationRequest.headBranch);
                        PrGenerationExecution prGenerationExecution = prGenerationBackend.execute(prGenerationRequest, config.prGeneration);

                        Path prGenerationResultFile = normalizedOutputDir.resolve(config.prGeneration.resultFileName);
                        Files.writeString(prGenerationResultFile, prGenerationExecution.result().toJson());
                        LOG.info("Wrote PR generation result to {}", prGenerationResultFile);

                        if (prGenerationExecution.previewMarkdown() != null && !prGenerationExecution.previewMarkdown().isBlank()) {
                            Path prPreviewFile = normalizedOutputDir.resolve(config.prGeneration.previewFileName);
                            Files.writeString(prPreviewFile, prGenerationExecution.previewMarkdown());
                            LOG.info("Wrote PR preview markdown to {}", prPreviewFile);
                        }
                    } else {
                        LOG.info("Skipping PR generation because it is disabled by configuration.");
                    }
                } else {
                    LOG.info("Skipping patch application because it is disabled by configuration.");
                }
            } else {
                LOG.info("Skipping patch generation because it is disabled by configuration.");
            }
        } else if (!decision.allowed) {
            LOG.info("Skipping PrAuthorAgent artifacts because policy did not pass.");
        } else {
            LOG.info("Skipping PrAuthorAgent artifacts because authoring is disabled by configuration.");
        }

        LOG.info("PR automation draft workflow completed. allowed={}, failures={}, warnings={}",
                decision.allowed, decision.failures.size(), decision.warnings.size());
        return decision;
    }

    /**
     * Command-line entry point for running the remediation draft workflow
     * directly against a saved {@link AnalysisResult} JSON file.
     *
     * @param args CLI arguments in the form
     *             {@code <analysis-result-json-file> <repo-root> [output-dir] [config-file]}
     * @throws Exception if workflow execution fails
     */
    public static void main(String[] args) throws Exception {
        if (args == null || args.length < 2 || args.length > 4) {
            LOG.error("Usage: PrAutomationDraftWorkflow <analysis-result-json-file> <repo-root> [output-dir] [config-file]");
            System.exit(1);
        }

        Path analysisResultFile = Path.of(args[0]).toAbsolutePath().normalize();
        Path repoRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Path outputDir = args.length >= 3
                ? Path.of(args[2]).toAbsolutePath().normalize()
                : analysisResultFile.getParent();

        RemediationWorkflowConfig config = args.length == 4
                ? RemediationWorkflowConfig.load(Path.of(args[3]))
                : RemediationWorkflowConfig.loadDefault();

        AnalysisResult result = AnalysisResult.fromJson(Files.readString(analysisResultFile));
        PrPolicyDecision decision = new PrAutomationDraftWorkflow(config).run(result, repoRoot, outputDir);
        if (!decision.allowed) {
            LOG.warn("PR draft workflow blocked by policy. Failures={}", decision.failures);
            System.exit(2);
        }
    }
}


