/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;
    private final List<FrameProcessorOption> requestedProcessors = new ArrayList<>();

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean subtitlesRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isSubtitlesRequested() {
        return subtitlesRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }
    
    private void applyOption(String option) {
        switch (option) {
            case "-h", "--help" -> helpRequested = true;
            case "-a", "--audio" -> audioRequested = true;
            case "-S", "--subtitle-frames" -> subtitlesRequested = true;
            case "-x", "--maximise" -> maximiseRequested = true;
            case "-1", "--monitor-1" -> setDisplayId(1);
            case "-2", "--monitor-2" -> setDisplayId(2);
            default -> {
                FrameProcessorOption processor = FrameProcessorOption.fromOption(option);
                if (processor != null) {
                    requestedProcessors.add(processor);
                } else {
                    errorMessage = "Unknown option: " + option;
                }
            }
        }
    }

    public List<FrameProcessor> createFrameProcessors() {
        FrameProcessorOption.resetInstanceCounters();
        return requestedProcessors.stream()
                .map(FrameProcessorOption::create)
                .toList();
    }

    private void parse() {
    	List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                applyOption(arg);
            } else if (arg.startsWith("-") && arg.length() > 1) {
                // Stacked short options such as -jfddn. Each letter is applied in turn.
                for (char letter : arg.substring(1).toCharArray()) {
                    applyOption("-" + letter);
                }
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -S, --subtitle-frames  Add speech-to-text subtitles to frames");
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            System.out.println(option.helpLine());
        }
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Subtitles are an overlay above all frame processors; -S enables them initially.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
