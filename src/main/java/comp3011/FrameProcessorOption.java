package comp3011;

import java.util.function.Supplier;

public enum FrameProcessorOption {
    NUMBER("-n", "--number-frames", "Render the frame number onto each frame", FrameNumberer::new),
    SCRATCH("-s", "--scratch-frames", "Render vertical film scratches", FrameScratcher::new),
    FLICKER("-f", "--flicker-frames", "Randomly dim frames", FrameFlickerer::new),
    BLACK_AND_WHITE("-w", "--black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
    YELLOW("-y", "--yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
    VIGNETTE("-v", "--vignette-frames", "Darken the frame edges", FrameVignetter::new),
    DUST("-d", "--dust-frames", "Render dust and hair marks", FrameDuster::new),
    JITTER("-j", "--jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
    MOTTLE("-m", "--mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
    BLEED("-b", "--bleed-frames", "Bleed light into frames", FrameBleeder::new),
    PEPPER("-p", "--pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

    private final String shortName;
    private final String longName;
    private final String description;
    private final Supplier<? extends FrameProcessor> factory;

    FrameProcessorOption(String shortName, String longName, String description,
            Supplier<? extends FrameProcessor> factory) {
        this.shortName = shortName;
        this.longName = longName;
        this.description = description;
        this.factory = factory;
    }

    public FrameProcessor create() {
        return factory.get();
    }

    public String helpLine() {
        return String.format("  %s, %-19s%s", shortName, longName, description);
    }

    // Accepts either "-n" or "--number-frames". Returns null if there is no match.
    public static FrameProcessorOption fromOption(String option) {
        for (FrameProcessorOption candidate : values()) {
            if (candidate.shortName.equals(option) || candidate.longName.equals(option)) {
                return candidate;
            }
        }
        return null;
    }
}