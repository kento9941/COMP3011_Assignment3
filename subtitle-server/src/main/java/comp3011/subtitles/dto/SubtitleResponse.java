package comp3011.subtitles.dto;

import java.util.List;

public record SubtitleResponse(List<Word> words) {
    /** Start and end are measured in seconds from the start of the uploaded audio. */
    public record Word(double start, double end, String word) {}
}
