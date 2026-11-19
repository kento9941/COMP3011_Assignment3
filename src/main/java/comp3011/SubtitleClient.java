package comp3011;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

/** Loads subtitles from the local subtitle server. Run this off the JavaFX thread. */
final class SubtitleClient implements AutoCloseable {
    private static final URI ENDPOINT = URI.create("http://localhost:8080/api/v1/subtitles");
    private final SubtitleAudioExtractor extractor = new SubtitleAudioExtractor();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final JsonMapper json = JsonMapper.builder().build();

    SubtitleTrack load(File media) throws Exception {
        Path audio = extractor.extract(media);
        try {
            return upload(audio);
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    private SubtitleTrack upload(Path audio) throws IOException, InterruptedException {
        String boundary = UUID.randomUUID().toString();
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(BodyPublishers.concat(BodyPublishers.ofString(header),
                        BodyPublishers.ofFile(audio),
                        BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Subtitle server returned HTTP " + response.statusCode());
        }

        Response result = json.readValue(response.body(), Response.class);
        if (result == null || result.words() == null) {
            throw new IOException("Subtitle response is missing words");
        }
        List<SubtitleWord> words = new ArrayList<>();
        for (Word word : result.words()) {
            if (word == null || word.start() == null || word.end() == null
                    || !Double.isFinite(word.start()) || !Double.isFinite(word.end())
                    || word.start() < 0 || word.end() < word.start()
                    || word.end() >= Long.MAX_VALUE / 1_000_000.0) {
                throw new IOException("Subtitle response contains an invalid timestamp");
            }
            long startUs = Math.round(word.start() * 1_000_000);
            long endUs = Math.round(word.end() * 1_000_000);
            words.add(new SubtitleWord(startUs, Math.max(startUs + 1, endUs), word.word()));
        }
        return SubtitleTrack.fromWords(words);
    }

    private record Response(List<Word> words) {}

    private record Word(Double start, Double end, String word) {}

    @Override
    public void close() {
        http.close();
    }
}
