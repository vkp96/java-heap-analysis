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

