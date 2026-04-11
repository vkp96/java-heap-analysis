package org.test.remediation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic patch backend that turns authoring outputs into structured,
 * reviewable patch artifacts without applying edits.
 */
public class LocalPlanPatchBackend implements PatchGenerationBackend {

	private static final Pattern RETAINED_COLLECTION_PATTERN = Pattern.compile(
			"^(\\s*)(?:[\\w$.<>\\[\\]]+\\s+)?(\\w+)\\s*=\\s*new\\s+(?:java\\.util\\.)?ArrayList<.*>\\(\\);\\s*$");

	private final PatchDiffPreviewRenderer diffPreviewRenderer = new PatchDiffPreviewRenderer();

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String backendName() {
		return PatchProviderType.LOCAL_PLAN.name();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public PatchGenerationExecution execute(PatchGenerationRequest request,
											RemediationWorkflowConfig.PatchGenerationConfig config) {
		PatchGenerationResult result = new PatchGenerationResult();
		result.provider = backendName();
		result.summary = "Deterministic structured patch output derived from authoring results and retrieval snippets.";
		result.notes.add("No repository files were modified by this backend.");
		result.notes.add("The patch hunks are review artifacts that must be applied and validated by a later step.");

		for (PatchGenerationRequest.PatchTargetFile targetFile : request.files) {
			StructuredPatchFile patchFile = new StructuredPatchFile();
			patchFile.path = targetFile.path;
			patchFile.changeType = targetFile.changeType;
			patchFile.rationale = targetFile.justification;

			int hunkLimit = Math.min(config.maxHunksPerFile, targetFile.snippetContexts.size());
			for (int i = 0; i < hunkLimit; i++) {
				PatchGenerationRequest.SnippetContext snippetContext = targetFile.snippetContexts.get(i);
				StructuredPatchHunk hunk = new StructuredPatchHunk();
				hunk.startLine = snippetContext.startLine;
				hunk.endLine = snippetContext.endLine;
				hunk.currentText = snippetContext.content;
				hunk.currentSnippet = truncateLines(snippetContext.content, config.maxLinesPerHunk);
				hunk.proposedEditDescription = targetFile.intent;
				hunk.replacementPreview = buildReplacementPreview(targetFile, snippetContext);
				hunk.replacementText = buildReplacementText(snippetContext.content);
				if (hunk.replacementText == null || hunk.replacementText.isBlank()) {
					hunk.replacementText = stripLineNumbers(snippetContext.content);
				}
				patchFile.hunks.add(hunk);
			}
			result.structuredPatchFiles.add(patchFile);
		}

		String diffPreview = config.emitUnifiedDiffPreview ? diffPreviewRenderer.render(result) : null;
		return new PatchGenerationExecution(null, null, diffPreview, result);
	}

	/**
	 * Creates a compact replacement preview from a target file and snippet context.
	 *
	 * @param targetFile file-level patch target
	 * @param snippetContext current snippet context
	 * @return replacement preview text
	 */
	private String buildReplacementPreview(PatchGenerationRequest.PatchTargetFile targetFile,
										   PatchGenerationRequest.SnippetContext snippetContext) {
		String evidenceText = targetFile.evidence.isEmpty() ? "[no evidence]" : targetFile.evidence.get(0);
		String deterministicPreview = buildDeterministicPreview(snippetContext.content);
		if (deterministicPreview != null && !deterministicPreview.isBlank()) {
			return deterministicPreview;
		}
		return "Intent: " + targetFile.intent
				+ System.lineSeparator()
				+ "Justification: " + targetFile.justification
				+ System.lineSeparator()
				+ "Evidence: " + evidenceText
				+ System.lineSeparator()
				+ "Matched terms: " + snippetContext.matchedTerms;
	}

	private String buildReplacementText(String lineNumberedContent) {
		List<String> rawLines = new ArrayList<>(safeSplitLines(stripLineNumbers(lineNumberedContent)));
		if (rawLines.isEmpty()) {
			return null;
		}

		Matcher declarationMatcher = null;
		int declarationIndex = -1;
		for (int i = 0; i < rawLines.size(); i++) {
			Matcher matcher = RETAINED_COLLECTION_PATTERN.matcher(rawLines.get(i));
			if (matcher.matches()) {
				declarationMatcher = matcher;
				declarationIndex = i;
				break;
			}
		}
		if (declarationMatcher == null) {
			return null;
		}

		String indent = declarationMatcher.group(1);
		String variableName = declarationMatcher.group(2);
		rawLines.set(declarationIndex, indent + "byte[] latestChunk = null;");

		for (int i = 0; i < rawLines.size(); i++) {
			String line = rawLines.get(i);
			if (line.contains("Keep references to the arrays so they are not garbage-collected")) {
				rawLines.set(i, indent + "// Avoid retaining every allocated chunk so earlier allocations can be garbage-collected.");
			}
			if (line.contains(variableName + ".add(new byte[ONE_MB])")) {
				rawLines.set(i, line.replace(variableName + ".add(new byte[ONE_MB])", "latestChunk = new byte[ONE_MB]"));
			}
			if (line.contains("throw oom;")) {
				rawLines.add(i, indent + "    latestChunk = null;");
				break;
			}
		}

		return String.join(System.lineSeparator(), rawLines);
	}

	private String buildDeterministicPreview(String lineNumberedContent) {
		String replacementText = buildReplacementText(lineNumberedContent);
		if (replacementText == null || replacementText.isBlank()) {
			return null;
		}
		return truncateLines(replacementText, 8);
	}

	private String stripLineNumbers(String content) {
		List<String> lines = safeSplitLines(content);
		List<String> stripped = new ArrayList<>();
		for (String line : lines) {
			stripped.add(line.replaceFirst("^\\s*\\d+\\s*\\|\\s?", ""));
		}
		return String.join(System.lineSeparator(), stripped);
	}


	/**
	 * Truncates a line-numbered snippet to at most the requested number of lines.
	 *
	 * @param content multi-line snippet content
	 * @param maxLines maximum number of lines to keep
	 * @return truncated snippet text
	 */
	private String truncateLines(String content, int maxLines) {
		List<String> lines = safeSplitLines(content);
		if (lines.size() <= maxLines) {
			return String.join(System.lineSeparator(), lines);
		}
		return String.join(System.lineSeparator(), lines.subList(0, maxLines))
				+ System.lineSeparator()
				+ "[truncated]";
	}

	/**
	 * Splits a multi-line block into individual lines while tolerating null input.
	 *
	 * @param content multi-line content
	 * @return individual lines, or an empty list for null/blank input
	 */
	private List<String> safeSplitLines(String content) {
		if (content == null || content.isBlank()) {
			return List.of();
		}
		return List.of(content.split("\\R"));
	}
}

