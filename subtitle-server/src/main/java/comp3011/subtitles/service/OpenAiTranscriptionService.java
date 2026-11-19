package comp3011.subtitles.service;

import java.util.List;

import comp3011.subtitles.dto.OpenAiTranscriptionResponse;
import comp3011.subtitles.dto.OpenAiTranscriptionResponse.Segment;
import comp3011.subtitles.dto.SubtitleResponse;
import comp3011.subtitles.dto.SubtitleResponse.Word;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("!stub")
public class OpenAiTranscriptionService implements TranscriptionService {
    private static final String OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions";
    private final RestClient restClient;
    private final GlobalUsageTracker usageTracker;

    public OpenAiTranscriptionService(@Value("${OPENAI_API_KEY}") String apiKey,
                                      RestClient.Builder restClientBuilder,
                                      GlobalUsageTracker usageTracker) {
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("OPENAI_API_KEY must be set to run the transcription server");
        }
        restClient = restClientBuilder.defaultHeaders(headers -> headers.setBearerAuth(apiKey)).build();
        this.usageTracker = usageTracker;
    }

    @Override
    public SubtitleResponse transcribe(MultipartFile audio) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", audio.getResource());
        form.add("model", "whisper-1");
        form.add("response_format", "verbose_json");
        form.add("timestamp_granularities[]", "word");
        form.add("timestamp_granularities[]", "segment");

        OpenAiTranscriptionResponse response;
        try {
            response = restClient.post()
                    .uri(OPENAI_URL)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(OpenAiTranscriptionResponse.class);
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "OpenAI transcription returned HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI transcription request failed");
        }
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI returned an empty response");
        }
        Double seconds = response.usageSeconds();
        if (seconds != null && Double.isFinite(seconds) && seconds >= 0) {
            usageTracker.recordUsage(seconds);
        }

        List<Segment> silentSegments = response.segments() == null ? List.of()
                : response.segments().stream().filter(Segment::isNoSpeech).toList();
        List<Word> words = response.words() == null ? List.of()
                : response.words().stream().filter(word -> {
                    // A word can straddle a segment boundary. Assign it by its midpoint.
                    double midpoint = (word.start() + word.end()) / 2;
                    return silentSegments.stream().noneMatch(segment -> segment.contains(midpoint));
                }).toList();
        return new SubtitleResponse(words);
    }
}
