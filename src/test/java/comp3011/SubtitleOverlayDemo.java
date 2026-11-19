package comp3011;

import java.io.File;
import java.util.List;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Optional manual playback check. Sample captions do not transcribe the supplied video. */
public class SubtitleOverlayDemo {
    public static void main(String[] args) {
        Application.launch(Demo.class, args);
    }

    public static class Demo extends Application {
        private VideoPlayerController controller;

        @Override
        public void start(Stage stage) {
            if (getParameters().getRaw().size() != 1) {
                throw new IllegalArgumentException("Supply a video file for the subtitle overlay demo");
            }
            controller = new VideoPlayerController(true, true,
                    () -> List.of(new FrameBlackAndWhiter()), (_, _) -> {});
            Scene scene = new Scene(controller.getView().getRoot(), 960, 640);
            controller.installKeyboardShortcuts(scene);
            stage.setScene(scene);
            stage.setTitle("Subtitle overlay check — sample text");
            stage.show();
            controller.play(new File(getParameters().getRaw().getFirst()));
            controller.setSubtitleTrack(new SubtitleTrack(List.of(
                    new SubtitleCue(0, 3_000_000, "Sample subtitles above the video effects."),
                    new SubtitleCue(3_000_000, 7_000_000, "Pause, then press S to hide this text.\nPress S again to bring it back."),
                    new SubtitleCue(8_000_000, 12_000_000, "Seek backward to check subtitle timing."))));
        }

        @Override
        public void stop() {
            if (controller != null) controller.shutdown();
        }
    }
}
