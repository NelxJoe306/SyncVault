package gitlet;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Line-level 3-way merge (diff3) and conflict marker parser.
 *
 * Emits CS61B-compatible conflict markers for conflicting hunks:
 * <pre>
 * &lt;&lt;&lt;&lt;&lt;&lt;&lt; HEAD
 * ...ours lines...
 * =======
 * ...theirs lines...
 * &gt;&gt;&gt;&gt;&gt;&gt;&gt;
 * </pre>
 */
public final class Diff3 {

    public static final String MARKER_HEAD = "<<<<<<< HEAD";
    public static final String MARKER_SEP = "=======";
    public static final String MARKER_END = ">>>>>>>";

    private Diff3() {
    }

    /**
     * Result of a 3-way merge operation.
     */
    public record Result(byte[] content, boolean hasConflict) {
        public String text() {
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    /**
     * Represents a change hunk between base[bStart..bEnd) and target[tStart..tEnd).
     */
    public record Hunk(int bStart, int bEnd, int tStart, int tEnd) {
        public boolean isPureInsertion() {
            return bStart == bEnd;
        }
    }

    /**
     * Type of a parsed or merged blob section.
     */
    public enum SectionType {
        CONTEXT("context"),
        CONFLICT("conflict");

        private final String jsonValue;

        SectionType(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        public String jsonValue() {
            return jsonValue;
        }
    }

    /**
     * Represents a parsed section of a text blob (either normal context lines or a conflict block).
     */
    public record Section(SectionType type, int startLine, List<String> lines, List<String> ours, List<String> theirs) {
        public boolean isConflict() {
            return type == SectionType.CONFLICT;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type.jsonValue());
            map.put("startLine", startLine);
            if (isConflict()) {
                map.put("ours", ours != null ? ours : Collections.emptyList());
                map.put("theirs", theirs != null ? theirs : Collections.emptyList());
            } else {
                map.put("lines", lines != null ? lines : Collections.emptyList());
            }
            return map;
        }
    }

    /**
     * Performs a 3-way merge on raw blob byte arrays.
     * Falls back to a whole-file conflict if any input is binary / invalid UTF-8,
     * or if a non-empty base file was deleted on one side and modified on the other.
     *
     * @param base   split-point (LCA) blob bytes (null or empty if absent at split)
     * @param ours   current branch (HEAD) blob bytes (null or empty if deleted/absent)
     * @param theirs given branch blob bytes (null or empty if deleted/absent)
     * @return merge Result containing merged bytes and whether any conflict occurred
     */
    public static Result merge(byte[] base, byte[] ours, byte[] theirs) {
        byte[] b = (base != null) ? base : new byte[0];
        byte[] o = (ours != null) ? ours : new byte[0];
        byte[] t = (theirs != null) ? theirs : new byte[0];

        // 1. Fast paths for identical sides or unmodified sides
        if (Arrays.equals(o, t)) {
            return new Result(o.clone(), false);
        }
        if (Arrays.equals(b, o)) {
            return new Result(t.clone(), false);
        }
        if (Arrays.equals(b, t)) {
            return new Result(o.clone(), false);
        }

        // 2. Decode UTF-8 text; fall back to whole-file conflict for binary / non-UTF8
        String bText = tryDecodeUtf8Text(b);
        String oText = tryDecodeUtf8Text(o);
        String tText = tryDecodeUtf8Text(t);
        if (bText == null || oText == null || tText == null) {
            return new Result(wholeFileConflict(o, t), true);
        }

        // 3. Delete vs modify on a whole file: if base was non-empty and one side deleted the file
        // while the other modified it, emit conflict between the surviving side and empty side.
        if (b.length > 0 && (o.length == 0 || t.length == 0)) {
            return new Result(wholeFileConflict(o, t), true);
        }

        return mergeText(bText, oText, tText);
    }

    /**
     * Performs a line-level 3-way merge on UTF-8 strings.
     */
    public static Result merge(String base, String ours, String theirs) {
        String b = (base != null) ? base : "";
        String o = (ours != null) ? ours : "";
        String t = (theirs != null) ? theirs : "";
        return merge(
                b.getBytes(StandardCharsets.UTF_8),
                o.getBytes(StandardCharsets.UTF_8),
                t.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Convenience helper returning just the merged bytes.
     */
    public static byte[] mergeBytes(byte[] base, byte[] ours, byte[] theirs) {
        return merge(base, ours, theirs).content();
    }

    private static Result mergeText(String baseText, String oursText, String theirsText) {
        List<String> baseLines = splitLines(baseText);
        List<String> oursLines = splitLines(oursText);
        List<String> theirsLines = splitLines(theirsText);

        if (oursLines.equals(theirsLines)) {
            boolean trailingNewline = endsWithNewline(oursText) || endsWithNewline(theirsText);
            String joined = joinLines(oursLines, trailingNewline);
            return new Result(joined.getBytes(StandardCharsets.UTF_8), false);
        }
        if (baseLines.equals(oursLines)) {
            String joined = joinLines(theirsLines, endsWithNewline(theirsText));
            return new Result(joined.getBytes(StandardCharsets.UTF_8), false);
        }
        if (baseLines.equals(theirsLines)) {
            String joined = joinLines(oursLines, endsWithNewline(oursText));
            return new Result(joined.getBytes(StandardCharsets.UTF_8), false);
        }

        List<Hunk> hunksO = computeHunks(baseLines, oursLines);
        List<Hunk> hunksT = computeHunks(baseLines, theirsLines);

        // Represent output as a sequence of clean context sections and individual conflict blocks
        List<Section> rawSections = new ArrayList<>();
        boolean hasConflict = false;

        int nB = baseLines.size();
        int b = 0;
        int iO = 0;
        int iT = 0;

        while (iO < hunksO.size() || iT < hunksT.size()) {
            Hunk hO = (iO < hunksO.size()) ? hunksO.get(iO) : null;
            Hunk hT = (iT < hunksT.size()) ? hunksT.get(iT) : null;

            int nextB;
            if (hO != null && hT != null) {
                nextB = Math.min(hO.bStart(), hT.bStart());
            } else if (hO != null) {
                nextB = hO.bStart();
            } else {
                nextB = hT.bStart();
            }

            // Copy unchanged base lines before the next hunk
            while (b < nextB) {
                addContextLine(rawSections, baseLines.get(b));
                b++;
            }

            if (hT == null) {
                addContextSlice(rawSections, oursLines, hO.tStart(), hO.tEnd());
                b = hO.bEnd();
                iO++;
                continue;
            }
            if (hO == null) {
                addContextSlice(rawSections, theirsLines, hT.tStart(), hT.tEnd());
                b = hT.bEnd();
                iT++;
                continue;
            }

            // Check if one hunk strictly precedes the other without overlapping
            boolean bothPureInsertionsAtSamePoint =
                    hO.isPureInsertion() && hT.isPureInsertion() && hO.bStart() == hT.bStart();

            if (!bothPureInsertionsAtSamePoint && hO.bEnd() <= hT.bStart()) {
                addContextSlice(rawSections, oursLines, hO.tStart(), hO.tEnd());
                b = hO.bEnd();
                iO++;
                continue;
            }
            if (!bothPureInsertionsAtSamePoint && hT.bEnd() <= hO.bStart()) {
                addContextSlice(rawSections, theirsLines, hT.tStart(), hT.tEnd());
                b = hT.bEnd();
                iT++;
                continue;
            }

            // Hunks overlap: expand collision cluster across any transitively overlapping hunks
            int bClusterStart = Math.min(hO.bStart(), hT.bStart());
            int bClusterEnd = Math.max(hO.bEnd(), hT.bEnd());
            Hunk firstHO = hO;
            Hunk lastHO = hO;
            Hunk firstHT = hT;
            Hunk lastHT = hT;
            iO++;
            iT++;

            boolean expanded = true;
            while (expanded) {
                expanded = false;
                while (iO < hunksO.size() && hunksO.get(iO).bStart() < bClusterEnd) {
                    lastHO = hunksO.get(iO);
                    bClusterEnd = Math.max(bClusterEnd, lastHO.bEnd());
                    iO++;
                    expanded = true;
                }
                while (iT < hunksT.size() && hunksT.get(iT).bStart() < bClusterEnd) {
                    lastHT = hunksT.get(iT);
                    bClusterEnd = Math.max(bClusterEnd, lastHT.bEnd());
                    iT++;
                    expanded = true;
                }
            }

            int oClusterStart = firstHO.tStart() - (firstHO.bStart() - bClusterStart);
            int oClusterEnd = lastHO.tEnd() + (bClusterEnd - lastHO.bEnd());
            int tClusterStart = firstHT.tStart() - (firstHT.bStart() - bClusterStart);
            int tClusterEnd = lastHT.tEnd() + (bClusterEnd - lastHT.bEnd());

            List<String> oursSlice = oursLines.subList(oClusterStart, oClusterEnd);
            List<String> theirsSlice = theirsLines.subList(tClusterStart, tClusterEnd);

            if (oursSlice.equals(theirsSlice)) {
                for (String line : oursSlice) {
                    addContextLine(rawSections, line);
                }
            } else {
                hasConflict = true;
                addConflictBlock(rawSections, oursSlice, theirsSlice);
            }

            b = bClusterEnd;
        }

        // Copy any remaining unchanged base lines after the last hunk
        while (b < nB) {
            addContextLine(rawSections, baseLines.get(b));
            b++;
        }

        List<String> outLines = new ArrayList<>();
        for (Section s : rawSections) {
            if (s.isConflict()) {
                outLines.add(MARKER_HEAD);
                outLines.addAll(s.ours());
                outLines.add(MARKER_SEP);
                outLines.addAll(s.theirs());
                outLines.add(MARKER_END);
            } else {
                outLines.addAll(s.lines());
            }
        }

        boolean trailingNewline =
                endsWithNewline(baseText) || endsWithNewline(oursText) || endsWithNewline(theirsText);
        String merged = joinLines(outLines, trailingNewline);
        return new Result(merged.getBytes(StandardCharsets.UTF_8), hasConflict);
    }

    private static void addContextLine(List<Section> sections, String line) {
        if (!sections.isEmpty() && !sections.get(sections.size() - 1).isConflict()) {
            sections.get(sections.size() - 1).lines().add(line);
        } else {
            List<String> list = new ArrayList<>();
            list.add(line);
            sections.add(new Section(SectionType.CONTEXT, 1, list, null, null));
        }
    }

    private static void addContextSlice(List<Section> sections, List<String> src, int start, int end) {
        for (int i = start; i < end; i++) {
            addContextLine(sections, src.get(i));
        }
    }

    private static void addConflictBlock(List<Section> sections, List<String> ours, List<String> theirs) {
        sections.add(new Section(SectionType.CONFLICT, 1, null, new ArrayList<>(ours), new ArrayList<>(theirs)));
    }

    /**
     * Computes change hunks between base and target using LCS.
     * Equal-length multi-line replacements are decomposed into 1-line replacements
     * so adjacent independent line edits can merge cleanly.
     */
    static List<Hunk> computeHunks(List<String> base, List<String> target) {
        int n = base.size();
        int m = target.size();
        int[][] dp = new int[n + 1][m + 1];

        for (int i = n - 1; i >= 0; i--) {
            String bLine = base.get(i);
            for (int j = m - 1; j >= 0; j--) {
                if (bLine.equals(target.get(j))) {
                    dp[i][j] = 1 + dp[i + 1][j + 1];
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<Hunk> hunks = new ArrayList<>();
        int prevB = -1;
        int prevT = -1;
        int i = 0;
        int j = 0;

        while (i < n && j < m) {
            if (base.get(i).equals(target.get(j))) {
                int bStart = prevB + 1;
                int tStart = prevT + 1;
                if (bStart < i || tStart < j) {
                    emitDecomposedHunk(hunks, bStart, i, tStart, j);
                }
                prevB = i;
                prevT = j;
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }

        int bStart = prevB + 1;
        int tStart = prevT + 1;
        if (bStart < n || tStart < m) {
            emitDecomposedHunk(hunks, bStart, n, tStart, m);
        }

        return hunks;
    }

    private static void emitDecomposedHunk(List<Hunk> hunks, int bStart, int bEnd, int tStart, int tEnd) {
        int bLen = bEnd - bStart;
        int tLen = tEnd - tStart;
        if (bLen > 1 && bLen == tLen) {
            for (int d = 0; d < bLen; d++) {
                hunks.add(new Hunk(bStart + d, bStart + d + 1, tStart + d, tStart + d + 1));
            }
        } else {
            hunks.add(new Hunk(bStart, bEnd, tStart, tEnd));
        }
    }

    /**
     * Splits text into logical lines (without line terminators).
     * Empty string yields an empty list.
     */
    static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        int len = text.length();
        int start = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                int end = (i > start && text.charAt(i - 1) == '\r') ? i - 1 : i;
                lines.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start < len) {
            lines.add(text.substring(start, len));
        }
        return lines;
    }

    private static boolean endsWithNewline(String text) {
        return text != null && !text.isEmpty() && text.charAt(text.length() - 1) == '\n';
    }

    private static String joinLines(List<String> lines, boolean trailingNewline) {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        if (trailingNewline) {
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Classic whole-file conflict marker dump (used for binary fallback or whole-file delete vs modify).
     */
    public static byte[] wholeFileConflict(byte[] current, byte[] given) {
        byte[] c = (current != null) ? current : new byte[0];
        byte[] g = (given != null) ? given : new byte[0];
        byte[] head = "<<<<<<< HEAD\n".getBytes(StandardCharsets.UTF_8);
        byte[] mid = "=======\n".getBytes(StandardCharsets.UTF_8);
        byte[] end = ">>>>>>>\n".getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + c.length + mid.length + g.length + end.length];
        int i = 0;
        System.arraycopy(head, 0, out, i, head.length);
        i += head.length;
        System.arraycopy(c, 0, out, i, c.length);
        i += c.length;
        System.arraycopy(mid, 0, out, i, mid.length);
        i += mid.length;
        System.arraycopy(g, 0, out, i, g.length);
        i += g.length;
        System.arraycopy(end, 0, out, i, end.length);
        return out;
    }

    /**
     * Attempts to decode bytes as strict UTF-8 without NUL bytes.
     * Returns null if bytes appear binary or invalid UTF-8.
     */
    public static String tryDecodeUtf8Text(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Checks whether a raw byte array is valid UTF-8 text containing conflict markers.
     */
    public static boolean hasConflictMarkers(byte[] bytes) {
        String text = tryDecodeUtf8Text(bytes);
        return text != null && hasConflictMarkers(text);
    }

    /**
     * Checks whether text contains at least one complete conflict marker block
     * (<<<<<<< HEAD ... ======= ... >>>>>>>).
     */
    public static boolean hasConflictMarkers(String text) {
        if (text == null || text.isEmpty() || !text.contains("<<<<<<<")) {
            return false;
        }
        for (Section s : parseSections(text)) {
            if (s.isConflict()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a text file into alternating context sections and conflict sections.
     */
    public static List<Section> parseSections(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> lines = splitLines(text);
        List<Section> sections = new ArrayList<>();
        List<String> contextBuf = new ArrayList<>();
        int contextStartLine = 1;

        int i = 0;
        int n = lines.size();
        while (i < n) {
            String line = lines.get(i);
            if (isStartMarker(line)) {
                int sepIdx = -1;
                int endIdx = -1;
                for (int j = i + 1; j < n; j++) {
                    String candidate = lines.get(j);
                    if (sepIdx < 0) {
                        if (isStartMarker(candidate)) {
                            break;
                        }
                        if (isSepMarker(candidate)) {
                            sepIdx = j;
                        }
                    } else {
                        if (isEndMarker(candidate)) {
                            endIdx = j;
                            break;
                        }
                    }
                }

                if (sepIdx > i && endIdx > sepIdx) {
                    if (!contextBuf.isEmpty()) {
                        sections.add(new Section(SectionType.CONTEXT, contextStartLine, new ArrayList<>(contextBuf), null, null));
                        contextBuf.clear();
                    }
                    List<String> ours = new ArrayList<>(lines.subList(i + 1, sepIdx));
                    List<String> theirs = new ArrayList<>(lines.subList(sepIdx + 1, endIdx));
                    sections.add(new Section(SectionType.CONFLICT, i + 1, null, ours, theirs));
                    i = endIdx + 1;
                    contextStartLine = i + 1;
                    continue;
                }
            }

            if (contextBuf.isEmpty()) {
                contextStartLine = i + 1;
            }
            contextBuf.add(line);
            i++;
        }

        if (!contextBuf.isEmpty()) {
            sections.add(new Section(SectionType.CONTEXT, contextStartLine, new ArrayList<>(contextBuf), null, null));
        }

        return sections;
    }

    private static boolean isStartMarker(String line) {
        String trimmed = line.stripTrailing();
        return trimmed.equals(MARKER_HEAD) || trimmed.startsWith("<<<<<<< ");
    }

    private static boolean isSepMarker(String line) {
        return line.stripTrailing().equals(MARKER_SEP);
    }

    private static boolean isEndMarker(String line) {
        String trimmed = line.stripTrailing();
        return trimmed.equals(MARKER_END) || trimmed.startsWith(">>>>>>> ");
    }
}
