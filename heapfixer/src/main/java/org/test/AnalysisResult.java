package org.test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;

/**
 * Structured output produced by HeapAnalyzerAgent.
 *
 * This is the JSON document the agent returns, containing:
 *   - top retained heap objects
 *   - GC root chains leading to suspects
 *   - dominant allocation call stacks
 *   - a root cause summary
 *   - remediation suggestions
 */
public class AnalysisResult {

    @JsonProperty("heap_dump_path")
    public String heapDumpPath;

    @JsonProperty("analyzed_at")
    public String analyzedAt;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("estimated_leak_size_mb")
    public Double estimatedLeakSizeMb;

    /** Top N object types by retained heap size. */
    @JsonProperty("top_retained_objects")
    public List<RetainedObject> topRetainedObjects;

    /** GC root chains leading from roots down to the leak suspects. */
    @JsonProperty("gc_root_chains")
    public List<GcRootChain> gcRootChains;

    /** Dominant call stacks where the leaking allocations originate. */
    @JsonProperty("dominant_allocator_stacks")
    public List<AllocatorStack> dominantAllocatorStacks;

    /** Root cause as determined by the agent. */
    @JsonProperty("root_cause")
    public RootCause rootCause;

    /** Concrete remediation steps for developers. */
    @JsonProperty("remediation")
    public List<String> remediation;

    /** Confidence in the analysis (LOW / MEDIUM / HIGH). */
    @JsonProperty("confidence")
    public String confidence;

    // -------------------------------------------------------------------------
    //  Nested types
    // -------------------------------------------------------------------------

    public static class RetainedObject {
        /** Fully qualified class name. */
        @JsonProperty("class_name")
        public String className;

        /** Number of instances on the heap. */
        @JsonProperty("instance_count")
        public Long instanceCount;

        /** Total retained heap in bytes. */
        @JsonProperty("retained_heap_bytes")
        public Long retainedHeapBytes;

        /** Retained heap as a percentage of total heap. */
        @JsonProperty("retained_heap_pct")
        public Double retainedHeapPct;

        /** Whether MAT flagged this class as a leak suspect. */
        @JsonProperty("is_suspect")
        public Boolean isSuspect;

        /** One-line explanation from the agent. */
        @JsonProperty("agent_note")
        public String agentNote;
    }

    public static class GcRootChain {
        /** Short label for this chain, e.g. "Thread → HashMap → byte[]". */
        @JsonProperty("chain_label")
        public String chainLabel;

        /** GC root type: Thread, ClassLoader, JNI, Static, etc. */
        @JsonProperty("root_type")
        public String rootType;

        /** The root object description. */
        @JsonProperty("root_object")
        public String rootObject;

        /** Ordered list of references from root to the leak suspect. */
        @JsonProperty("reference_path")
        public List<ReferenceStep> referencePath;

        /** The final suspect object at the end of the chain. */
        @JsonProperty("suspect_object")
        public String suspectObject;

        /** Retained bytes held via this chain. */
        @JsonProperty("retained_heap_bytes")
        public Long retainedHeapBytes;
    }

    public static class ReferenceStep {
        /** Source object in this reference. */
        @JsonProperty("from")
        public String from;

        /** Field or collection type through which the reference is held. */
        @JsonProperty("via_field")
        public String viaField;

        /** Destination object in this reference. */
        @JsonProperty("to")
        public String to;
    }

    public static class AllocatorStack {
        /** The class + method where allocations are concentrated. */
        @JsonProperty("allocator_method")
        public String allocatorMethod;

        /** Number of live objects traceable to this allocation site. */
        @JsonProperty("object_count")
        public Long objectCount;

        /** Total retained heap for objects from this site. */
        @JsonProperty("retained_heap_bytes")
        public Long retainedHeapBytes;

        /** Abbreviated stack frames (most specific first). */
        @JsonProperty("stack_frames")
        public List<String> stackFrames;

        /** Agent interpretation of why this site is leaking. */
        @JsonProperty("leak_pattern")
        public String leakPattern;
    }

    public static class RootCause {
        /** One-line description of the root cause. */
        @JsonProperty("description")
        public String description;

        /** Fully-qualified class most likely responsible. */
        @JsonProperty("responsible_class")
        public String responsibleClass;

        /** Method name if identifiable. */
        @JsonProperty("responsible_method")
        public String responsibleMethod;

        /** Leak pattern category: UNBOUNDED_CACHE, LISTENER_NOT_REMOVED,
         *  STATIC_COLLECTION, THREAD_LOCAL_NOT_CLEARED, CLASSLOADER_LEAK, etc. */
        @JsonProperty("leak_pattern_type")
        public String leakPatternType;

        /** Detailed explanation the developer can read. */
        @JsonProperty("detailed_explanation")
        public String detailedExplanation;

        /** Keywords useful for searching the codebase. */
        @JsonProperty("code_search_keywords")
        public List<String> codeSearchKeywords;
    }

    // -------------------------------------------------------------------------
    //  Serialization helpers
    // -------------------------------------------------------------------------

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    public static AnalysisResult fromJson(String json) throws Exception {
        return MAPPER.readValue(json, AnalysisResult.class);
    }
}
