package comp3011.subtitles.service;

import java.util.List;

import comp3011.subtitles.dto.SubtitleResponse;
import comp3011.subtitles.dto.SubtitleResponse.Word;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("stub")
public class StubTranscriptionService implements TranscriptionService {
    private static final SubtitleResponse SAMPLE = new SubtitleResponse(List.of(
            new Word(0.0, 0.5, "Sample"),
            new Word(0.5, 1.2, "subtitles.")));

    @Override
    public SubtitleResponse transcribe(MultipartFile audio) {
        return SAMPLE;
    }
}
