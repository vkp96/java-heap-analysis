package org.test.reporter.copilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.test.AnalysisResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Local HTML report writer for {@link AnalysisResult} JSON output.
 *
 * <p>This reporter does not call any external AI service. It takes the structured analysis JSON,
 * renders a readable standalone HTML report, and writes it to disk for developers to inspect.
 */
public class ReportWriterCopilot {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReportWriterCopilot.class);
	private static final DateTimeFormatter DISPLAY_TIME_FORMATTER =
		DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss 'UTC'")
			.withLocale(Locale.ENGLISH)
			.withZone(ZoneId.of("UTC"));

	public static void main(String[] args) {
		try {
			if (args == null || args.length < 1 || args.length > 2) {
				LOGGER.error("Usage: ReportWriterCopilotAgent <analysis-result-json-path> [output-html-path]");
				System.exit(1);
			}

			Path analysisResultJsonPath = Path.of(args[0]).toAbsolutePath().normalize();
			Path outputHtmlPath = args.length == 2
				? Path.of(args[1]).toAbsolutePath().normalize()
				: defaultOutputPath(analysisResultJsonPath);

			LOGGER.info("AnalysisResult JSON input path: {}", analysisResultJsonPath);
			LOGGER.info("HTML report output path: {}", outputHtmlPath);

			ReportWriterCopilot agent = new ReportWriterCopilot();
			agent.writeReport(analysisResultJsonPath, outputHtmlPath);
			LOGGER.info("HTML report generation completed successfully.");
		} catch (Exception e) {
			LOGGER.error("Failed to generate HTML report from AnalysisResult JSON.", e);
			System.exit(1);
		}
	}

	public Path writeReport(Path analysisResultJsonPath) throws Exception {
		return writeReport(analysisResultJsonPath, defaultOutputPath(analysisResultJsonPath));
	}

	public Path writeReport(Path analysisResultJsonPath, Path outputHtmlPath) throws Exception {
		Objects.requireNonNull(analysisResultJsonPath, "analysisResultJsonPath must not be null");
		Objects.requireNonNull(outputHtmlPath, "outputHtmlPath must not be null");

		Path normalizedInputPath = analysisResultJsonPath.toAbsolutePath().normalize();
		Path normalizedOutputPath = outputHtmlPath.toAbsolutePath().normalize();

		LOGGER.info("Reading AnalysisResult JSON from {}", normalizedInputPath);
		AnalysisResult result = readAnalysisResult(normalizedInputPath);

		LOGGER.info("Rendering standalone HTML report for heap dump: {}", nvl(result.heapDumpPath));
		String html = renderHtml(result, normalizedInputPath);

		Path parent = normalizedOutputPath.getParent();
		if (parent != null) {
			LOGGER.info("Ensuring output directory exists: {}", parent);
			Files.createDirectories(parent);
		}

		LOGGER.info("Writing HTML report to {}", normalizedOutputPath);
		Files.writeString(normalizedOutputPath, html, StandardCharsets.UTF_8);
		LOGGER.info("Report written successfully to {}", normalizedOutputPath);
		return normalizedOutputPath;
	}

	public AnalysisResult readAnalysisResult(Path analysisResultJsonPath) throws Exception {
		Objects.requireNonNull(analysisResultJsonPath, "analysisResultJsonPath must not be null");
		Path normalizedPath = analysisResultJsonPath.toAbsolutePath().normalize();

		if (!Files.isRegularFile(normalizedPath)) {
			throw new IllegalArgumentException("AnalysisResult JSON file not found: " + normalizedPath);
		}

		String json = Files.readString(normalizedPath, StandardCharsets.UTF_8);
		LOGGER.info("Read {} characters from AnalysisResult JSON file {}", json.length(), normalizedPath);
		return AnalysisResult.fromJson(json);
	}

	public String renderHtml(AnalysisResult result, Path analysisResultJsonPath) {
		Objects.requireNonNull(result, "result must not be null");

		String dumpPath = nvl(result.heapDumpPath);
		String dumpFileName = dumpPath.isBlank() ? "unknown-heapdump.hprof" : Path.of(dumpPath).getFileName().toString();
		String confidence = nvl(result.confidence).isBlank() ? "UNKNOWN" : result.confidence.toUpperCase(Locale.ENGLISH);
		String confidenceClass = switch (confidence) {
			case "HIGH" -> "confidence-high";
			case "MEDIUM" -> "confidence-medium";
			case "LOW" -> "confidence-low";
			default -> "confidence-unknown";
		};

		return """
			<!DOCTYPE html>
			<html lang="en">
			<head>
			  <meta charset="UTF-8"/>
			  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
			  <title>Heap Dump Analysis Report - %s</title>
			  <style>
				:root {
				  --bg: #0b1020;
				  --panel: #121a2b;
				  --panel-2: #18233a;
				  --text: #ecf2ff;
				  --muted: #aab8d1;
				  --border: #2b3957;
				  --accent: #67b3ff;
				  --danger: #ff6b81;
				  --warn: #ffbf69;
				  --ok: #5dd39e;
				  --code: #8bd3ff;
				  --shadow: 0 12px 28px rgba(0, 0, 0, 0.28);
				}
				* { box-sizing: border-box; }
				html, body {
				  margin: 0;
				  padding: 0;
				  background: linear-gradient(180deg, #08101d 0%%, #0b1020 100%%);
				  color: var(--text);
				  font-family: Inter, Segoe UI, Arial, sans-serif;
				}
				body {
				  padding: 28px;
				}
				.page {
				  max-width: 1180px;
				  margin: 0 auto;
				}
				.hero {
				  background: linear-gradient(135deg, #121a2b 0%%, #0f1730 100%%);
				  border: 1px solid var(--border);
				  border-radius: 18px;
				  box-shadow: var(--shadow);
				  padding: 28px;
				  margin-bottom: 24px;
				}
				.eyebrow {
				  color: var(--accent);
				  text-transform: uppercase;
				  letter-spacing: 0.12em;
				  font-size: 12px;
				  font-weight: 700;
				  margin-bottom: 12px;
				}
				h1 {
				  margin: 0 0 8px 0;
				  font-size: 34px;
				  line-height: 1.15;
				}
				.hero-subtitle {
				  margin: 0;
				  color: var(--muted);
				  font-size: 16px;
				  line-height: 1.6;
				  max-width: 900px;
				}
				.hero-meta {
				  display: flex;
				  flex-wrap: wrap;
				  gap: 12px;
				  margin-top: 18px;
				}
				.pill {
				  display: inline-flex;
				  align-items: center;
				  gap: 8px;
				  padding: 8px 12px;
				  border-radius: 999px;
				  background: rgba(255,255,255,0.04);
				  border: 1px solid var(--border);
				  font-size: 13px;
				  color: var(--muted);
				}
				.pill strong {
				  color: var(--text);
				}
				.confidence-high { color: var(--ok); }
				.confidence-medium { color: var(--warn); }
				.confidence-low, .confidence-unknown { color: var(--danger); }
				.grid {
				  display: grid;
				  grid-template-columns: repeat(12, 1fr);
				  gap: 18px;
				}
				.section {
				  grid-column: 1 / -1;
				  background: var(--panel);
				  border: 1px solid var(--border);
				  border-radius: 16px;
				  box-shadow: var(--shadow);
				  padding: 22px;
				}
				.section h2 {
				  margin: 0 0 16px 0;
				  font-size: 22px;
				}
				.section p {
				  color: var(--muted);
				  line-height: 1.7;
				}
				.card-grid {
				  display: grid;
				  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
				  gap: 14px;
				}
				.stat-card {
				  background: var(--panel-2);
				  border: 1px solid var(--border);
				  border-radius: 14px;
				  padding: 16px;
				}
				.stat-label {
				  font-size: 12px;
				  color: var(--muted);
				  text-transform: uppercase;
				  letter-spacing: 0.08em;
				  margin-bottom: 8px;
				}
				.stat-value {
				  font-size: 24px;
				  font-weight: 700;
				  color: var(--text);
				  word-break: break-word;
				}
				.stat-sub {
				  color: var(--muted);
				  font-size: 13px;
				  margin-top: 6px;
				  line-height: 1.5;
				}
				.suspect-list, .stack-list, .chain-list, .remediation-list {
				  display: flex;
				  flex-direction: column;
				  gap: 14px;
				}
				.item-card {
				  background: var(--panel-2);
				  border: 1px solid var(--border);
				  border-radius: 14px;
				  padding: 16px;
				}
				.item-header {
				  display: flex;
				  justify-content: space-between;
				  gap: 12px;
				  align-items: flex-start;
				  flex-wrap: wrap;
				  margin-bottom: 8px;
				}
				.item-title {
				  font-size: 17px;
				  font-weight: 700;
				  color: var(--text);
				  word-break: break-word;
				}
				.badge {
				  border-radius: 999px;
				  padding: 5px 10px;
				  border: 1px solid var(--border);
				  font-size: 12px;
				  font-weight: 700;
				  white-space: nowrap;
				}
				.badge-suspect {
				  color: var(--danger);
				  border-color: rgba(255, 107, 129, 0.5);
				  background: rgba(255, 107, 129, 0.08);
				}
				.badge-normal {
				  color: var(--accent);
				  border-color: rgba(103, 179, 255, 0.4);
				  background: rgba(103, 179, 255, 0.07);
				}
				.meta-row {
				  display: flex;
				  flex-wrap: wrap;
				  gap: 10px 18px;
				  margin: 10px 0;
				}
				.meta-row span {
				  color: var(--muted);
				  font-size: 13px;
				}
				.meta-row strong {
				  color: var(--text);
				}
				.note {
				  margin-top: 8px;
				  padding: 12px 14px;
				  background: rgba(103, 179, 255, 0.06);
				  border-left: 3px solid var(--accent);
				  border-radius: 10px;
				  color: var(--muted);
				  line-height: 1.6;
				}
				table {
				  width: 100%%;
				  border-collapse: collapse;
				  overflow: hidden;
				  border-radius: 12px;
				}
				th, td {
				  padding: 12px 10px;
				  border-bottom: 1px solid var(--border);
				  vertical-align: top;
				  text-align: left;
				}
				th {
				  color: var(--muted);
				  font-size: 12px;
				  text-transform: uppercase;
				  letter-spacing: 0.08em;
				  background: rgba(255,255,255,0.02);
				}
				td {
				  color: var(--text);
				  font-size: 14px;
				}
				.mono {
				  font-family: Consolas, "Courier New", monospace;
				  color: var(--code);
				  word-break: break-word;
				}
				.chain-step {
				  padding: 10px 0;
				  border-top: 1px solid rgba(255,255,255,0.05);
				}
				.chain-step:first-child {
				  border-top: none;
				}
				.stack-frames {
				  margin-top: 12px;
				  background: rgba(0,0,0,0.18);
				  border: 1px solid var(--border);
				  border-radius: 10px;
				  padding: 12px;
				}
				.stack-frames li {
				  margin: 6px 0 6px 18px;
				  color: var(--muted);
				}
				.keywords {
				  display: flex;
				  flex-wrap: wrap;
				  gap: 8px;
				  margin-top: 12px;
				}
				.keyword {
				  padding: 6px 10px;
				  border-radius: 999px;
				  background: rgba(139, 211, 255, 0.08);
				  border: 1px solid rgba(139, 211, 255, 0.22);
				  color: var(--code);
				  font-size: 12px;
				}
				.remediation-list ol {
				  margin: 0;
				  padding-left: 22px;
				}
				.remediation-list li {
				  margin: 10px 0;
				  line-height: 1.7;
				  color: var(--muted);
				}
				.footer {
				  color: var(--muted);
				  font-size: 12px;
				  margin: 18px 0 8px 0;
				  text-align: center;
				}
				@media (max-width: 720px) {
				  body { padding: 14px; }
				  .hero, .section { padding: 18px; }
				  h1 { font-size: 28px; }
				}
			  </style>
			</head>
			<body>
			  <div class="page">
				<section class="hero">
				  <div class="eyebrow">Java Heap Dump Analysis</div>
				  <h1>Developer Report for <span class="mono">%s</span></h1>
				  <p class="hero-subtitle">%s</p>
				  <div class="hero-meta">
					<span class="pill"><strong>Confidence:</strong> <span class="%s">%s</span></span>
					<span class="pill"><strong>Leak Size:</strong> %s</span>
					<span class="pill"><strong>Analysis Time:</strong> %s</span>
					<span class="pill"><strong>Source JSON:</strong> <span class="mono">%s</span></span>
				  </div>
				</section>

				<section class="section">
				  <h2>Heap Dump Overview</h2>
				  <div class="card-grid">
					%s
				  </div>
				</section>

				<section class="section">
				  <h2>Leak Suspects and Top Retained Objects</h2>
				  %s
				</section>

				<section class="section">
				  <h2>GC Root Chains</h2>
				  %s
				</section>

				<section class="section">
				  <h2>Allocation Hotspots</h2>
				  %s
				</section>

				<section class="section">
				  <h2>Root Cause Assessment</h2>
				  %s
				</section>

				<section class="section">
				  <h2>Recommended Remediation</h2>
				  %s
				</section>

				<div class="footer">Generated locally by ReportWriterCopilotAgent</div>
			  </div>
			</body>
			</html>
			""".formatted(
			esc(dumpFileName),
			esc(dumpFileName),
			esc(defaultSummary(result)),
			confidenceClass,
			esc(confidence),
			esc(formatLeakSize(result.estimatedLeakSizeMb)),
			esc(formatInstant(result.analyzedAt)),
			esc(analysisResultJsonPath.toString()),
			buildOverviewCardsHtml(result),
			buildLeakSuspectsHtml(result),
			buildGcRootChainsHtml(result),
			buildAllocatorStacksHtml(result),
			buildRootCauseHtml(result),
			buildRemediationHtml(result)
		);
	}

	private String buildOverviewCardsHtml(AnalysisResult result) {
		long suspectCount = safeList(result.topRetainedObjects).stream()
			.filter(o -> Boolean.TRUE.equals(o.isSuspect))
			.count();

		return new StringBuilder()
			.append(statCard("Heap dump path", nvl(result.heapDumpPath), "Original heap dump file analysed"))
			.append(statCard("Analysed at", formatInstant(result.analyzedAt), "Timestamp from the structured analysis result"))
			.append(statCard("Estimated leak size", formatLeakSize(result.estimatedLeakSizeMb), "Estimated amount of retained heap tied to the leak"))
			.append(statCard("Confidence", nvl(result.confidence).isBlank() ? "UNKNOWN" : result.confidence, "How strong the automated diagnosis is"))
			.append(statCard("Top retained objects", String.valueOf(safeList(result.topRetainedObjects).size()), "Number of retained object entries in the result"))
			.append(statCard("Leak suspects", String.valueOf(suspectCount), "Objects explicitly flagged as suspects"))
			.append(statCard("GC root chains", String.valueOf(safeList(result.gcRootChains).size()), "Root-to-suspect retention chains available"))
			.append(statCard("Allocator stacks", String.valueOf(safeList(result.dominantAllocatorStacks).size()), "Allocation hotspots captured in the analysis"))
			.toString();
	}

	private String buildLeakSuspectsHtml(AnalysisResult result) {
		List<AnalysisResult.RetainedObject> objects = safeList(result.topRetainedObjects).stream()
			.sorted(Comparator.comparingLong((AnalysisResult.RetainedObject o) -> o.retainedHeapBytes != null ? o.retainedHeapBytes : Long.MIN_VALUE).reversed())
			.collect(Collectors.toList());

		if (objects.isEmpty()) {
			return "<p>No top retained object data is available in this AnalysisResult.</p>";
		}

		StringBuilder html = new StringBuilder();
		html.append("<table><thead><tr>")
			.append("<th>Class</th>")
			.append("<th>Instances</th>")
			.append("<th>Retained Heap</th>")
			.append("<th>Heap %%</th>")
			.append("<th>Status</th>")
			.append("<th>Developer Note</th>")
			.append("</tr></thead><tbody>");

		for (AnalysisResult.RetainedObject object : objects) {
			boolean suspect = Boolean.TRUE.equals(object.isSuspect);
			html.append("<tr>")
				.append("<td class=\"mono\">" ).append(esc(nvl(object.className))).append("</td>")
				.append("<td>").append(esc(formatLongNullable(object.instanceCount))).append("</td>")
				.append("<td>").append(esc(formatBytesNullable(object.retainedHeapBytes))).append("</td>")
				.append("<td>").append(esc(formatPct(object.retainedHeapPct))).append("</td>")
				.append("<td><span class=\"badge ")
				.append(suspect ? "badge-suspect\">Leak suspect" : "badge-normal\">Observed")
				.append("</span></td>")
				.append("<td>").append(esc(nvl(object.agentNote))).append("</td>")
				.append("</tr>");
		}
		html.append("</tbody></table>");
		return html.toString();
	}

	private String buildGcRootChainsHtml(AnalysisResult result) {
		List<AnalysisResult.GcRootChain> chains = safeList(result.gcRootChains);
		if (chains.isEmpty()) {
			return "<p>No GC root chain information is available.</p>";
		}

		StringBuilder html = new StringBuilder("<div class=\"chain-list\">");
		for (AnalysisResult.GcRootChain chain : chains) {
			html.append("<div class=\"item-card\">")
				.append("<div class=\"item-header\">")
				.append("<div class=\"item-title\">" ).append(esc(nvl(chain.chainLabel))).append("</div>")
				.append("<span class=\"badge badge-normal\">Retained ")
				.append(esc(formatBytesNullable(chain.retainedHeapBytes)))
				.append("</span></div>")
				.append("<div class=\"meta-row\">")
				.append("<span><strong>Root type:</strong> ").append(esc(nvl(chain.rootType))).append("</span>")
				.append("<span><strong>Root object:</strong> <span class=\"mono\">").append(esc(nvl(chain.rootObject))).append("</span></span>")
				.append("</div>");

			if (!safeList(chain.referencePath).isEmpty()) {
				for (AnalysisResult.ReferenceStep step : safeList(chain.referencePath)) {
					html.append("<div class=\"chain-step\">")
						.append("<div><strong>From:</strong> <span class=\"mono\">").append(esc(nvl(step.from))).append("</span></div>")
						.append("<div><strong>Via:</strong> <span class=\"mono\">").append(esc(nvl(step.viaField))).append("</span></div>")
						.append("<div><strong>To:</strong> <span class=\"mono\">").append(esc(nvl(step.to))).append("</span></div>")
						.append("</div>");
				}
			}

			if (!nvl(chain.suspectObject).isBlank()) {
				html.append("<div class=\"note\"><strong>Suspect object:</strong> <span class=\"mono\">")
					.append(esc(chain.suspectObject))
					.append("</span></div>");
			}
			html.append("</div>");
		}
		html.append("</div>");
		return html.toString();
	}

	private String buildAllocatorStacksHtml(AnalysisResult result) {
		List<AnalysisResult.AllocatorStack> stacks = safeList(result.dominantAllocatorStacks);
		if (stacks.isEmpty()) {
			return "<p>No allocator stack information is available.</p>";
		}

		StringBuilder html = new StringBuilder("<div class=\"stack-list\">");
		for (AnalysisResult.AllocatorStack stack : stacks) {
			html.append("<div class=\"item-card\">")
				.append("<div class=\"item-header\">")
				.append("<div class=\"item-title mono\">" ).append(esc(nvl(stack.allocatorMethod))).append("</div>")
				.append("<span class=\"badge badge-normal\">Retained ").append(esc(formatBytesNullable(stack.retainedHeapBytes))).append("</span>")
				.append("</div>")
				.append("<div class=\"meta-row\">")
				.append("<span><strong>Objects:</strong> ").append(esc(formatLongNullable(stack.objectCount))).append("</span>")
				.append("</div>");

			if (!nvl(stack.leakPattern).isBlank()) {
				html.append("<div class=\"note\">" ).append(esc(stack.leakPattern)).append("</div>");
			}

			if (!safeList(stack.stackFrames).isEmpty()) {
				html.append("<div class=\"stack-frames\"><strong>Stack frames</strong><ol>");
				for (String frame : safeList(stack.stackFrames)) {
					html.append("<li class=\"mono\">" ).append(esc(nvl(frame))).append("</li>");
				}
				html.append("</ol></div>");
			}
			html.append("</div>");
		}
		html.append("</div>");
		return html.toString();
	}

	private String buildRootCauseHtml(AnalysisResult result) {
		if (result.rootCause == null) {
			return "<p>No root cause section is available.</p>";
		}

		AnalysisResult.RootCause rootCause = result.rootCause;
		StringBuilder html = new StringBuilder();
		html.append("<div class=\"item-card\">")
			.append("<div class=\"item-title\">" ).append(esc(nvl(rootCause.description))).append("</div>")
			.append("<div class=\"meta-row\">")
			.append("<span><strong>Responsible class:</strong> <span class=\"mono\">").append(esc(nvl(rootCause.responsibleClass))).append("</span></span>")
			.append("<span><strong>Method:</strong> <span class=\"mono\">").append(esc(nvl(rootCause.responsibleMethod))).append("</span></span>")
			.append("<span><strong>Pattern:</strong> ").append(esc(nvl(rootCause.leakPatternType))).append("</span></span>")
			.append("</div>");

		if (!nvl(rootCause.detailedExplanation).isBlank()) {
			html.append("<p>").append(esc(rootCause.detailedExplanation)).append("</p>");
		}

		if (!safeList(rootCause.codeSearchKeywords).isEmpty()) {
			html.append("<div class=\"keywords\">");
			for (String keyword : safeList(rootCause.codeSearchKeywords)) {
				html.append("<span class=\"keyword\">" ).append(esc(nvl(keyword))).append("</span>");
			}
			html.append("</div>");
		}

		html.append("</div>");
		return html.toString();
	}

	private String buildRemediationHtml(AnalysisResult result) {
		List<String> remediation = safeList(result.remediation);
		if (remediation.isEmpty()) {
			return "<p>No remediation guidance is available.</p>";
		}

		StringBuilder html = new StringBuilder("<div class=\"remediation-list\"><ol>");
		for (String step : remediation) {
			html.append("<li>").append(esc(nvl(step))).append("</li>");
		}
		html.append("</ol></div>");
		return html.toString();
	}

	private static String statCard(String label, String value, String subText) {
		return """
			<div class="stat-card">
			  <div class="stat-label">%s</div>
			  <div class="stat-value">%s</div>
			  <div class="stat-sub">%s</div>
			</div>
			""".formatted(esc(label), esc(value), esc(subText));
	}

	private static String defaultSummary(AnalysisResult result) {
		if (!nvl(result.summary).isBlank()) {
			return result.summary;
		}
		if (result.rootCause != null && !nvl(result.rootCause.description).isBlank()) {
			return result.rootCause.description;
		}
		return "This report summarises the available heap dump leak analysis data for developer review.";
	}

	private static String formatInstant(String value) {
		if (value == null || value.isBlank()) {
			return "Unknown";
		}
		try {
			return DISPLAY_TIME_FORMATTER.format(Instant.parse(value));
		} catch (Exception ignored) {
			return value;
		}
	}

	private static String formatLeakSize(Double mb) {
		if (mb == null) {
			return "Unknown";
		}
		return String.format(Locale.ENGLISH, "%.2f MB", mb);
	}

	private static String formatPct(Double value) {
		if (value == null) {
			return "—";
		}
		return String.format(Locale.ENGLISH, "%.2f%%", value);
	}

	private static String formatBytesNullable(Long bytes) {
		return bytes == null ? "—" : formatBytes(bytes);
	}

	private static String formatBytes(long bytes) {
		if (bytes >= 1_073_741_824L) {
			return String.format(Locale.ENGLISH, "%.2f GB", bytes / 1_073_741_824.0);
		}
		if (bytes >= 1_048_576L) {
			return String.format(Locale.ENGLISH, "%.2f MB", bytes / 1_048_576.0);
		}
		if (bytes >= 1_024L) {
			return String.format(Locale.ENGLISH, "%.2f KB", bytes / 1_024.0);
		}
		return bytes + " B";
	}

	private static String formatLongNullable(Long value) {
		return value == null ? "—" : formatLong(value);
	}

	private static String formatLong(long value) {
		return String.format(Locale.ENGLISH, "%,d", value);
	}

	private static <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : values;
	}

	private static String esc(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}

	private static String nvl(String value) {
		return value == null ? "" : value;
	}

	private static Path defaultOutputPath(Path analysisResultJsonPath) {
		Path normalizedInputPath = analysisResultJsonPath.toAbsolutePath().normalize();
		String inputFileName = normalizedInputPath.getFileName() != null
			? normalizedInputPath.getFileName().toString()
			: "analysis-result.json";
		int extensionIndex = inputFileName.lastIndexOf('.');
		String baseName = extensionIndex > 0 ? inputFileName.substring(0, extensionIndex) : inputFileName;
		Path parent = normalizedInputPath.getParent();
		return (parent != null ? parent : Path.of("."))
			.resolve(baseName + "-developer-report.html")
			.toAbsolutePath()
			.normalize();
	}
}
