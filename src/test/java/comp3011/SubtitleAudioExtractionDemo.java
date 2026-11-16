package comp3011;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.sampled.AudioSystem;

/** Manual integration check: supply a media file containing audio. */
public class SubtitleAudioExtractionDemo {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply a media file");
        Path audio = new SubtitleAudioExtractor().extract(new File(args[0]));
        try {
            try (var stream = AudioSystem.getAudioInputStream(audio.toFile())) {
                var format = stream.getFormat();
                if (format.getChannels() != 1 || format.getSampleRate() != 16_000
                        || format.getSampleSizeInBits() != 16 || stream.getFrameLength() <= 0) {
                    throw new AssertionError("Expected nonempty mono 16 kHz, 16-bit WAV: " + format);
                }
                System.out.println("Audio extraction passed: " + format);
            }
        } finally {
            Files.deleteIfExists(audio);
        }
    }
}
