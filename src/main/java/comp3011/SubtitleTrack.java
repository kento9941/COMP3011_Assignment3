package comp3011;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable cues, shared by playback and the eventual transcription client. */
public final class SubtitleTrack {
    private static final long SECOND_US = 1_000_000;
    private static final long MAX_DURATION_US = 7 * SECOND_US;
    private final List<SubtitleCue> cues;

    public SubtitleTrack(List<SubtitleCue> cues) {
        this.cues = cues.stream().sorted(Comparator.comparingLong(SubtitleCue::startUs)).toList();
        long previousEnd = 0;
        for (SubtitleCue cue : this.cues) {
            if (cue.startUs() < previousEnd) {
                throw new IllegalArgumentException("Subtitle cues must not overlap");
            }
            previousEnd = cue.endUs();
        }
    }

    public List<SubtitleCue> cues() {
        return cues;
    }

    public String textAt(long timestampUs) {
        int low = 0;
        int high = cues.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            SubtitleCue cue = cues.get(middle);
            if (timestampUs < cue.startUs()) high = middle - 1;
            else if (timestampUs >= cue.endUs()) low = middle + 1;
            else return cue.text();
        }
        return "";
    }

    /** Prefer phrase boundaries; split further to satisfy the display limits. */
    public static SubtitleTrack fromWords(List<SubtitleWord> input) {
        List<SubtitleWord> words = new ArrayList<>();
        for (SubtitleWord word : input.stream()
                .sorted(Comparator.comparingLong(SubtitleWord::startUs)).toList()) {
            // An unusually long token still must respect the 42-character limit.
            String normal = word.text().replaceAll("\\s+", " ");
            int[] characters = normal.codePoints().toArray();
            int pieces = (characters.length + 41) / 42;
            for (int piece = 0; piece < pieces; piece++) {
                int offset = piece * 42;
                long start = word.startUs() + (word.endUs() - word.startUs()) * piece / pieces;
                long end = word.startUs() + (word.endUs() - word.startUs()) * (piece + 1) / pieces;
                words.add(new SubtitleWord(start, Math.max(start + 1, end),
                        new String(characters, offset, Math.min(42, characters.length - offset))));
            }
        }

        List<SubtitleCue> result = new ArrayList<>();
        int first = 0;
        while (first < words.size()) {
            SubtitleWord beginning = words.get(first);
            String text = beginning.text();
            int last = first;
            while (last + 1 < words.size()) {
                SubtitleWord current = words.get(last);
                SubtitleWord next = words.get(last + 1);
                boolean phraseEnd = current.text().matches(".*[.!?,;:][\"')]*$");
                if (next.startUs() - current.endUs() >= 500_000
                        || (phraseEnd && current.endUs() - beginning.startUs() >= SECOND_US)) break;
                String candidate = text + " " + next.text();
                if (next.endUs() - beginning.startUs() > MAX_DURATION_US || wrap(candidate) == null) break;
                text = candidate;
                last++;
            }
            long end = Math.min(beginning.startUs() + MAX_DURATION_US,
                    Math.max(words.get(last).endUs(), beginning.startUs() + SECOND_US));
            // Rapid dialogue can require a cue shorter than one second. Never overlap the next cue.
            if (last + 1 < words.size()) end = Math.min(end, words.get(last + 1).startUs());
            if (end > beginning.startUs()) {
                result.add(new SubtitleCue(beginning.startUs(), end, wrap(text)));
            }
            first = last + 1;
        }
        return new SubtitleTrack(result);
    }

    private static String wrap(String text) {
        if (length(text) <= 42) return text;
        int bestBreak = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int index = 1; index < text.length() - 1; index++) {
            if (text.charAt(index) != ' ') continue;
            String before = text.substring(0, index);
            String after = text.substring(index + 1);
            if (length(before) > 42 || length(after) > 42) continue;
            int score = Math.abs(length(before) - length(after));
            if (!before.matches(".*[.!?,;:][\"')]*$")) score += 100;
            if (score < bestScore) {
                bestScore = score;
                bestBreak = index;
            }
        }
        return bestBreak < 0 ? null : text.substring(0, bestBreak) + "\n" + text.substring(bestBreak + 1);
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
