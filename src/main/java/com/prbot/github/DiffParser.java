package com.prbot.github;

import com.prbot.config.BotProperties;
import com.prbot.config.GitHubProperties;
import com.prbot.model.ParsedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Transforms raw GitHub file diffs into clean, AI-ready context.
 *
 * Key responsibilities:
 *   1. Filter out non-reviewable files (images, lock files, generated code)
 *   2. Classify files by type (backend vs frontend) for targeted prompting
 *   3. Cap file size to avoid blowing token budgets
 *   4. Format diff context in a way that maximises AI review quality
 *
 * Interview talking point: "I filter noise before sending to the AI.
 * Sending a 500-line package-lock.json gives zero signal and costs tokens.
 * Signal-to-noise ratio directly affects review quality — and cost."
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DiffParser {

    private final BotProperties botProperties;
    private final GitHubProperties gitHubProperties;

    // Language detection by extension
    private static final Map<String, String> LANGUAGE_MAP = Map.ofEntries(
        Map.entry(".java",       "Java"),
        Map.entry(".kt",         "Kotlin"),
        Map.entry(".scala",      "Scala"),
        Map.entry(".py",         "Python"),
        Map.entry(".go",         "Go"),
        Map.entry(".rs",         "Rust"),
        Map.entry(".ts",         "TypeScript"),
        Map.entry(".js",         "JavaScript"),
        Map.entry(".jsx",        "React JSX"),
        Map.entry(".tsx",        "React TSX"),
        Map.entry(".vue",        "Vue"),
        Map.entry(".sql",        "SQL"),
        Map.entry(".sh",         "Shell"),
        Map.entry(".rb",         "Ruby"),
        Map.entry(".php",        "PHP"),
        Map.entry(".cs",         "C#"),
        Map.entry(".cpp",        "C++"),
        Map.entry(".html",       "HTML"),
        Map.entry(".css",        "CSS"),
        Map.entry(".scss",       "SCSS")
    );

    // Frontend file extensions — currently skipped, future scope
    private static final Set<String> FRONTEND_EXTENSIONS = Set.of(
        ".jsx", ".tsx", ".vue", ".html", ".css", ".scss", ".sass", ".less"
    );

    // Backend file extensions — actively reviewed
    private static final Set<String> BACKEND_EXTENSIONS = Set.of(
        ".java", ".kt", ".scala", ".py", ".go", ".rs",
        ".ts", ".js", ".sql", ".rb", ".php", ".cs", ".cpp", ".sh"
    );

    public List<ParsedFile> parse(List<GHPullRequestFileDetail> files) {
        List<String> skipExtensions = botProperties.getSkipExtensions();
        int maxFiles = gitHubProperties.getMaxFilesPerPr();
        int maxAdditions = gitHubProperties.getMaxAdditionsPerFile();

        List<ParsedFile> parsed = files.stream()
            .filter(f -> isReviewable(f, skipExtensions))
            .filter(f -> f.getPatch() != null && !f.getPatch().isBlank())
            .filter(f -> f.getAdditions() >= botProperties.getReview().getMinLinesThreshold())
            .map(f -> toParseFile(f, maxAdditions))
            .filter(f -> f != null)
            .limit(maxFiles)
            .collect(Collectors.toList());

        log.info("Parsed {}/{} files for review (skipped {})",
            parsed.size(), files.size(), files.size() - parsed.size());

        return parsed;
    }

    private boolean isReviewable(GHPullRequestFileDetail file, List<String> skipExtensions) {
        String ext = getExtension(file.getFilename());
        if (skipExtensions.contains(ext)) {
            log.debug("Skipping {} — extension {} in skip list", file.getFilename(), ext);
            return false;
        }
        // Skip deleted files — nothing to review
        if ("removed".equals(file.getStatus())) {
            log.debug("Skipping {} — file was deleted", file.getFilename());
            return false;
        }
        // Skip generated files by naming convention
        if (isGenerated(file.getFilename())) {
            log.debug("Skipping {} — appears to be generated", file.getFilename());
            return false;
        }
        return true;
    }

    private ParsedFile toParseFile(GHPullRequestFileDetail file, int maxAdditions) {
        String ext = getExtension(file.getFilename());
        String language = LANGUAGE_MAP.getOrDefault(ext, "Unknown");
        ParsedFile.FileType fileType = classifyFileType(ext);
        String patch = file.getPatch();

        // Truncate very large diffs — better to review 200 meaningful lines
        // than overwhelm the model with 800 lines of noise
        if (file.getAdditions() > maxAdditions) {
            log.info("Truncating large file {} ({} additions > {} limit)",
                file.getFilename(), file.getAdditions(), maxAdditions);
            patch = truncatePatch(patch, maxAdditions);
        }

        int lineCount = countAddedLines(patch);

        String context = buildContext(file.getFilename(), language, patch);

        return ParsedFile.builder()
            .filename(file.getFilename())
            .language(language)
            .fileType(fileType)
            .additions(file.getAdditions())
            .deletions(file.getDeletions())
            .patch(patch)
            .context(context)
            .lineCount(lineCount)
            .build();
    }

    private ParsedFile.FileType classifyFileType(String ext) {
        if (BACKEND_EXTENSIONS.contains(ext))  return ParsedFile.FileType.BACKEND;
        if (FRONTEND_EXTENSIONS.contains(ext)) return ParsedFile.FileType.FRONTEND;
        return ParsedFile.FileType.UNKNOWN;
    }

    /**
     * Build a richly formatted context block for the AI.
     * Format matters — structured input produces structured output.
     */
    private String buildContext(String filename, String language, String patch) {
        return """
            ### File: `%s` (%s)
            ```diff
            %s
            ```
            """.formatted(filename, language, patch);
    }

    private String truncatePatch(String patch, int maxLines) {
        String[] lines = patch.split("\n");
        if (lines.length <= maxLines) return patch;

        StringBuilder sb = new StringBuilder();
        int addedCount = 0;
        for (String line : lines) {
            sb.append(line).append("\n");
            if (line.startsWith("+") && !line.startsWith("+++")) addedCount++;
            if (addedCount >= maxLines) break;
        }
        sb.append("\n... [truncated — file too large for full review]");
        return sb.toString();
    }

    private int countAddedLines(String patch) {
        if (patch == null) return 0;
        return (int) patch.lines()
            .filter(l -> l.startsWith("+") && !l.startsWith("+++"))
            .count();
    }

    private boolean isGenerated(String filename) {
        String lower = filename.toLowerCase();
        return lower.contains("generated")
            || lower.contains(".min.")
            || lower.endsWith(".pb.go")
            || lower.endsWith("_pb2.py")
            || lower.contains("__generated__");
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }
}
