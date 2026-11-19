package comp3011.subtitles.controller;

import comp3011.subtitles.dto.SubtitleResponse;
import comp3011.subtitles.service.TranscriptionService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("stub")
public class SubtitleController {
    private final TranscriptionService transcriptionService;

    public SubtitleController(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @PostMapping(path = "/api/v1/subtitles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SubtitleResponse subtitles(@RequestParam("audio") MultipartFile audio) {
        if (audio.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio is empty");
        }
        return transcriptionService.transcribe(audio);
    }
}
