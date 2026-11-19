package comp3011.subtitles.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import comp3011.subtitles.dto.SubtitleResponse.Word;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiTranscriptionResponse(List<Word> words, List<Segment> segments,
                                          Double duration, Usage usage) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(Double seconds) {}

    public Double usageSeconds() {
        return usage != null && usage.seconds() != null ? usage.seconds() : duration;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(double start, double end,
                          @JsonProperty("no_speech_prob") double noSpeechProbability,
                          @JsonProperty("avg_logprob") double averageLogProbability) {
        public boolean isNoSpeech() {
            return noSpeechProbability > 0.6 && averageLogProbability < -1.0;
        }

        public boolean contains(double seconds) {
            return start <= seconds && seconds < end;
        }
    }
}
