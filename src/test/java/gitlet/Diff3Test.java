package gitlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Diff3Test {

    @AfterEach
    public void tearDown() {
        Store.closeDefault();
    }

    @Test
    public void testAutoMergeDisjointEdits() {
        String base = "line 1\nline 2\nline 3\nline 4\n";
        String ours = "line 1 ours\nline 2\nline 3\nline 4\n";
        String theirs = "line 1\nline 2\nline 3\nline 4 theirs\n";

        Diff3.Result res = Diff3.merge(base, ours, theirs);
        assertFalse(res.hasConflict());
        assertEquals("line 1 ours\nline 2\nline 3\nline 4 theirs\n", res.text());
    }

    @Test
    public void testAutoMergeAdjacentNonOverlappingEdits() {
        String base = "alpha\nbeta\ngamma\n";
        String ours = "alpha-ours\nbeta\ngamma\n";
        String theirs = "alpha\nbeta-theirs\ngamma\n";

        Diff3.Result res = Diff3.merge(base, ours, theirs);
        assertFalse(res.hasConflict());
        assertEquals("alpha-ours\nbeta-theirs\ngamma\n", res.text());
    }

    @Test
    public void testConflictingHunkMarkersOnlyAroundConflict() {
        String base = "header\nmiddle\nfooter\n";
        String ours = "header-ours\nmiddle-ours\nfooter\n";
        String theirs = "header\nmiddle-theirs\nfooter\n";

        Diff3.Result res = Diff3.merge(base, ours, theirs);
        assertTrue(res.hasConflict());
        String expected =
                "header-ours\n"
                + "<<<<<<< HEAD\n"
                + "middle-ours\n"
                + "=======\n"
                + "middle-theirs\n"
                + ">>>>>>>\n"
                + "footer\n";
        assertEquals(expected, res.text());
    }

    @Test
    public void testMultipleConflictingHunksWithCleanMiddle() {
        String base = "a\nb\nc\nd\ne\n";
        String ours = "a_ours\nb\nc\nd_ours\ne\n";
        String theirs = "a_theirs\nb\nc\nd_theirs\ne\n";

        Diff3.Result res = Diff3.merge(base, ours, theirs);
        assertTrue(res.hasConflict());
        String expected =
                "<<<<<<< HEAD\n"
                + "a_ours\n"
                + "=======\n"
                + "a_theirs\n"
                + ">>>>>>>\n"
                + "b\n"
                + "c\n"
                + "<<<<<<< HEAD\n"
                + "d_ours\n"
                + "=======\n"
                + "d_theirs\n"
                + ">>>>>>>\n"
                + "e\n";
        assertEquals(expected, res.text());
    }

    @Test
    public void testDeleteVsModifyWholeFile() {
        String base = "This is not a wug.\n";
        String ours = "This is a wug.\n";
        String theirs = ""; // deleted in given branch

        Diff3.Result res = Diff3.merge(base, ours, theirs);
        assertTrue(res.hasConflict());
        String expected =
                "<<<<<<< HEAD\n"
                + "This is a wug.\n"
                + "=======\n"
                + ">>>>>>>\n";
        assertEquals(expected, res.text());

        // Symmetric case: deleted in current (ours), modified in given (theirs)
        Diff3.Result resSym = Diff3.merge(base, "", "Modified in theirs.\n");
        assertTrue(resSym.hasConflict());
        String expectedSym =
                "<<<<<<< HEAD\n"
                + "=======\n"
                + "Modified in theirs.\n"
                + ">>>>>>>\n";
        assertEquals(expectedSym, resSym.text());
    }

    @Test
    public void testDeleteVsModifyHunkLevel() {
        String base = "keep 1\ntarget line\nkeep 2\n";
        String ours = "keep 1\nkeep 2\n"; // deleted target line
        String theirs = "keep 1\ntarget modified\nkeep 2\n"; // modified target line

        Diff3.Result res = Diff3.merge(base, ours, theirs);
        assertTrue(res.hasConflict());
        String expected =
                "keep 1\n"
                + "<<<<<<< HEAD\n"
                + "=======\n"
                + "target modified\n"
                + ">>>>>>>\n"
                + "keep 2\n";
        assertEquals(expected, res.text());
    }

    @Test
    public void testIdenticalBothSidesNoConflict() {
        String base = "line 1\nline 2\n";
        String sameEdit = "line 1\nline 2 updated identically\nline 3\n";

        Diff3.Result res = Diff3.merge(base, sameEdit, sameEdit);
        assertFalse(res.hasConflict());
        assertEquals(sameEdit, res.text());

        // Identical hunk edit alongside disjoint edit
        String base2 = "top\nmid\nbot\n";
        String ours2 = "top_ours\nmid_same\nbot\n";
        String theirs2 = "top\nmid_same\nbot_theirs\n";
        Diff3.Result res2 = Diff3.merge(base2, ours2, theirs2);
        assertFalse(res2.hasConflict());
        assertEquals("top_ours\nmid_same\nbot_theirs\n", res2.text());
    }

    @Test
    public void testBinaryFallbackToWholeFileConflict() {
        byte[] base = new byte[]{0x00, 0x01, 0x02};
        byte[] ours = new byte[]{0x00, 0x0a, 0x0b};
        byte[] theirs = new byte[]{0x00, 0x0c, 0x0d};

        Diff3.Result res = Diff3.merge(base, ours, theirs);
        assertTrue(res.hasConflict());
        byte[] expected = Diff3.wholeFileConflict(ours, theirs);
        assertArrayEquals(expected, res.content());
    }

    @Test
    public void testParseConflictSections() {
        String content =
                "clean top\n"
                + "<<<<<<< HEAD\n"
                + "ours 1\n"
                + "ours 2\n"
                + "=======\n"
                + "theirs 1\n"
                + ">>>>>>>\n"
                + "clean bottom\n";
        assertTrue(Diff3.hasConflictMarkers(content));
        assertFalse(Diff3.hasConflictMarkers("just normal text\nno markers\n"));

        List<Diff3.Section> sections = Diff3.parseSections(content);
        assertEquals(3, sections.size());
        assertEquals(Diff3.SectionType.CONTEXT, sections.get(0).type());
        assertFalse(sections.get(0).isConflict());
        assertEquals(List.of("clean top"), sections.get(0).lines());
        assertEquals(Diff3.SectionType.CONFLICT, sections.get(1).type());
        assertTrue(sections.get(1).isConflict());
        assertEquals(List.of("ours 1", "ours 2"), sections.get(1).ours());
        assertEquals(List.of("theirs 1"), sections.get(1).theirs());
        assertEquals(Diff3.SectionType.CONTEXT, sections.get(2).type());
        assertFalse(sections.get(2).isConflict());
        assertEquals(List.of("clean bottom"), sections.get(2).lines());
    }

    @Test
    public void testNoTrailingNewlinePreserved() {
        String base = "line 1\nline 2\nline 3";
        String ours = "line 1 ours\nline 2\nline 3";
        String theirs = "line 1\nline 2\nline 3 theirs";

        Diff3.Result cleanRes = Diff3.merge(base, ours, theirs);
        assertFalse(cleanRes.hasConflict());
        assertEquals("line 1 ours\nline 2\nline 3 theirs", cleanRes.text());
        assertFalse(cleanRes.text().endsWith("\n"));

        String conflictOurs = "line 1\nline 2 ours\nline 3";
        String conflictTheirs = "line 1\nline 2 theirs\nline 3";
        Diff3.Result conflictRes = Diff3.merge(base, conflictOurs, conflictTheirs);
        assertTrue(conflictRes.hasConflict());
        String expectedConflict =
                "line 1\n"
                + "<<<<<<< HEAD\n"
                + "line 2 ours\n"
                + "=======\n"
                + "line 2 theirs\n"
                + ">>>>>>>\n"
                + "line 3";
        assertEquals(expectedConflict, conflictRes.text());
        assertFalse(conflictRes.text().endsWith("\n"));
    }

    @Test
    public void testTrailingNewlineUsesOnlyContributingInputs() {
        Diff3.Result clean = Diff3.merge("a\nb\n", "A\nb", "a\nB");
        assertFalse(clean.hasConflict());
        assertEquals("A\nB", clean.text());

        Diff3.Result conflict = Diff3.merge("base\n", "ours", "theirs");
        assertTrue(conflict.hasConflict());
        assertEquals(
                "<<<<<<< HEAD\n"
                + "ours\n"
                + "=======\n"
                + "theirs\n"
                + ">>>>>>>",
                conflict.text());
    }

    @Test
    public void testAdjacentConflictingHunksEmitSeparateMarkerRegions() {
        String base = "line 1\nline 2\n";
        String ours = "line 1 ours\nline 2 ours\n";
        String theirs = "line 1 theirs\nline 2 theirs\n";

        Diff3.Result res = Diff3.merge(base, ours, theirs);
        assertTrue(res.hasConflict());
        String expected =
                "<<<<<<< HEAD\n"
                + "line 1 ours\n"
                + "=======\n"
                + "line 1 theirs\n"
                + ">>>>>>>\n"
                + "<<<<<<< HEAD\n"
                + "line 2 ours\n"
                + "=======\n"
                + "line 2 theirs\n"
                + ">>>>>>>\n";
        assertEquals(expected, res.text());

        List<Diff3.Section> parsed = Diff3.parseSections(res.text());
        assertEquals(2, parsed.size());
        assertTrue(parsed.get(0).isConflict());
        assertEquals(List.of("line 1 ours"), parsed.get(0).ours());
        assertEquals(List.of("line 1 theirs"), parsed.get(0).theirs());
        assertTrue(parsed.get(1).isConflict());
        assertEquals(List.of("line 2 ours"), parsed.get(1).ours());
        assertEquals(List.of("line 2 theirs"), parsed.get(1).theirs());
    }

    @Test
    public void testResolveOursAndTheirsMultiHunkAndTrailingNewline() {
        String multiHunk =
                "<<<<<<< HEAD\n"
                + "a_ours\n"
                + "=======\n"
                + "a_theirs\n"
                + ">>>>>>>\n"
                + "b\n"
                + "c\n"
                + "<<<<<<< HEAD\n"
                + "d_ours_1\n"
                + "d_ours_2\n"
                + "=======\n"
                + "d_theirs_1\n"
                + ">>>>>>>\n"
                + "e\n";

        assertEquals("a_ours\nb\nc\nd_ours_1\nd_ours_2\ne\n", Diff3.resolve(multiHunk, "--ours"));
        assertEquals("a_theirs\nb\nc\nd_theirs_1\ne\n", Diff3.resolve(multiHunk, "theirs"));
        assertEquals("a_ours\na_theirs\nb\nc\nd_ours_1\nd_ours_2\nd_theirs_1\ne\n", Diff3.resolve(multiHunk, "--combined"));

        // Preserve absence of trailing newline
        String noTrailingLf =
                "line 1\n"
                + "<<<<<<< HEAD\n"
                + "line 2 ours\n"
                + "=======\n"
                + "line 2 theirs\n"
                + ">>>>>>>\n"
                + "line 3";
        String resolvedNoLf = Diff3.resolve(noTrailingLf, "ours");
        assertEquals("line 1\nline 2 ours\nline 3", resolvedNoLf);
        assertFalse(resolvedNoLf.endsWith("\n"));
    }

    @Test
    public void testResolveThrowsWhenNoConflictMarkersOrInvalidSide() {
        GitletException noMarkerEx = assertThrows(
                GitletException.class,
                () -> Diff3.resolve("clean line 1\nclean line 2\n", "--ours")
        );
        assertEquals("File has no conflict markers.", noMarkerEx.getMessage());

        GitletException badSideEx = assertThrows(
                GitletException.class,
                () -> Diff3.resolve("<<<<<<< HEAD\na\n=======\nb\n>>>>>>>\n", "--invalid")
        );
        assertEquals("Incorrect operands.", badSideEx.getMessage());
    }

    @Test
    public void testRepositoryResolveWritesAndStagesWorkingTreeFile(@TempDir Path tempDir) throws Exception {
        File gitletDir = tempDir.resolve(".gitlet").toFile();
        Store.useDir(gitletDir);

        // 1. Not initialized repo error
        Path notesPath = tempDir.resolve("notes.txt");
        Files.writeString(notesPath, "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>>\n", StandardCharsets.UTF_8);
        GitletException notInitEx = assertThrows(
                GitletException.class,
                () -> Repository.resolve("notes.txt", "--ours")
        );
        assertEquals("Not in an initialized Gitlet directory.", notInitEx.getMessage());

        // Initialize repo in tempDir
        gitletDir.mkdirs();
        Store.createSchema();
        String c0 = Commit.first_commit();
        Store.setHead("master");
        Repository.currHead = c0;

        // 2. Missing file error
        GitletException missingEx = assertThrows(
                GitletException.class,
                () -> Repository.resolve("missing.txt", "--ours")
        );
        assertEquals("File does not exist.", missingEx.getMessage());

        // 3. Path traversal error
        GitletException escapeEx = assertThrows(
                GitletException.class,
                () -> Repository.resolve("../outside.txt", "--ours")
        );
        assertEquals("Invalid file path.", escapeEx.getMessage());

        // 4. Multi-hunk resolve (--ours) writes file and stages it
        String conflicted =
                "intro\n"
                + "<<<<<<< HEAD\n"
                + "ours-1\n"
                + "=======\n"
                + "theirs-1\n"
                + ">>>>>>>\n"
                + "middle\n"
                + "<<<<<<< HEAD\n"
                + "ours-2\n"
                + "=======\n"
                + "theirs-2\n"
                + ">>>>>>>\n"
                + "outro\n";
        Files.writeString(notesPath, conflicted, StandardCharsets.UTF_8);

        Repository.resolve("notes.txt", "--ours");
        String expectedOurs = "intro\nours-1\nmiddle\nours-2\noutro\n";
        assertEquals(expectedOurs, Files.readString(notesPath, StandardCharsets.UTF_8));
        assertFalse(Diff3.hasConflictMarkers(Files.readAllBytes(notesPath)));

        String expectedOursHash = Utils.hash(expectedOurs.getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedOursHash, Store.stagedAdditions().get("notes.txt"));
        assertArrayEquals(expectedOurs.getBytes(StandardCharsets.UTF_8), Store.getBlob(expectedOursHash));

        // 5. Resolving an already-resolved (no-marker) file throws GitletException
        GitletException alreadyCleanEx = assertThrows(
                GitletException.class,
                () -> Repository.resolve("notes.txt", "--theirs")
        );
        assertEquals("File has no conflict markers.", alreadyCleanEx.getMessage());

        // 6. Multi-hunk resolve (--theirs) and resolveAll (--all)
        Files.writeString(notesPath, conflicted, StandardCharsets.UTF_8);
        Path secondPath = tempDir.resolve("second.txt");
        Files.writeString(secondPath, "<<<<<<< HEAD\nleft\n=======\nright\n>>>>>>>\n", StandardCharsets.UTF_8);

        List<String> resolvedAll = Repository.resolveAll("--theirs");
        assertEquals(List.of("notes.txt", "second.txt"), resolvedAll);
        assertEquals("intro\ntheirs-1\nmiddle\ntheirs-2\noutro\n", Files.readString(notesPath, StandardCharsets.UTF_8));
        assertEquals("right\n", Files.readString(secondPath, StandardCharsets.UTF_8));
        assertTrue(Store.stagedAdditions().containsKey("notes.txt"));
        assertTrue(Store.stagedAdditions().containsKey("second.txt"));
    }
}
