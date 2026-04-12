# heapfixer

## Remediation draft workflow MVP

This project now includes a local-only remediation drafting flow that runs after an `AnalysisResult` is produced.

### What it does

- derives targeted retrieval terms from `AnalysisResult.rootCause`, allocator stacks, and keywords
- scans only matching repository files instead of using full-repo context
- writes three local artifacts beside the analysis output:
  - `targeted_retrieval_context.json`
  - `pr_draft.json`
  - `pr_policy_decision.json`
- when policy passes, also writes authoring artifacts:
  - `pr_author_request.json`
  - `pr_change_plan.json`
  - `pr_author_result.json`
- after authoring, also writes patch-generation artifacts:
  - `patch_generation_request.json`
  - `patch_generation_result.json`
  - `patch_preview.diff`
- when local patch application is enabled, also writes:
  - `patch_application_request.json`
  - `patch_application_result.json`
  - `patch_application_final.diff`
  - `patch_application_validation.log`
- when final PR generation is enabled, also writes:
  - `pr_generation_request.json`
  - `pr_generation_result.json`
  - `pr_preview.md`
- when remote publish is enabled, also writes:
  - `remote_publish_request.json`
  - `remote_publish_result.json`
  - `remote_publish_push.log`
  - `remote_publish_response.json`
- for AI-backed patch backends, can also write:
  - `patch_generation_prompt.txt`
  - `patch_generation_response.json`
- for AI-backed authoring backends, can also write:
  - `pr_author_prompt.txt`
  - `pr_author_response.json`
- blocks future PR creation if policy checks fail

### Configuration

Default config lives at:

- `src/main/resources/remediation-workflow-config.json`

You can override it with environment variables:

- `HEAPFIXER_REMEDIATION_CONFIG` - path to a config JSON file
- `HEAPFIXER_PR_DRAFT_ENABLED` - `true` or `false`
- `HEAPFIXER_REPO_ROOT` - repository root for targeted retrieval

### Important policy knobs

- `retrieval.max_files`
- `retrieval.max_snippets_per_file`
- `retrieval.allow_repo_wide_fallback`
- `pr_policy.minimum_confidence`
- `pr_policy.max_candidate_files`
- `pr_policy.max_total_snippets`
- `pr_policy.allowed_change_globs`
- `authoring.request_file_name`
- `authoring.change_plan_file_name`
- `authoring.max_snippets_per_file`
- `authoring.provider`
- `authoring.result_file_name`
- `authoring.prompt_file_name`
- `authoring.raw_response_file_name`
- `authoring.copilot_auth_token_file`
- `authoring.copilot_model`
- `patch_generation.provider`
- `patch_generation.request_file_name`
- `patch_generation.result_file_name`
- `patch_generation.prompt_file_name`
- `patch_generation.raw_response_file_name`
- `patch_generation.diff_preview_file_name`
- `patch_generation.copilot_auth_token_file`
- `patch_generation.copilot_model`
- `patch_generation.max_files`
- `patch_generation.max_hunks_per_file`
- `patch_generation.max_lines_per_hunk`
- `patch_generation.emit_unified_diff_preview`
- `patch_application.auto_commit`
- `patch_application.commit_message_prefix`
- `patch_application.git_user_name`
- `patch_application.git_user_email`
- `pr_generation.provider`
- `pr_generation.request_file_name`
- `pr_generation.result_file_name`
- `pr_generation.preview_file_name`
- `pr_generation.draft`
- `remote_publish.provider`
- `remote_publish.remote_name`
- `remote_publish.github_owner`
- `remote_publish.github_repo`
- `remote_publish.github_token`
- `remote_publish.github_api_base_url`
- `remote_publish.request_file_name`
- `remote_publish.result_file_name`
- `remote_publish.push_output_file_name`
- `remote_publish.raw_response_file_name`

### Default safety posture

- repo-wide fallback is disabled by default
- only a small number of files/snippets are retrieved
- a draft is blocked if retrieval is ambiguous
- no GitHub PR is created yet
- default authoring backend is `LOCAL_PLAN` so tests and offline runs remain deterministic
- patch generation remains provider-neutral, while patch application is opt-in and operates on a new Git branch
- auto-commit is opt-in and uses a local Git identity by default
- PR generation produces the final title/body artifacts used for remote publishing
- remote publish is opt-in and can push the branch plus create a draft PR through a provider-backed integration
- human approval is still required for any future fix PR

### Run the workflow test

```powershell
Set-Location "D:\Project\AutomaticHeapDumpAnalysis\java-heap-analysis\heapfixer"
.\gradlew.bat test --tests org.test.remediation.PrAutomationDraftWorkflowTest
```

### Enable the workflow in the normal analysis pipeline

```powershell
$env:HEAPFIXER_PR_DRAFT_ENABLED = "true"
$env:HEAPFIXER_REPO_ROOT = "D:\Project\AutomaticHeapDumpAnalysis\java-heap-analysis\heapfixer"
```

After `analysis_result.json` is written, the remediation draft artifacts will be written beside it.

## Standalone Jira story creation from `AnalysisResult`

This project now includes a standalone `JiraIssueCreator` CLI at `org.test.jira.JiraIssueCreator`.

What it does:

- reads an `AnalysisResult` JSON file
- builds a Jira story title from the root cause, leak pattern, and retained size
- builds a detailed description containing the full analysis details
- generates acceptance criteria as a separate field
- sends the story to an MCP-enabled Jira server over HTTP using a best-effort `tools/list` + `tools/call` flow
- writes a local result artifact named `jira_issue_creation_result.json` by default

### Jira MCP configuration

You can supply configuration through CLI flags or environment variables:

- `HEAPFIXER_JIRA_MCP_URL` - HTTP endpoint for the MCP-enabled Jira server
- `HEAPFIXER_JIRA_MCP_TOKEN` - optional auth token
- `HEAPFIXER_JIRA_MCP_AUTH_HEADER` - optional auth header name, defaults to `Authorization`
- `HEAPFIXER_JIRA_MCP_TOOL_NAME` - optional MCP tool name override when auto-discovery is not enough
- `HEAPFIXER_JIRA_PROJECT_KEY` - optional Jira project key

### Dry-run example

Use this first if you want to verify the generated title, description, and acceptance criteria without sending an HTTP request:

```powershell
Set-Location "D:\Project\AutomaticHeapDumpAnalysis\java-heap-analysis\heapfixer"
.\gradlew.bat runJiraIssueCreator -PanalysisResultFile=".\result.json" -PjiraDryRun=true
```

### HTTP creation example

```powershell
Set-Location "D:\Project\AutomaticHeapDumpAnalysis\java-heap-analysis\heapfixer"
$env:HEAPFIXER_JIRA_MCP_URL = "https://your-jira-mcp-server.example.com/mcp"
$env:HEAPFIXER_JIRA_MCP_TOKEN = "<token>"
$env:HEAPFIXER_JIRA_PROJECT_KEY = "OPS"
.\gradlew.bat runJiraIssueCreator -PanalysisResultFile=".\result.json"
```

### Optional CLI flags

```text
JiraIssueCreator <analysis-result-json-file> [--endpoint <mcp-url>] [--output <result-json-file>]
                 [--token <token-or-token-file>] [--auth-header <header-name>] [--tool <tool-name>]
                 [--project-key <jira-project-key>] [--dry-run]
```

Notes:

- the Jira MCP request shape can vary by server, so this implementation tries to discover a suitable tool automatically
- if your server exposes a non-obvious tool name, set `HEAPFIXER_JIRA_MCP_TOOL_NAME` or use `--tool`
- this class is standalone only for now and is not wired into the remediation workflow yet
- if you want to invoke the main class directly instead of the Gradle task, use a classpath that includes the full runtime dependencies

## Copilot Coding Agent — automatic fix PRs via GitHub Issues

Instead of generating patches, applying them to Git, and pushing PRs from within this codebase, heapfixer can delegate the entire code-fix workflow to the **GitHub Copilot Coding Agent**. The approach is simple:

1. `HeapDumpWatcher` detects a new `.hprof` file and runs the `AnalyzerPipeline`.
2. The pipeline produces a structured `AnalysisResult` (root cause, responsible class/method, leak pattern, remediation steps, etc.).
3. `CopilotAgentRemediationService` takes that result and calls `GitHubIssueCreator` to open a GitHub Issue assigned to `copilot`.
4. The Copilot Coding Agent autonomously reads the issue, locates the relevant source files, writes a minimal fix, and opens a Pull Request — no local patch generation, policy checks, or git operations happen in heapfixer.

### Key classes

| Class | Package | Role |
|---|---|---|
| `GitHubIssueCreator` | `org.test.github` | Builds a richly-formatted GitHub Issue from an `AnalysisResult` and creates it via the GitHub REST API. Assigns the issue to `copilot` and labels it `heap-leak-fix`. Includes duplicate detection so the same root-cause class does not produce multiple open issues. |
| `CopilotAgentRemediationService` | `org.test.github` | Thin integration service wired into `HeapDumpWatcher`. Reads configuration from environment variables, gates on analysis confidence, and delegates to `GitHubIssueCreator`. |

### What the generated issue contains

The issue body created by `GitHubIssueCreator` includes:

- **Overview** — summary, confidence, estimated leak size, heap dump path, analysis timestamp
- **Root Cause** — responsible class, method, leak pattern type, detailed explanation, code search keywords
- **Remediation Steps** — numbered list from the analysis
- **Top Retained Objects** — table with class name, retained bytes, heap percentage, suspect flag
- **GC Root Chains** — reference paths from GC roots to leak suspects
- **Dominant Allocator Stacks** — allocation sites with stack frames and leak patterns
- **Instructions for Copilot** — explicit directives for the Copilot Coding Agent:
  - primary target class and method
  - leak pattern to fix
  - checklist of changes (from remediation steps)
  - code search hints (keywords)
  - guidelines (minimal changes, no unrelated refactoring, `[OOM Fix]` PR title prefix, reference the issue)

### Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `COPILOT_AGENT_ENABLED` | Yes | `false` | Set to `true` to enable automatic issue creation after analysis. |
| `GITHUB_OWNER` | Yes | — | GitHub repository owner (user or organization), e.g. `vkp96`. |
| `GITHUB_REPO` | Yes | — | GitHub repository name, e.g. `java-heap-analysis`. |
| `GITHUB_TOKEN` or `GH_TOKEN` | Yes | — | GitHub personal access token with `repo` scope (or at minimum `issues:write`). |
| `GITHUB_API_BASE_URL` | No | `https://api.github.com` | Override for GitHub Enterprise Server, e.g. `https://github.example.com/api/v3`. |
| `COPILOT_AGENT_MIN_CONFIDENCE` | No | `MEDIUM` | Minimum analysis confidence required to create an issue. Accepted values: `LOW`, `MEDIUM`, `HIGH`. Results below this threshold are silently skipped. |

### Quick-start example

```powershell
Set-Location "D:\Project\AutomaticHeapDumpAnalysis\java-heap-analysis\heapfixer"

$env:COPILOT_AGENT_ENABLED = "true"
$env:GITHUB_OWNER = "vkp96"
$env:GITHUB_REPO = "java-heap-analysis"
$env:GITHUB_TOKEN = "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

# Optional: only create issues for HIGH confidence results
$env:COPILOT_AGENT_MIN_CONFIDENCE = "HIGH"

# Start the watcher (also needs MAT_TOOL_PATH and an analysis strategy)
java -cp build/classes/java/main org.test.HeapDumpWatcher ../heapdumps ../heapdumps
```

When a heap dump appears and analysis completes with sufficient confidence, a GitHub Issue like this is created:

```
🔴 Memory Leak Fix: com.example.CacheManager.addEntry — UNBOUNDED_CACHE
```

The issue is automatically assigned to the Copilot Coding Agent, which opens a fix PR.

### Duplicate detection

Before creating a new issue, `GitHubIssueCreator` searches the GitHub Search API for open issues with the `heap-leak-fix` label whose title matches the same responsible class. If a match is found, the existing issue is returned and no duplicate is created.

### Programmatic usage

```java
// Standalone — create an issue directly from an AnalysisResult
GitHubIssueCreator creator = new GitHubIssueCreator("vkp96", "java-heap-analysis");
GitHubIssueCreator.CreatedIssue issue = creator.createFromAnalysis(result);
System.out.println("Issue: " + issue.htmlUrl());

// Via the service (env-var driven, used by HeapDumpWatcher)
CopilotAgentRemediationService service = new CopilotAgentRemediationService();
service.submit(result); // no-ops if disabled or below confidence threshold
```

### Safety posture

- disabled by default — requires `COPILOT_AGENT_ENABLED=true`
- confidence gating — `LOW` confidence results are skipped by default
- duplicate detection — won't flood the repo with duplicate issues for the same class
- the Copilot Coding Agent opens a **draft PR** which still requires human review and approval before merge
- no code is modified locally — all fix generation happens on GitHub's side

