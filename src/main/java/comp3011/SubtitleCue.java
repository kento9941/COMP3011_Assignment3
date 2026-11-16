package comp3011;

/** A complete subtitle, shown from startUs (inclusive) to endUs (exclusive). */
public record SubtitleCue(long startUs, long endUs, String text) {
    public SubtitleCue {
        if (startUs < 0 || endUs <= startUs || text == null || text.isBlank()) {
            throw new IllegalArgumentException("A subtitle cue needs text and valid media timestamps");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length > 2) {
            throw new IllegalArgumentException("Subtitles may contain at most two lines");
        }
        for (String line : lines) {
            if (line.isBlank() || line.codePointCount(0, line.length()) > 42) {
                throw new IllegalArgumentException("Subtitle lines must contain 1 to 42 characters");
            }
        }
    }
}
