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


