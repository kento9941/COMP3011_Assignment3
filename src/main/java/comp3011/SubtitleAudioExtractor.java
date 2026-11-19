package comp3011;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE;

/** Extracts original media audio independently of the playback decoder and mute state. */
public final class SubtitleAudioExtractor {
    /**
     * Blocking operation: call from a background task, never the JavaFX thread.
     * The caller owns the returned temporary WAV and must delete it after upload.
     */
    public Path extract(File media) throws Exception {
        Path audio = Files.createTempFile("videoplayer-subtitles-", ".wav");
        boolean completed = false;
        try {
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(media)) {
                grabber.start();
                if (grabber.getAudioChannels() <= 0) {
                    throw new IllegalArgumentException("The selected media has no audio track");
                }
                try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(audio.toFile(), 1)) {
                    recorder.setFormat("wav");
                    recorder.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
                    recorder.setSampleRate(16_000);
                    recorder.start();
                    Frame frame;
                    while ((frame = grabber.grabSamples()) != null) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("Subtitle audio extraction cancelled");
                        }
                        recorder.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
                    }
                }
            }
            completed = true;
            return audio;
        } finally {
            if (!completed) Files.deleteIfExists(audio);
        }
    }
}
