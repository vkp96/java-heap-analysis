package org.test.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class CopilotClient {

    private static final Logger LOG = LoggerFactory.getLogger(CopilotClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Unofficial / internal endpoints. Subject to change by GitHub.
    private static final String COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token";
    private static final String COPILOT_CHAT_URL = "https://api.githubcopilot.com/chat/completions";

    // These headers are commonly expected by Copilot-backed clients.
    private static final String DEFAULT_EDITOR_VERSION = "vscode/1.99.0";
    private static final String DEFAULT_PLUGIN_VERSION = "copilot-chat/0.26.0";
    private static final String DEFAULT_USER_AGENT = "GitHubCopilotChat/0.26.0";

    private final HttpClient httpClient;
    private final String githubOAuthToken;
    private final String model;
    private final String editorVersion;
    private final String pluginVersion;
    private final String userAgent;
    private final String machineId;
    private final String sessionId;

    private volatile CopilotAccessToken cachedAccessToken;

    public CopilotClient(String githubOAuthToken) {
        this(githubOAuthToken, null);
    }

    public CopilotClient(String githubOAuthToken, String model) {
        this(
                githubOAuthToken,
                model,
                DEFAULT_EDITOR_VERSION,
                DEFAULT_PLUGIN_VERSION,
                DEFAULT_USER_AGENT
        );
    }

    public CopilotClient(
            String githubOAuthToken,
            String model,
            String editorVersion,
            String pluginVersion,
            String userAgent
    ) {
        this.githubOAuthToken = Objects.requireNonNull(githubOAuthToken, "githubOAuthToken must not be null").trim();
        this.model = (model == null || model.isBlank()) ? null : model.trim();
        this.editorVersion = Objects.requireNonNull(editorVersion, "editorVersion must not be null");
        this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion must not be null");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent must not be null");
        this.machineId = UUID.randomUUID().toString();
        this.sessionId = UUID.randomUUID().toString();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Convenience factory that reads the GitHub OAuth token from env vars.
     * Tries:
     * - GITHUB_COPILOT_OAUTH_TOKEN
     * - GITHUB_TOKEN
     * - GH_TOKEN
     */
    public static CopilotClient fromEnvironment() {
        String token = firstNonBlank(
                System.getenv("GITHUB_COPILOT_OAUTH_TOKEN"),
                System.getenv("GITHUB_TOKEN"),
                System.getenv("GH_TOKEN")
        );

        if (token == null) {
            throw new IllegalStateException(
                    "No GitHub OAuth token found. Set one of: " +
                            "GITHUB_COPILOT_OAUTH_TOKEN, GITHUB_TOKEN, GH_TOKEN");
        }

        String model = blankToNull(System.getenv("COPILOT_MODEL"));
        return new CopilotClient(token, model);
    }

    /**
     * Convenience factory that reads the token from a local file.
     */
    public static CopilotClient fromTokenFile(Path tokenFile) throws IOException {
        Path normalized = tokenFile.toAbsolutePath().normalize();
        String token = Files.readString(normalized, StandardCharsets.UTF_8).trim();
        if (token.isBlank()) {
            throw new IllegalArgumentException("Token file is empty: " + normalized);
        }
        LOG.info("Loaded GitHub OAuth token from {}", normalized);
        return new CopilotClient(token, blankToNull(System.getenv("COPILOT_MODEL")));
    }

    public String chat(String prompt) throws Exception {
        Objects.requireNonNull(prompt, "prompt must not be null");
        return chat(List.of(new ChatMessage("user", prompt)));
    }

    public String chat(List<ChatMessage> messages) throws Exception {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be null or empty");
        }

        CopilotAccessToken accessToken = ensureAccessToken();

        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode messagesNode = body.putArray("messages");

        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            ObjectNode msg = messagesNode.addObject();
            msg.put("role", message.role());
            msg.put("content", message.content());
        }

        body.put("stream", false);
        body.put("temperature", 0.1);

        if (model != null) {
            body.put("model", model);
        }

        String requestBody = MAPPER.writeValueAsString(body);

        LOG.info("Sending Copilot chat request. messages={}, model={}", messages.size(), model);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(COPILOT_CHAT_URL))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + accessToken.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Editor-Version", editorVersion)
                .header("Editor-Plugin-Version", pluginVersion)
                .header("User-Agent", userAgent)
                .header("OpenAI-Intent", "conversation-panel")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("VScode-SessionId", sessionId)
                .header("VScode-MachineId", machineId)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        LOG.info("Copilot chat response status={}", response.statusCode());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Copilot chat request failed. HTTP " + response.statusCode() +
                            ", body: " + truncate(response.body(), 2000));
        }

        JsonNode root = MAPPER.readTree(response.body());
        String assistantText = extractAssistantText(root);

        LOG.info("Received Copilot response. chars={}", assistantText.length());
        return assistantText;
    }

    /**
     * Returns the raw JSON payload from Copilot instead of extracting only the assistant text.
     */
    public JsonNode chatRaw(List<ChatMessage> messages) throws Exception {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be null or empty");
        }

        CopilotAccessToken accessToken = ensureAccessToken();

        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode messagesNode = body.putArray("messages");

        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            ObjectNode msg = messagesNode.addObject();
            msg.put("role", message.role());
            msg.put("content", message.content());
        }

        body.put("stream", false);
        body.put("temperature", 0.1);
        if (model != null) {
            body.put("model", model);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(COPILOT_CHAT_URL))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + accessToken.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Editor-Version", editorVersion)
                .header("Editor-Plugin-Version", pluginVersion)
                .header("User-Agent", userAgent)
                .header("OpenAI-Intent", "conversation-panel")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("VScode-SessionId", sessionId)
                .header("VScode-MachineId", machineId)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Copilot chat request failed. HTTP " + response.statusCode() +
                            ", body: " + truncate(response.body(), 2000));
        }

        return MAPPER.readTree(response.body());
    }

    private CopilotAccessToken ensureAccessToken() throws Exception {
        CopilotAccessToken current = cachedAccessToken;
        if (current != null && current.isUsable()) {
            return current;
        }

        synchronized (this) {
            current = cachedAccessToken;
            if (current != null && current.isUsable()) {
                return current;
            }

            LOG.info("Refreshing Copilot access token via {}", COPILOT_TOKEN_URL);
            cachedAccessToken = fetchAccessToken();
            LOG.info("Fetched Copilot access token. Expires at {}", cachedAccessToken.expiresAt());
            return cachedAccessToken;
        }
    }

    private CopilotAccessToken fetchAccessToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(COPILOT_TOKEN_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "token " + githubOAuthToken)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        LOG.info("Copilot token exchange response status={}", response.statusCode());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Failed to exchange GitHub OAuth token for Copilot token. HTTP " +
                            response.statusCode() + ", body: " + truncate(response.body(), 2000));
        }

        JsonNode root = MAPPER.readTree(response.body());
        String token = root.path("token").asText(null);

        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Token exchange succeeded but no 'token' field was present. Body: " +
                            truncate(response.body(), 2000));
        }

        Instant expiresAt = parseExpiry(root);
        return new CopilotAccessToken(token, expiresAt);
    }

    private Instant parseExpiry(JsonNode root) {
        JsonNode expiresAtNode = root.get("expires_at");
        if (expiresAtNode != null && !expiresAtNode.isNull()) {
            if (expiresAtNode.isNumber()) {
                return Instant.ofEpochSecond(expiresAtNode.asLong());
            }
            if (expiresAtNode.isTextual()) {
                String text = expiresAtNode.asText().trim();
                try {
                    return Instant.ofEpochSecond(Long.parseLong(text));
                } catch (Exception ignored) {
                    // ignore
                }
                try {
                    return Instant.parse(text);
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }

        // fallback if schema changes
        return Instant.now().plus(Duration.ofMinutes(25));
    }

    private String extractAssistantText(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException(
                    "Copilot response did not contain choices[0]. Body: " + truncate(root.toString(), 2000));
        }

        JsonNode content = choices.get(0).path("message").path("content");

        if (content.isTextual()) {
            return content.asText();
        }

        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if (item.isTextual()) {
                    sb.append(item.asText());
                } else if (item.hasNonNull("text")) {
                    sb.append(item.path("text").asText());
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }

        throw new IllegalStateException(
                "Could not extract assistant text from Copilot response. Body: " +
                        truncate(root.toString(), 2000));
    }

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

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String readArgAsLiteralOrFile(String arg) throws IOException {
        try {
            Path path = Path.of(arg);
            if (Files.isRegularFile(path)) {
                Path normalized = path.toAbsolutePath().normalize();
                LOG.info("Reading value from file {}", normalized);
                return Files.readString(normalized, StandardCharsets.UTF_8);
            }
        } catch (InvalidPathException ignored) {
            // treat as literal
        }
        return arg;
    }

    public static void main(String[] args) {
        try {
            if (args == null || args.length < 2 || args.length > 3) {
                LOG.error("Usage: CopilotClient <oauth-token-or-token-file> <prompt-or-prompt-file> [model]");
                System.exit(1);
            }

            String tokenOrFile = args[0];
            String promptOrFile = args[1];
            String model = args.length == 3 ? blankToNull(args[2]) : null;

            String githubOAuthToken = readArgAsLiteralOrFile(tokenOrFile).trim();
            String prompt = readArgAsLiteralOrFile(promptOrFile);

            CopilotClient client = new CopilotClient(githubOAuthToken, model);

            LOG.info("Calling Copilot. promptChars={}, model={}", prompt.length(), model);
            String response = client.chat(prompt);
            LOG.info("Copilot response:\n{}", response);
        } catch (Exception e) {
            LOG.error("CopilotClient execution failed.", e);
            System.exit(1);
        }
    }


    public record ChatMessage(String role, String content) {
        public ChatMessage {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("role must not be blank");
            }
            if (content == null) {
                throw new IllegalArgumentException("content must not be null");
            }
        }
    }

    private record CopilotAccessToken(String token, Instant expiresAt) {
        boolean isUsable() {
            return token != null
                    && !token.isBlank()
                    && expiresAt != null
                    && expiresAt.isAfter(Instant.now().plusSeconds(60));
        }
    }
}
