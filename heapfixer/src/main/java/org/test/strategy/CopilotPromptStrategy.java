package org.test.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;
import org.test.MATRunner;
import org.test.MatReportExtractor;
import org.test.client.CopilotClient;
import org.test.parser.copilot.CopilotResponseParser;
import org.test.parser.copilot.LeakSuspectPromptCreator;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * API-driven Copilot strategy.
 * <p>
 * Workflow:
 * <ol>
 *   <li>Generates the full prompt via {@link LeakSuspectPromptCreator} and writes
 *       it to {@code <workDir>/copilot_prompt.txt}.</li>
 *   <li>Invokes {@link CopilotClient} to submit the prompt to GitHub Copilot.</li>
 *   <li>Optionally writes the raw Copilot response to
 *       {@code <workDir>/copilot_response.json} for debugging.</li>
 *   <li>Parses the response text with {@link CopilotResponseParser}.</li>
 * </ol>
 * <p>
 * Configuration:
 * <ul>
 *   <li>{@code COPILOT_AUTH_TOKEN_FILE} – optional path to a local token file;
 *       if supplied, the strategy uses {@link CopilotClient#fromTokenFile(Path)}</li>
 *   <li>{@code GITHUB_COPILOT_OAUTH_TOKEN} – preferred GitHub OAuth token</li>
 *   <li>{@code GITHUB_TOKEN} / {@code GH_TOKEN} – alternative token env vars</li>
 *   <li>{@code COPILOT_MODEL} – optional model override</li>
 * </ul>
 */
public class CopilotPromptStrategy implements HeapAnalysisStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(CopilotPromptStrategy.class);

    private static final String PROMPT_FILE_NAME   = "copilot_prompt.txt";
    private static final String RESPONSE_FILE_NAME = "copilot_response.json";

    private final Path workDir;
    private final CopilotClient copilotClient;

    public CopilotPromptStrategy(Path workDir) {
        this(workDir, null);
    }

    public CopilotPromptStrategy(Path workDir, Path copilotAuthTokenFile) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.copilotClient = createCopilotClient(copilotAuthTokenFile);
    }

    public CopilotPromptStrategy(Path workDir, long timeoutMinutes) {
        this(workDir);
        LOG.info("[CopilotPrompt] Legacy timeout parameter ({}) is ignored because Copilot requests are now executed directly via CopilotClient.", timeoutMinutes);
    }

    @Override
    public AnalysisResult analyze(MatReportExtractor.MatReport report, int topN) throws Exception {
        Path analysisWorkDir = resolveAnalysisWorkDir(report);
        Files.createDirectories(analysisWorkDir);

        // 1. Build and save the prompt
        LOG.info("[CopilotPrompt] Building prompt (topN={})", topN);
        String prompt = new LeakSuspectPromptCreator()
                        .withTopN(topN)
                        .buildFromReport(report);

        Path promptFile = analysisWorkDir.resolve(PROMPT_FILE_NAME);
        Files.writeString(promptFile, prompt);
        LOG.info("[CopilotPrompt] Prompt written to {} ({} chars)", promptFile, prompt.length());

        // 2. Invoke Copilot directly
        LOG.info("[CopilotPrompt] Sending prompt to CopilotClient");
        String rawResponse = copilotClient.chat(prompt);
        LOG.info("[CopilotPrompt] Received raw Copilot response ({} chars)", rawResponse.length());

        Path responseFile = analysisWorkDir.resolve(RESPONSE_FILE_NAME);
        Files.writeString(responseFile, rawResponse);
        LOG.info("[CopilotPrompt] Raw Copilot response written to {}", responseFile);

        // 3. Parse the response
        LOG.info("[CopilotPrompt] Parsing Copilot response into AnalysisResult");
        AnalysisResult result = CopilotResponseParser.parse(rawResponse);

        if (result.heapDumpPath == null || result.heapDumpPath.isBlank()) {
            result.heapDumpPath = report.heapDumpPath;
        }
        LOG.info("[CopilotPrompt] Analysis complete. Confidence={}", result.confidence);
        return result;
    }

    @Override
    public String strategyName() {
        return "Copilot Prompt (file-based)";
    }

    private CopilotClient createCopilotClient(Path copilotAuthTokenFile) {
        try {
            if (copilotAuthTokenFile != null) {
                Path normalizedTokenFile = copilotAuthTokenFile.toAbsolutePath().normalize();
                LOG.info("[CopilotPrompt] Creating CopilotClient using token file {}", normalizedTokenFile);
                return CopilotClient.fromTokenFile(normalizedTokenFile);
            }

            LOG.info("[CopilotPrompt] Creating CopilotClient using environment variables");
            return CopilotClient.fromEnvironment();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize CopilotClient", e);
        }
    }

    private Path resolveAnalysisWorkDir(MatReportExtractor.MatReport report) {
        if (report != null && report.reportsDir != null && !report.reportsDir.isBlank()) {
            try {
                Path reportsDir = Path.of(report.reportsDir).toAbsolutePath().normalize();
                LOG.info("[CopilotPrompt] Using MAT reports directory for prompt/response artifacts: {}", reportsDir);
                return reportsDir;
            } catch (Exception e) {
                LOG.warn("[CopilotPrompt] Failed to use reportsDir='{}' from MatReport. Falling back to derived work directory.",
                        report.reportsDir, e);
            }
        }

        if (report == null || report.heapDumpPath == null || report.heapDumpPath.isBlank()) {
            return workDir;
        }

        try {
            String heapBaseName = MATRunner.stripExtension(Path.of(report.heapDumpPath)
                    .getFileName()
                    .toString());
            return workDir.resolve(heapBaseName).toAbsolutePath().normalize();
        } catch (Exception e) {
            LOG.warn("[CopilotPrompt] Failed to derive per-analysis work directory from heapDumpPath='{}'. Using base workDir {}.",
                    report.heapDumpPath, workDir, e);
            return workDir;
        }
    }
}

