package com.prbot.model;

import lombok.Builder;
import lombok.Data;

/**
 * A cleaned, AI-ready representation of a single changed file in a PR.
 */
@Data
@Builder
public class ParsedFile {
    private String filename;
    private String language;
    private FileType fileType;       // BACKEND | FRONTEND | CONFIG | UNKNOWN
    private int additions;
    private int deletions;
    private String patch;            // raw unified diff
    private String context;          // formatted for AI prompt
    private int lineCount;

    public enum FileType {
        BACKEND,    // .java, .py, .go, .ts (server), .js (server)
        FRONTEND,   // .tsx, .jsx, .html, .css, .scss, .vue
        CONFIG,     // .yaml, .xml, .properties, .json (skipped in review)
        UNKNOWN
    }
}
