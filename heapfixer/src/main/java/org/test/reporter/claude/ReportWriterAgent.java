package org.test.reporter.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.test.AnalysisResult;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * ReportWriterAgent
 * Consumes an {@link AnalysisResult} from HeapAnalyzerAgent,} and produces a polished, self-contained HTML
 * report with AI-generated narrative insights for each section.
 * Process:
 *   1. Call Claude to generate developer-readable prose for the executive summary,
 *      root-cause narrative, and per-remediation rationale.
 *   2. Render a self-contained HTML file that embeds all data, CSS, and JS —
 *      no external dependencies, opens in any browser offline.
 * Usage:
 *   ReportWriterAgent writer = new ReportWriterAgent(System.getenv("ANTHROPIC_API_KEY"));
 *   Path reportPath = writer.writeReport(analysisResult, outputDir);
 */
public class ReportWriterAgent {

    private static final Logger LOG = Logger.getLogger(ReportWriterAgent.class.getName());

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL             = "claude-sonnet-4-20250514";
    private static final int    MAX_TOKENS        = 2048;

    private final String       apiKey;
    private final HttpClient   http;
    private final ObjectMapper mapper;

    public ReportWriterAgent(String apiKey) {
        this.apiKey  = Objects.requireNonNull(apiKey, "ANTHROPIC_API_KEY must not be null");
        this.http    = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper  = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Generate the HTML report and write it to outputDir/heap_analysis_report.html.
     *
     * @param result     Populated AnalysisResult from HeapAnalyzerAgent
     * @param outputDir  Directory to write the report into
     * @return           Path to the written HTML file
     */
    public Path writeReport(AnalysisResult result, Path outputDir) throws Exception {
        LOG.info("ReportWriterAgent: generating narrative insights via Claude…");
        Narratives narratives = generateNarratives(result);

        LOG.info("ReportWriterAgent: rendering HTML…");
        String html = renderHtml(result, narratives);

        Path outFile = outputDir.resolve("heap_analysis_report.html");
        Files.createDirectories(outputDir);
        Files.writeString(outFile, html, StandardCharsets.UTF_8);
        LOG.info("Report written → " + outFile.toAbsolutePath());
        return outFile;
    }

    // -------------------------------------------------------------------------
    //  Narrative generation via Claude
    // -------------------------------------------------------------------------

    record Narratives(
        String executiveSummary,
        String rootCauseNarrative,
        String remediationIntro,
        String technicalDetails
    ) {}

    private Narratives generateNarratives(AnalysisResult r) throws Exception {
        String systemPrompt = """
            You are a senior Java performance engineer writing a professional heap dump analysis
            report for a development team. Your prose is clear, technical, and actionable.
            Avoid filler phrases. Write in present tense.
            Respond ONLY with a JSON object — no markdown, no extra text.
            """;

        String rc = (r.rootCause != null)
            ? "Class: " + r.rootCause.responsibleClass + "\n"
              + "Pattern: " + r.rootCause.leakPatternType + "\n"
              + "Description: " + r.rootCause.description + "\n"
              + "Explanation: " + r.rootCause.detailedExplanation
            : "Root cause unknown";

        String topObjects = (r.topRetainedObjects != null)
                ? r.topRetainedObjects.stream().limit(5)
                  .map(o -> o.className + " (" + (o.retainedHeapPct != null ? String.format("%.1f", o.retainedHeapPct) : "0.0") + "% retained heap)")
                  .collect(Collectors.joining(", "))
                : "";


        String remSteps = (r.remediation != null)
            ? String.join("\n", r.remediation)
            : "";

        String userPrompt = """
            Given this heap dump analysis data, write four narrative sections.
            Return ONLY a JSON object with these exact keys:

            {
              "executive_summary": "<3-4 sentences. State what leaked, how large, impact on the service, and urgency. No bullet points.>",
              "root_cause_narrative": "<4-5 sentences. Explain the technical mechanism of the leak: why objects are retained, what is holding them, why GC cannot collect them. Reference the responsible class and method by name.>",
              "remediation_intro": "<2-3 sentences introducing the remediation steps. Explain the general approach before the specific steps.>",
              "technical_details": "<3-4 sentences. Describe the object graph: top retained types, GC root chain structure, and what the allocation stack pattern reveals about when and where the leak happens.>"
            }

            INPUT DATA:
            Summary: %s
            Root cause: %s
            Top retained objects: %s
            Confidence: %s
            Estimated leak size MB: %s
            Remediation steps:
            %s
            """.formatted(
                nvl(r.summary),
                rc,
                topObjects,
                nvl(r.confidence),
                r.estimatedLeakSizeMb != null ? r.estimatedLeakSizeMb.toString() : "unknown",
                remSteps
            );

        String raw = callClaude(systemPrompt, userPrompt);
        var   node = parseJson(raw);
        return new Narratives(
            node.path("executive_summary").asText(""),
            node.path("root_cause_narrative").asText(""),
            node.path("remediation_intro").asText(""),
            node.path("technical_details").asText("")
        );
    }

    // -------------------------------------------------------------------------
    //  HTML rendering
    // -------------------------------------------------------------------------

    private String renderHtml(AnalysisResult r, Narratives n) {
        String timestamp = r.analyzedAt != null
            ? DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'")
                .withZone(ZoneId.of("UTC"))
                .format(Instant.parse(r.analyzedAt))
            : "Unknown";

        String dumpFile = r.heapDumpPath != null
            ? Path.of(r.heapDumpPath).getFileName().toString()
            : "unknown.hprof";

        String confidenceClass = switch (nvl(r.confidence).toUpperCase()) {
            case "HIGH"   -> "badge-high";
            case "MEDIUM" -> "badge-medium";
            default       -> "badge-low";
        };

        double leakMb = r.estimatedLeakSizeMb != null ? r.estimatedLeakSizeMb : 0.0;

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Heap Analysis — %s</title>
<style>
/* ── Reset & tokens ─────────────────────────────────────────── */
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{
  --bg:#0c0e12;--surface:#13161d;--surface2:#1a1e28;--surface3:#222736;
  --border:#2a2f3d;--border2:#353c50;
  --text:#e2e6f0;--text2:#9aa3b8;--text3:#5c6478;
  --red:#ff4d5e;--amber:#f5a623;--green:#3ddc84;--blue:#4fa3f7;
  --purple:#a78bfa;--teal:#2dd4bf;
  --font-mono:'JetBrains Mono','Fira Code','Cascadia Code',monospace;
  --font-sans:'DM Sans','Outfit',system-ui,sans-serif;
  --r:8px;--r2:12px;
}
html{font-size:15px;background:var(--bg);color:var(--text);font-family:var(--font-sans);
  -webkit-font-smoothing:antialiased}
body{min-height:100vh}

/* ── Google Fonts ──────────────────────────────────────────── */
@import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600&family=JetBrains+Mono:wght@400;500;600&display=swap');

/* ── Layout ──────────────────────────────────────────────────── */
.page{max-width:1100px;margin:0 auto;padding:48px 28px 80px}

/* ── Header ─────────────────────────────────────────────────── */
.header{border-bottom:1px solid var(--border);padding-bottom:32px;margin-bottom:40px}
.header-top{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;flex-wrap:wrap}
.logo-row{display:flex;align-items:center;gap:10px;margin-bottom:14px}
.logo-dot{width:10px;height:10px;border-radius:50%;background:var(--red);box-shadow:0 0 12px var(--red)}
.logo-text{font-family:var(--font-mono);font-size:11px;letter-spacing:.12em;text-transform:uppercase;color:var(--text3)}
.report-title{font-size:28px;font-weight:600;letter-spacing:-.02em;line-height:1.2;color:var(--text)}
.report-title span{color:var(--red)}
.meta-grid{display:flex;gap:28px;flex-wrap:wrap;margin-top:16px}
.meta-item{display:flex;flex-direction:column;gap:3px}
.meta-label{font-family:var(--font-mono);font-size:10px;text-transform:uppercase;letter-spacing:.1em;color:var(--text3)}
.meta-value{font-size:13px;color:var(--text2);font-family:var(--font-mono)}
.header-badges{display:flex;gap:8px;align-items:flex-start;flex-shrink:0}

/* ── Badges ─────────────────────────────────────────────────── */
.badge{display:inline-flex;align-items:center;gap:5px;padding:4px 10px;border-radius:100px;
  font-family:var(--font-mono);font-size:11px;font-weight:600;letter-spacing:.05em;text-transform:uppercase}
.badge::before{content:'';width:6px;height:6px;border-radius:50%;flex-shrink:0}
.badge-high{background:rgba(61,220,132,.12);color:var(--green);border:1px solid rgba(61,220,132,.25)}
.badge-high::before{background:var(--green);box-shadow:0 0 6px var(--green)}
.badge-medium{background:rgba(245,166,35,.12);color:var(--amber);border:1px solid rgba(245,166,35,.25)}
.badge-medium::before{background:var(--amber)}
.badge-low{background:rgba(79,163,247,.12);color:var(--blue);border:1px solid rgba(79,163,247,.25)}
.badge-low::before{background:var(--blue)}
.badge-leak{background:rgba(255,77,94,.12);color:var(--red);border:1px solid rgba(255,77,94,.25)}
.badge-leak::before{background:var(--red);box-shadow:0 0 6px var(--red)}

/* ── Sections ───────────────────────────────────────────────── */
.section{margin-bottom:40px;opacity:0;transform:translateY(16px);
  animation:fadeUp .45s ease forwards}
@keyframes fadeUp{to{opacity:1;transform:none}}
.section:nth-child(1){animation-delay:.05s}.section:nth-child(2){animation-delay:.1s}
.section:nth-child(3){animation-delay:.15s}.section:nth-child(4){animation-delay:.2s}
.section:nth-child(5){animation-delay:.25s}.section:nth-child(6){animation-delay:.3s}
.section:nth-child(7){animation-delay:.35s}
.section-header{display:flex;align-items:center;gap:10px;margin-bottom:18px}
.section-num{font-family:var(--font-mono);font-size:11px;color:var(--text3);
  border:1px solid var(--border2);border-radius:4px;padding:2px 7px;flex-shrink:0}
.section-title{font-size:15px;font-weight:600;letter-spacing:-.01em}
.section-rule{flex:1;height:1px;background:var(--border)}

/* ── Cards ───────────────────────────────────────────────────── */
.card{background:var(--surface);border:1px solid var(--border);border-radius:var(--r2);
  padding:22px;transition:border-color .2s}
.card:hover{border-color:var(--border2)}
.card-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:14px}

/* ── KPI cards ───────────────────────────────────────────────── */
.kpi{background:var(--surface2);border:1px solid var(--border);border-radius:var(--r2);
  padding:18px 20px;position:relative;overflow:hidden}
.kpi::after{content:'';position:absolute;bottom:0;left:0;right:0;height:2px}
.kpi.kpi-red::after{background:var(--red)}.kpi.kpi-amber::after{background:var(--amber)}
.kpi.kpi-blue::after{background:var(--blue)}.kpi.kpi-green::after{background:var(--green)}
.kpi-label{font-family:var(--font-mono);font-size:10px;text-transform:uppercase;
  letter-spacing:.1em;color:var(--text3);margin-bottom:8px}
.kpi-value{font-size:26px;font-weight:600;line-height:1;letter-spacing:-.03em}
.kpi-sub{font-size:12px;color:var(--text2);margin-top:5px}

/* ── Prose ───────────────────────────────────────────────────── */
.prose{font-size:14px;line-height:1.75;color:var(--text2)}
.prose strong{color:var(--text);font-weight:500}
.prose code{font-family:var(--font-mono);font-size:12px;background:var(--surface2);
  border:1px solid var(--border);border-radius:4px;padding:1px 5px;color:var(--purple)}

/* ── Histogram ──────────────────────────────────────────────── */
.histogram{display:flex;flex-direction:column;gap:10px}
.histo-row{display:grid;grid-template-columns:1fr 2fr 120px;gap:14px;align-items:center}
.histo-class{font-family:var(--font-mono);font-size:11px;color:var(--text);
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
.histo-class .pkg{color:var(--text3)}
.histo-bar-wrap{height:8px;background:var(--surface3);border-radius:4px;overflow:hidden}
.histo-bar{height:100%;border-radius:4px;transition:width 1s cubic-bezier(.23,1,.32,1);
  width:0 !important}
.histo-bar.bar-suspect{background:linear-gradient(90deg,var(--red),#ff8a96)}
.histo-bar.bar-normal{background:linear-gradient(90deg,var(--blue),var(--teal))}
.histo-meta{text-align:right;font-family:var(--font-mono);font-size:11px;color:var(--text2)}
.histo-meta .pct{color:var(--text3);font-size:10px;margin-left:4px}
.histo-note{font-size:11px;color:var(--text3);margin-top:3px;
  grid-column:1/-1;padding-left:0;line-height:1.4}

/* ── GC Root chains ─────────────────────────────────────────── */
.chain-card{background:var(--surface);border:1px solid var(--border);
  border-radius:var(--r2);overflow:hidden;margin-bottom:12px}
.chain-header{background:var(--surface2);border-bottom:1px solid var(--border);
  padding:12px 18px;display:flex;align-items:center;justify-content:space-between;gap:12px}
.chain-label{font-family:var(--font-mono);font-size:12px;color:var(--teal);font-weight:500}
.chain-size{font-family:var(--font-mono);font-size:11px;color:var(--red)}
.chain-body{padding:16px 18px}
.chain-steps{display:flex;flex-direction:column;gap:0}
.chain-step{display:flex;align-items:flex-start;gap:10px;padding:6px 0}
.chain-step + .chain-step{border-top:1px solid var(--border)}
.step-connector{display:flex;flex-direction:column;align-items:center;flex-shrink:0;
  padding-top:3px}
.step-dot{width:8px;height:8px;border-radius:50%;border:2px solid var(--border2);flex-shrink:0}
.step-dot.root{border-color:var(--red);background:rgba(255,77,94,.2)}
.step-dot.leaf{border-color:var(--amber);background:rgba(245,166,35,.2)}
.step-line{width:1px;flex:1;background:var(--border);min-height:14px;margin:2px 0}
.step-content{min-width:0;flex:1}
.step-from{font-family:var(--font-mono);font-size:11px;color:var(--text);font-weight:500}
.step-via{font-size:11px;color:var(--text3);margin-top:2px}
.step-via code{font-family:var(--font-mono);font-size:10px;color:var(--purple)}
.chain-root-label{font-size:11px;color:var(--text3);margin-bottom:8px}
.chain-root-label strong{color:var(--amber);font-weight:500}
.chain-suspect{background:rgba(245,166,35,.06);border:1px solid rgba(245,166,35,.2);
  border-radius:6px;padding:8px 12px;margin-top:10px;display:flex;align-items:center;gap:8px}
.chain-suspect-icon{font-size:13px}
.chain-suspect-text{font-family:var(--font-mono);font-size:11px;color:var(--amber)}

/* ── Allocator stacks ───────────────────────────────────────── */
.stack-card{background:var(--surface);border:1px solid var(--border);
  border-radius:var(--r2);margin-bottom:12px;overflow:hidden}
.stack-header{background:var(--surface2);border-bottom:1px solid var(--border);
  padding:12px 18px;display:flex;justify-content:space-between;align-items:center;gap:12px}
.stack-method{font-family:var(--font-mono);font-size:12px;color:var(--green);font-weight:500}
.stack-stats{display:flex;gap:14px}
.stack-stat{font-family:var(--font-mono);font-size:11px;color:var(--text3)}
.stack-stat span{color:var(--text2)}
.stack-body{padding:14px 18px}
.stack-pattern{font-size:12px;color:var(--amber);margin-bottom:12px;line-height:1.5;
  background:rgba(245,166,35,.06);border-left:2px solid var(--amber);padding:8px 12px;
  border-radius:0 6px 6px 0}
.stack-frames{background:var(--bg);border:1px solid var(--border);border-radius:6px;
  padding:12px 14px;overflow-x:auto}
.stack-frame{font-family:var(--font-mono);font-size:11px;line-height:1.8;display:block;
  white-space:nowrap}
.stack-frame:first-child{color:var(--purple);font-weight:500}
.stack-frame:not(:first-child){color:var(--text3)}
.stack-frame .app{color:var(--text2)}

/* ── Root cause ─────────────────────────────────────────────── */
.root-cause-card{background:var(--surface);border:1px solid rgba(255,77,94,.3);
  border-radius:var(--r2);overflow:hidden}
.root-cause-top{background:rgba(255,77,94,.07);border-bottom:1px solid rgba(255,77,94,.2);
  padding:16px 22px;display:flex;align-items:center;gap:12px}
.root-cause-icon{font-size:20px}
.root-cause-desc{font-size:14px;font-weight:500;color:var(--text)}
.root-cause-body{padding:20px 22px;display:grid;grid-template-columns:1fr 1fr;gap:20px}
.rc-field{display:flex;flex-direction:column;gap:5px}
.rc-label{font-family:var(--font-mono);font-size:10px;text-transform:uppercase;
  letter-spacing:.1em;color:var(--text3)}
.rc-value{font-family:var(--font-mono);font-size:12px;color:var(--text);font-weight:500}
.rc-pattern{background:rgba(255,77,94,.1);border:1px solid rgba(255,77,94,.2);
  color:var(--red);font-family:var(--font-mono);font-size:11px;padding:4px 10px;
  border-radius:100px;display:inline-flex;width:fit-content}
.keywords{display:flex;gap:6px;flex-wrap:wrap;margin-top:4px}
.keyword{background:var(--surface3);border:1px solid var(--border2);color:var(--purple);
  font-family:var(--font-mono);font-size:10px;padding:3px 8px;border-radius:4px}
.rc-narrative{padding:0 22px 22px;border-top:1px solid var(--border)}
.rc-narrative .prose{margin-top:16px}

/* ── Remediation ────────────────────────────────────────────── */
.remediation-intro{margin-bottom:18px}
.rem-list{display:flex;flex-direction:column;gap:10px}
.rem-item{display:flex;align-items:flex-start;gap:14px;background:var(--surface);
  border:1px solid var(--border);border-radius:var(--r2);padding:16px;
  transition:border-color .2s,background .2s;cursor:pointer}
.rem-item:hover{border-color:var(--border2);background:var(--surface2)}
.rem-item.done{border-color:rgba(61,220,132,.3);background:rgba(61,220,132,.04)}
.rem-num{width:26px;height:26px;border-radius:50%;background:var(--surface3);
  border:1px solid var(--border2);display:flex;align-items:center;justify-content:center;
  font-family:var(--font-mono);font-size:11px;color:var(--text3);flex-shrink:0;
  transition:all .2s}
.rem-item.done .rem-num{background:rgba(61,220,132,.15);border-color:rgba(61,220,132,.4);
  color:var(--green)}
.rem-item.done .rem-num::after{content:'✓';font-size:12px}
.rem-item:not(.done) .rem-num-text{display:block}
.rem-item.done .rem-num-text{display:none}
.rem-text{font-size:13px;line-height:1.65;color:var(--text2);flex:1}
.rem-text code{font-family:var(--font-mono);font-size:11px;background:var(--surface2);
  border:1px solid var(--border);border-radius:4px;padding:1px 5px;color:var(--purple)}

/* ── Footer ─────────────────────────────────────────────────── */
.footer{border-top:1px solid var(--border);padding-top:24px;margin-top:60px;
  display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px}
.footer-left{font-family:var(--font-mono);font-size:11px;color:var(--text3)}
.footer-right{font-family:var(--font-mono);font-size:11px;color:var(--text3)}

/* ── Responsive ─────────────────────────────────────────────── */
@media(max-width:640px){
  .histo-row{grid-template-columns:1fr;gap:4px}
  .root-cause-body{grid-template-columns:1fr}
  .page{padding:24px 16px 60px}
  .report-title{font-size:22px}
}
</style>
</head>
<body>
<div class="page">

<!-- ── HEADER ─────────────────────────────────────────────────── -->
<header class="header">
  <div class="header-top">
    <div>
      <div class="logo-row">
        <div class="logo-dot"></div>
        <span class="logo-text">Java Heap Analysis Report</span>
      </div>
      <h1 class="report-title">Memory Leak<br><span>Diagnostic</span></h1>
      <div class="meta-grid">
        <div class="meta-item">
          <span class="meta-label">Dump file</span>
          <span class="meta-value" title="%s">%s</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">Analysed at</span>
          <span class="meta-value">%s</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">Leak size</span>
          <span class="meta-value">%s MB</span>
        </div>
      </div>
    </div>
    <div class="header-badges">
      <span class="badge %s">%s confidence</span>
      <span class="badge badge-leak">Memory Leak</span>
    </div>
  </div>
</header>

<!-- ── KPI ROW ─────────────────────────────────────────────────── -->
<section class="section">
  <div class="card-grid">
    <div class="kpi kpi-red">
      <div class="kpi-label">Estimated Leak</div>
      <div class="kpi-value" style="color:var(--red)">%s<span style="font-size:16px"> MB</span></div>
      <div class="kpi-sub">retained heap lost</div>
    </div>
    <div class="kpi kpi-amber">
      <div class="kpi-label">Top Suspect</div>
      <div class="kpi-value" style="color:var(--amber);font-size:16px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">%s</div>
      <div class="kpi-sub">primary leaking class</div>
    </div>
    <div class="kpi kpi-blue">
      <div class="kpi-label">Objects Retained</div>
      <div class="kpi-value" style="color:var(--blue)">%s</div>
      <div class="kpi-sub">leak suspect instances</div>
    </div>
    <div class="kpi kpi-green">
      <div class="kpi-label">Leak pattern</div>
      <div class="kpi-value" style="color:var(--purple);font-size:14px;word-break:break-all">%s</div>
      <div class="kpi-sub">pattern type</div>
    </div>
  </div>
</section>

<!-- ── 1. EXECUTIVE SUMMARY ───────────────────────────────────── -->
<section class="section">
  <div class="section-header">
    <span class="section-num">01</span>
    <span class="section-title">Executive Summary</span>
    <div class="section-rule"></div>
  </div>
  <div class="card">
    <p class="prose">%s</p>
  </div>
</section>

<!-- ── 2. HEAP HISTOGRAM ──────────────────────────────────────── -->
<section class="section">
  <div class="section-header">
    <span class="section-num">02</span>
    <span class="section-title">Top Retained Objects by Heap</span>
    <div class="section-rule"></div>
  </div>
  <div class="card">
    <div class="histogram" id="histogram">
%s
    </div>
  </div>
</section>

<!-- ── 3. GC ROOT CHAINS ──────────────────────────────────────── -->
<section class="section">
  <div class="section-header">
    <span class="section-num">03</span>
    <span class="section-title">GC Root Chains</span>
    <div class="section-rule"></div>
  </div>
%s
</section>

<!-- ── 4. ALLOCATOR STACKS ────────────────────────────────────── -->
<section class="section">
  <div class="section-header">
    <span class="section-num">04</span>
    <span class="section-title">Dominant Allocator Stacks</span>
    <div class="section-rule"></div>
  </div>
%s
</section>

<!-- ── 5. ROOT CAUSE ──────────────────────────────────────────── -->
<section class="section">
  <div class="section-header">
    <span class="section-num">05</span>
    <span class="section-title">Root Cause Analysis</span>
    <div class="section-rule"></div>
  </div>
  <div class="root-cause-card">
    <div class="root-cause-top">
      <span class="root-cause-icon">⚑</span>
      <span class="root-cause-desc">%s</span>
    </div>
    <div class="root-cause-body">
      <div class="rc-field">
        <span class="rc-label">Responsible class</span>
        <span class="rc-value">%s</span>
      </div>
      <div class="rc-field">
        <span class="rc-label">Method</span>
        <span class="rc-value">%s</span>
      </div>
      <div class="rc-field">
        <span class="rc-label">Leak pattern type</span>
        <span class="rc-pattern">%s</span>
      </div>
      <div class="rc-field">
        <span class="rc-label">Code search keywords</span>
        <div class="keywords">%s</div>
      </div>
    </div>
    <div class="rc-narrative">
      <p class="prose">%s</p>
    </div>
  </div>
</section>

<!-- ── 6. TECHNICAL DETAILS ───────────────────────────────────── -->
<section class="section">
  <div class="section-header">
    <span class="section-num">06</span>
    <span class="section-title">Technical Details</span>
    <div class="section-rule"></div>
  </div>
  <div class="card">
    <p class="prose">%s</p>
  </div>
</section>

<!-- ── 7. REMEDIATION ────────────────────────────────────────── -->
<section class="section">
  <div class="section-header">
    <span class="section-num">07</span>
    <span class="section-title">Remediation Steps</span>
    <div class="section-rule"></div>
  </div>
  <p class="prose remediation-intro">%s</p>
  <div class="rem-list" style="margin-top:16px">
%s
  </div>
</section>

<footer class="footer">
  <span class="footer-left">Generated by HeapAnalyzerAgent · Claude claude-sonnet-4-20250514</span>
  <span class="footer-right">%s</span>
</footer>

</div><!-- /page -->
<script>
// Animate histogram bars after page loads
document.addEventListener('DOMContentLoaded', () => {
  requestAnimationFrame(() => {
    document.querySelectorAll('.histo-bar').forEach(b => {
      b.style.width = b.dataset.pct + '%%';
    });
  });
});

// Remediation checklist toggle
document.querySelectorAll('.rem-item').forEach(item => {
  item.addEventListener('click', () => item.classList.toggle('done'));
});
</script>
</body>
</html>
""".formatted(
    /* title   */ dumpFile,
    /* dump full path title attr */ nvl(r.heapDumpPath),
    /* dump file name           */ dumpFile,
    /* timestamp                */ timestamp,
    /* leak MB                  */ leakMb > 0 ? String.format("%.1f", leakMb) : "N/A",
    /* confidence badge class   */ confidenceClass,
    /* confidence text          */ nvl(r.confidence).toLowerCase(),
    /* kpi leak                 */ leakMb > 0 ? String.format("%.1f", leakMb) : "?",
    /* kpi top suspect          */ topSuspectShortName(r),
    /* kpi instance count       */ topSuspectInstanceCount(r),
    /* kpi leak pattern         */ rcField(r, "leakPatternType"),
    /* executive summary        */ esc(n.executiveSummary()),
    /* histogram html           */ buildHistogramHtml(r),
    /* gc root chains html      */ buildGcChainsHtml(r),
    /* allocator stacks html    */ buildAllocatorStacksHtml(r),
    /* root cause desc          */ esc(rcField(r, "description")),
    /* responsible class        */ esc(rcField(r, "responsibleClass")),
    /* responsible method       */ esc(rcField(r, "responsibleMethod")),
    /* leak pattern type        */ esc(rcField(r, "leakPatternType")),
    /* keywords html            */ buildKeywordsHtml(r),
    /* root cause narrative     */ esc(n.rootCauseNarrative()),
    /* technical details        */ esc(n.technicalDetails()),
    /* remediation intro        */ esc(n.remediationIntro()),
    /* remediation items html   */ buildRemediationHtml(r),
    /* footer timestamp         */ timestamp
        );
    }

    // -------------------------------------------------------------------------
    //  HTML fragment builders
    // -------------------------------------------------------------------------

    private String buildHistogramHtml(AnalysisResult r) {
        if (r.topRetainedObjects == null || r.topRetainedObjects.isEmpty())
            return "<p class='prose'>No histogram data available.</p>";

        double maxPct = r.topRetainedObjects.stream()
            .mapToDouble(o -> o.retainedHeapPct != null ? o.retainedHeapPct : 0)
            .max().orElse(100);

        StringBuilder sb = new StringBuilder();
        for (var obj : r.topRetainedObjects) {
            String fqn   = nvl(obj.className);
            int    dot   = fqn.lastIndexOf('.');
            String pkg   = dot > 0 ? fqn.substring(0, dot + 1) : "";
            String cls   = dot > 0 ? fqn.substring(dot + 1)    : fqn;
            double pct   = obj.retainedHeapPct != null ? obj.retainedHeapPct : 0;
            double barW  = maxPct > 0 ? (pct / maxPct * 100) : 0;
            String barCls = Boolean.TRUE.equals(obj.isSuspect) ? "bar-suspect" : "bar-normal";
            String bytes  = obj.retainedHeapBytes != null
                ? formatBytes(obj.retainedHeapBytes) : "—";

            sb.append("""
                <div class="histo-row">
                  <div class="histo-class"><span class="pkg">%s</span>%s%s</div>
                  <div class="histo-bar-wrap">
                    <div class="histo-bar %s" data-pct="%.1f" style="width:0"></div>
                  </div>
                  <div class="histo-meta">%s<span class="pct">%.1f%%</span></div>
                  <div class="histo-note">%s</div>
                </div>
                """.formatted(
                    esc(pkg), esc(cls),
                    Boolean.TRUE.equals(obj.isSuspect) ? " <span style='color:var(--red);font-size:10px'>●&nbsp;suspect</span>" : "",
                    barCls, barW, bytes, pct,
                    esc(nvl(obj.agentNote))
                ));
        }
        return sb.toString();
    }

    private String buildGcChainsHtml(AnalysisResult r) {
        if (r.gcRootChains == null || r.gcRootChains.isEmpty())
            return "<div class='card'><p class='prose'>No GC root chain data available.</p></div>";

        StringBuilder sb = new StringBuilder();
        for (var chain : r.gcRootChains) {
            String size = chain.retainedHeapBytes != null
                ? formatBytes(chain.retainedHeapBytes) : "";
            sb.append("""
                <div class="chain-card">
                  <div class="chain-header">
                    <span class="chain-label">%s</span>
                    %s
                  </div>
                  <div class="chain-body">
                    <div class="chain-root-label">Root type: <strong>%s</strong> — %s</div>
                    <div class="chain-steps">
                """.formatted(
                    esc(nvl(chain.chainLabel)),
                    size.isBlank() ? "" : "<span class='chain-size'>" + esc(size) + " retained</span>",
                    esc(nvl(chain.rootType)),
                    esc(nvl(chain.rootObject))
                ));

            if (chain.referencePath != null) {
                for (int i = 0; i < chain.referencePath.size(); i++) {
                    var step   = chain.referencePath.get(i);
                    boolean isFirst = i == 0;
                    boolean isLast  = i == chain.referencePath.size() - 1;
                    sb.append("""
                        <div class="chain-step">
                          <div class="step-connector">
                            <div class="step-dot %s"></div>
                            %s
                          </div>
                          <div class="step-content">
                            <div class="step-from">%s</div>
                            <div class="step-via">via field <code>%s</code> → %s</div>
                          </div>
                        </div>
                        """.formatted(
                            isFirst ? "root" : (isLast ? "leaf" : ""),
                            isLast  ? "" : "<div class='step-line'></div>",
                            esc(nvl(step.from)),
                            esc(nvl(step.viaField)),
                            esc(nvl(step.to))
                        ));
                }
            }

            sb.append("</div>"); // chain-steps
            if (chain.suspectObject != null) {
                sb.append("""
                    <div class="chain-suspect">
                      <span class="chain-suspect-icon">⚠</span>
                      <span class="chain-suspect-text">%s</span>
                    </div>""".formatted(esc(chain.suspectObject)));
            }
            sb.append("</div></div>"); // chain-body, chain-card
        }
        return sb.toString();
    }

    private String buildAllocatorStacksHtml(AnalysisResult r) {
        if (r.dominantAllocatorStacks == null || r.dominantAllocatorStacks.isEmpty())
            return "<div class='card'><p class='prose'>No allocator stack data available. MAT may not have captured allocation stacks — ensure the heap dump was taken with -XX:+HeapDumpOnOutOfMemoryError.</p></div>";

        StringBuilder sb = new StringBuilder();
        for (var stack : r.dominantAllocatorStacks) {
            String objCount = stack.objectCount    != null ? formatLong(stack.objectCount) : "—";
            String retBytes = stack.retainedHeapBytes != null ? formatBytes(stack.retainedHeapBytes) : "—";

            sb.append("""
                <div class="stack-card">
                  <div class="stack-header">
                    <span class="stack-method">%s</span>
                    <div class="stack-stats">
                      <span class="stack-stat">objects: <span>%s</span></span>
                      <span class="stack-stat">retained: <span>%s</span></span>
                    </div>
                  </div>
                  <div class="stack-body">
                """.formatted(esc(nvl(stack.allocatorMethod)), objCount, retBytes));

            if (stack.leakPattern != null)
                sb.append("<div class='stack-pattern'>").append(esc(stack.leakPattern)).append("</div>");

            if (stack.stackFrames != null && !stack.stackFrames.isEmpty()) {
                sb.append("<div class='stack-frames'>");
                for (String frame : stack.stackFrames) {
                    boolean isApp = frame != null && !frame.startsWith("java.")
                        && !frame.startsWith("javax.") && !frame.startsWith("sun.")
                        && !frame.startsWith("org.apache.");
                    sb.append("<code class='stack-frame")
                      .append(isApp ? " app" : "")
                      .append("'>").append(esc(nvl(frame))).append("</code>");
                }
                sb.append("</div>");
            }
            sb.append("</div></div>"); // stack-body, stack-card
        }
        return sb.toString();
    }

    private String buildKeywordsHtml(AnalysisResult r) {
        if (r.rootCause == null || r.rootCause.codeSearchKeywords == null)
            return "";
        return r.rootCause.codeSearchKeywords.stream()
            .map(k -> "<span class='keyword'>" + esc(k) + "</span>")
            .collect(Collectors.joining());
    }

    private String buildRemediationHtml(AnalysisResult r) {
        if (r.remediation == null || r.remediation.isEmpty())
            return "<p class='prose'>No remediation steps available.</p>";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String step : r.remediation) {
            sb.append("""
                <div class="rem-item">
                  <div class="rem-num">
                    <span class="rem-num-text">%d</span>
                  </div>
                  <div class="rem-text">%s</div>
                </div>
                """.formatted(i++, codeHighlight(esc(step))));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    private String topSuspectShortName(AnalysisResult r) {
        if (r.topRetainedObjects == null || r.topRetainedObjects.isEmpty()) return "—";
        String fqn = nvl(r.topRetainedObjects.get(0).className);
        int dot = fqn.lastIndexOf('.');
        return dot > 0 ? fqn.substring(dot + 1) : fqn;
    }

    private String topSuspectInstanceCount(AnalysisResult r) {
        if (r.topRetainedObjects == null || r.topRetainedObjects.isEmpty()) return "—";
        Long c = r.topRetainedObjects.get(0).instanceCount;
        return c != null ? formatLong(c) : "—";
    }

    private String rcField(AnalysisResult r, String field) {
        if (r.rootCause == null) return "—";
        return switch (field) {
            case "description"       -> nvl(r.rootCause.description);
            case "responsibleClass"  -> nvl(r.rootCause.responsibleClass);
            case "responsibleMethod" -> nvl(r.rootCause.responsibleMethod);
            case "leakPatternType"   -> nvl(r.rootCause.leakPatternType);
            default                  -> "—";
        };
    }

    /** Wraps backtick-quoted tokens in <code> for HTML display. */
    private String codeHighlight(String s) {
        return s.replaceAll("`([^`]+)`",
            "<code>$1</code>");
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L) return String.format("%.2f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)     return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1_024L)         return String.format("%.1f KB", bytes / 1_024.0);
        return bytes + " B";
    }

    private static String formatLong(long v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000)     return String.format("%.1fK", v / 1_000.0);
        return String.valueOf(v);
    }

    /** HTML-escape a string to prevent XSS in data values. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    // -------------------------------------------------------------------------
    //  Claude API
    // -------------------------------------------------------------------------

    private String callClaude(String system, String user) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL).put("max_tokens", MAX_TOKENS).put("system", system);
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role","user")
            .putArray("content").addObject().put("type","text").put("text", user);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(ANTHROPIC_API_URL))
            .header("Content-Type","application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version","2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(body), StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(90))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("Anthropic API " + resp.statusCode() + ": " + resp.body());
        return mapper.readTree(resp.body()).path("content").get(0).path("text").asText();
    }

    private com.fasterxml.jackson.databind.JsonNode parseJson(String raw) throws Exception {
        String s = raw.strip()
            .replaceFirst("^```[a-zA-Z]*\\s*","").replaceFirst("```\\s*$","").strip();
        try { return mapper.readTree(s); }
        catch (Exception e) {
            int a = s.indexOf('{'), b = s.lastIndexOf('}');
            if (a >= 0 && b > a) return mapper.readTree(s.substring(a, b+1));
            throw e;
        }
    }
}
