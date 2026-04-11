package org.test.remediation;

import org.test.client.CopilotClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * GitHub Copilot-backed implementation of {@link PrAuthorBackend}.
 * <p>
 * This backend reuses {@link CopilotClient} to send a structured authoring
 * prompt and parse the returned JSON into a normalized {@link PrAuthorResult}.
 */
public class CopilotPrAuthorBackend implements PrAuthorBackend {

    /**
     * Minimal functional abstraction over the chat call so tests can inject a
     * fake backend without making network requests.
     */
    @FunctionalInterface
    interface CopilotChatInvoker {
        /**
         * Sends the prompt to Copilot and returns the raw assistant response.
         *
         * @param prompt prompt text to send
         * @return raw assistant response text
         * @throws Exception if the chat invocation fails
         */
        String chat(String prompt) throws Exception;
    }

    private final PrAuthorPromptBuilder promptBuilder;
    private final CopilotChatInvoker chatInvoker;
    private final String model;

    /**
     * Creates a Copilot backend using the supplied authoring configuration.
     *
     * @param config authoring configuration containing Copilot credentials and model options
     */
    public CopilotPrAuthorBackend(RemediationWorkflowConfig.AuthoringConfig config) {
        this(new PrAuthorPromptBuilder(), createChatInvoker(config), normalizeModel(config.copilotModel));
    }

    /**
     * Package-visible constructor used by tests to inject a fake chat invoker.
     *
     * @param promptBuilder prompt builder used to produce the Copilot request
     * @param chatInvoker injected chat function used to simulate Copilot
     * @param model configured model identifier
     */
    CopilotPrAuthorBackend(PrAuthorPromptBuilder promptBuilder,
                           CopilotChatInvoker chatInvoker,
                           String model) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
        this.chatInvoker = Objects.requireNonNull(chatInvoker, "chatInvoker must not be null");
        this.model = normalizeModel(model);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String backendName() {
        return AuthoringProviderType.COPILOT.name();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PrAuthorExecution execute(PrAuthorRequest request,
                                     PrChangePlan changePlan,
                                     RemediationWorkflowConfig.AuthoringConfig config) throws Exception {
        String prompt = promptBuilder.build(request, changePlan);
        String rawResponse = chatInvoker.chat(prompt);
        PrAuthorResult result = PrAuthorResult.fromJson(rawResponse);
        if (result.provider == null || result.provider.isBlank()) {
            result.provider = backendName();
        }
        if ((result.model == null || result.model.isBlank()) && model != null) {
            result.model = model;
        }
        return new PrAuthorExecution(prompt, rawResponse, result);
    }

    /**
     * Creates the Copilot chat invoker from the provided authoring config.
     *
     * @param config authoring configuration containing Copilot options
     * @return invoker that sends prompts via {@link CopilotClient}
     */
    private static CopilotChatInvoker createChatInvoker(RemediationWorkflowConfig.AuthoringConfig config) {
        CopilotClient client = createCopilotClient(config);
        return client::chat;
    }

    /**
     * Creates a configured {@link CopilotClient} using either an explicit token
     * file or standard environment variables.
     *
     * @param config authoring configuration containing Copilot options
     * @return configured Copilot client
     */
    private static CopilotClient createCopilotClient(RemediationWorkflowConfig.AuthoringConfig config) {
        try {
            String model = normalizeModel(config.copilotModel);
            if (config.copilotAuthTokenFile != null && !config.copilotAuthTokenFile.isBlank()) {
                Path tokenFile = Path.of(config.copilotAuthTokenFile).toAbsolutePath().normalize();
                String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (token.isBlank()) {
                    throw new IllegalArgumentException("Copilot auth token file is empty: " + tokenFile);
                }
                return new CopilotClient(token, model);
            }

            if (model == null) {
                return CopilotClient.fromEnvironment();
            }

            String token = firstNonBlank(
                    System.getenv("GITHUB_COPILOT_OAUTH_TOKEN"),
                    System.getenv("GITHUB_TOKEN"),
                    System.getenv("GH_TOKEN")
            );
            if (token == null) {
                throw new IllegalStateException("No GitHub OAuth token found for Copilot author backend.");
            }
            return new CopilotClient(token, model);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize CopilotPrAuthorBackend", e);
        }
    }

    /**
     * Normalizes the configured model identifier.
     *
     * @param value model identifier
     * @return normalized model identifier or {@code null}
     */
    private static String normalizeModel(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Returns the first non-blank token value from the supplied candidates.
     *
     * @param values token candidates
     * @return first non-blank token or {@code null}
     */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

