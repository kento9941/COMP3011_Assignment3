package comp3011.subtitles.service;

import comp3011.subtitles.dto.SubtitleResponse;
import org.springframework.web.multipart.MultipartFile;

public interface TranscriptionService {
    SubtitleResponse transcribe(MultipartFile audio);
}
