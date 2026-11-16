package comp3011;

/** A transcribed word with start/end times relative to the original media. */
public record SubtitleWord(long startUs, long endUs, String text) {
    public SubtitleWord {
        if (startUs < 0 || endUs <= startUs || text == null || text.isBlank()) {
            throw new IllegalArgumentException("A subtitle word needs text and valid media timestamps");
        }
        text = text.strip();
    }
}
