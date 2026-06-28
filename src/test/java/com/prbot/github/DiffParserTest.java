package com.prbot.github;

import com.prbot.config.BotProperties;
import com.prbot.config.GitHubProperties;
import com.prbot.model.ParsedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHPullRequestFileDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DiffParser")
class DiffParserTest {

    private DiffParser parser;
    private BotProperties botProps;
    private GitHubProperties githubProps;

    @BeforeEach
    void setUp() {
        botProps = new BotProperties();
        BotProperties.Review review = new BotProperties.Review();
        review.setMinLinesThreshold(1);
        botProps.setReview(review);
        botProps.setSkipExtensions(List.of(".lock", ".png", ".md", ".yaml", ".yml",
            ".xml", ".json", ".html", ".css", ".scss"));

        githubProps = new GitHubProperties();
        githubProps.setMaxFilesPerPr(20);
        githubProps.setMaxAdditionsPerFile(400);

        parser = new DiffParser(botProps, githubProps);
    }

    @Test
    @DisplayName("parses Java file correctly")
    void parsesJavaFile() {
        GHPullRequestFileDetail file = mockFile("src/OrderService.java", "modified",
            10, 2, "@@ -1,3 +1,10 @@\n+import java.util.List;\n+public class OrderService {}");

        List<ParsedFile> result = parser.parse(List.of(file));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFilename()).isEqualTo("src/OrderService.java");
        assertThat(result.get(0).getLanguage()).isEqualTo("Java");
        assertThat(result.get(0).getFileType()).isEqualTo(ParsedFile.FileType.BACKEND);
    }

    @Test
    @DisplayName("skips lock files")
    void skipsLockFiles() {
        GHPullRequestFileDetail file = mockFile("package-lock.json", "modified",
            500, 0, "+lots of lock content");

        List<ParsedFile> result = parser.parse(List.of(file));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("skips deleted files")
    void skipsDeletedFiles() {
        GHPullRequestFileDetail file = mockFile("src/OldService.java", "removed",
            0, 50, null);

        List<ParsedFile> result = parser.parse(List.of(file));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("skips files with no patch")
    void skipsFilesWithNoPatch() {
        GHPullRequestFileDetail file = mockFile("image.png", "added", 0, 0, null);
        List<ParsedFile> result = parser.parse(List.of(file));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("classifies frontend files correctly")
    void classifiesFrontend() {
        // Frontend is in skip list by default, so remove .tsx from skip to test classification
        botProps.setSkipExtensions(List.of(".lock", ".png", ".md"));
        GHPullRequestFileDetail file = mockFile("src/Button.tsx", "modified",
            20, 5, "@@ -1,5 +1,20 @@\n+export const Button = () => <button />;");

        List<ParsedFile> result = parser.parse(List.of(file));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFileType()).isEqualTo(ParsedFile.FileType.FRONTEND);
    }

    @Test
    @DisplayName("respects max files limit")
    void respectsMaxFilesLimit() {
        githubProps.setMaxFilesPerPr(2);
        List<GHPullRequestFileDetail> files = List.of(
            mockFile("A.java", "modified", 5, 0, "+code"),
            mockFile("B.java", "modified", 5, 0, "+code"),
            mockFile("C.java", "modified", 5, 0, "+code")
        );
        assertThat(parser.parse(files)).hasSize(2);
    }

    @Test
    @DisplayName("builds context string containing filename")
    void buildsContext() {
        GHPullRequestFileDetail file = mockFile("src/PaymentService.java", "modified",
            5, 1, "+public void pay() {}");
        List<ParsedFile> result = parser.parse(List.of(file));
        assertThat(result.get(0).getContext()).contains("PaymentService.java");
    }

    // ---- helpers ----

    private GHPullRequestFileDetail mockFile(String filename, String status,
                                              int added, int deleted, String patch) {
        GHPullRequestFileDetail f = mock(GHPullRequestFileDetail.class);
        when(f.getFilename()).thenReturn(filename);
        when(f.getStatus()).thenReturn(status);
        when(f.getAdditions()).thenReturn(added);
        when(f.getDeletions()).thenReturn(deleted);
        when(f.getPatch()).thenReturn(patch);
        return f;
    }
}
