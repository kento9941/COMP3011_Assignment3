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
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Playback runs as a multi-threaded pipeline.
 * </p>
 * <ol>
 * <li>One decode thread owns the FFmpeg grabber. It reads frames in order, queues
 * audio, copies each image frame and tags it with a sequence number.</li>
 * <li>A pool of worker threads takes decoded frames in any order, applies the full
 * frame processor chain and converts the result to a JavaFX Image. Each worker owns
 * its own set of frame processor objects, because some processors keep working data
 * in fields and are not safe to share between threads. Frame processors are
 * deterministic per frame number, so which worker handles which frame does not
 * change the output.</li>
 * <li>The JavaFX thread reorders finished frames by sequence number, starts the
 * playback clock once a few frames are buffered, and shows each frame when it is due.
 * It never decodes or processes frames itself, so the UI stays responsive.</li>
 * </ol>
 * <p>
 * A semaphore bounds the number of frames in flight so memory use stays fixed. Seeking,
 * stopping and shutdown stop and join every pipeline thread before the grabber is
 * touched. A generation number on each frame guards against any frame from before a
 * seek ever being shown after it.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    // Leave a couple of cores for the decode thread, the JavaFX thread and the OS. Capped to bound memory use.
    private static final int WORKER_COUNT = 1;
    // Upper bound on frames that have been decoded but not yet shown.
    private static final int MAX_FRAMES_IN_FLIGHT = WORKER_COUNT + 8;
    // Frames to have ready before the playback clock starts, so playback begins with slack.
    private static final int PREBUFFER_FRAMES = WORKER_COUNT;

    // One independent processor chain per worker. A chain is only ever used by its own worker thread.
    private final List<List<FrameProcessor>> workerProcessors = new ArrayList<>();
    private final boolean[] workerProcessorsInitialised = new boolean[WORKER_COUNT];
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BlockingQueue<DecodedFrame> decodedFrames = new LinkedBlockingQueue<>();
    private final PriorityBlockingQueue<PreparedFrame> readyFrames = new PriorityBlockingQueue<>(
            MAX_FRAMES_IN_FLIGHT,
            Comparator.comparingInt(PreparedFrame::generation).thenComparingLong(PreparedFrame::sequence));
    private final Semaphore framesInFlight = new Semaphore(MAX_FRAMES_IN_FLIGHT);
    private final List<Thread> pipelineThreads = new ArrayList<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // The JavaFX heart beat. Each pulse it writes due audio and shows the next frame if it is due.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // Used only by the decode thread while the pipeline runs.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.

    // Shared between threads.
    private volatile boolean decoding;
    private volatile boolean decodeFinished;
    private volatile int generation;
    private volatile boolean playbackOpen;
    private volatile boolean pauseRequested;
    private volatile boolean audioOutputEnabled;
    private volatile long firstTimestampUs = NO_SEEK_REQUEST;
    private volatile long playbackStartNs;

    // JavaFX thread only, or set before the pipeline threads start.
    private long nextSequenceToShow;
    private boolean showOneFrameWhilePaused;
    private boolean audioAvailable;
    private InfoVideo videoInfo; // decode thread only, built from the first image frame
    private long pauseStartedNs;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long logicalPlaybackBaseUs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            Supplier<List<FrameProcessor>> frameProcessorFactory,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        for (int i = 0; i < WORKER_COUNT; i++) {
            workerProcessors.add(List.copyOf(frameProcessorFactory.get()));
        }
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            stopPipeline(); // every pipeline thread has stopped before the grabber is moved
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            showOneFrameWhilePaused = pauseRequested;
            startPipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            showOneFrameWhilePaused = initiallyPaused;
            playbackOpen = true;
            playbackTimer.start();
            startPipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0; // the JavaFX thread starts the clock once frames are buffered
        pauseStartedNs = 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    // ---------------------------------------------------------------------------------------------
    // Pipeline thread management (called on the JavaFX thread)
    // ---------------------------------------------------------------------------------------------

    private void startPipeline() {
        int pipelineGeneration = generation + 1;
        generation = pipelineGeneration;
        nextSequenceToShow = 0;
        decodeFinished = false;
        decoding = true;

        pipelineThreads.add(new Thread(() -> decodeLoop(pipelineGeneration), "decode"));
        for (int i = 0; i < WORKER_COUNT; i++) {
            int workerIndex = i;
            pipelineThreads.add(new Thread(() -> workerLoop(workerIndex), "frame-worker-" + i));
        }
        for (Thread thread : pipelineThreads) {
            thread.setDaemon(true);
            thread.start();
        }
    }

    // Stops and joins every pipeline thread, then discards any frames still queued.
    private void stopPipeline() {
        decoding = false;
        for (Thread thread : pipelineThreads) {
            thread.interrupt();
        }
        for (Thread thread : pipelineThreads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pipelineThreads.clear();
        decodedFrames.clear();
        readyFrames.clear();
        framesInFlight.drainPermits();
        framesInFlight.release(MAX_FRAMES_IN_FLIGHT);
    }

    // ---------------------------------------------------------------------------------------------
    // Decode thread
    // ---------------------------------------------------------------------------------------------

    private void decodeLoop(int pipelineGeneration) {
        long sequence = 0;
        try {
            while (decoding) {
                framesInFlight.acquire(); // blocks when enough frames are already in flight
                DecodedFrame decoded = readNextDecodedFrame(sequence, pipelineGeneration);
                if (decoded == null) {
                    framesInFlight.release();
                    return;
                }
                decodedFrames.put(decoded);
                sequence++;
            }
        } catch (InterruptedException e) {
            // The pipeline is being stopped.
        } catch (Exception e) {
            Platform.runLater(() -> handlePlaybackError(e));
        }
    }

    private DecodedFrame readNextDecodedFrame(long sequence, int pipelineGeneration) throws Exception {
        while (decoding) {
            Frame frame = grabFrame();
            if (frame == null) {
                decodeFinished = true;
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            // Built once from the first image frame and passed to the workers with every frame, so each worker can
            // initialise its own processor chain.
            if (videoInfo == null) {
                videoInfo = new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat());
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
            }

            int frameNumber = grabber.getFrameNumber();
            long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;

            // The grabber reuses its Frame buffer on the next grab, so each worker gets its own copy.
            return new DecodedFrame(
                    sequence,
                    pipelineGeneration,
                    frame.clone(),
                    videoInfo,
                    new InfoFrame(frameNumber, timestampUs),
                    logicalTimestampUs,
                    relativeTimestampUs);
        }

        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Worker threads
    // ---------------------------------------------------------------------------------------------

    private void workerLoop(int workerIndex) {
        List<FrameProcessor> processors = workerProcessors.get(workerIndex);
        JavaFXFrameConverter workerConverter = new JavaFXFrameConverter();
        try {
            while (decoding) {
                DecodedFrame decoded = decodedFrames.take();
                if (!workerProcessorsInitialised[workerIndex]) {
                    for (FrameProcessor processor : processors) {
                        processor.initialise(decoded.videoInfo());
                    }
                    workerProcessorsInitialised[workerIndex] = true;
                }
                for (FrameProcessor processor : processors) {
                    processor.process(decoded.frame(), decoded.info());
                }
                Image image = workerConverter.convert(decoded.frame());
                readyFrames.put(new PreparedFrame(
                        decoded.sequence(),
                        decoded.generation(),
                        image,
                        decoded.info().frameNumber(),
                        decoded.info().timestampUs(),
                        decoded.logicalTimestampUs(),
                        decoded.relativeTimestampUs(),
                        System.nanoTime()));
            }
        } catch (InterruptedException e) {
            // The pipeline is being stopped.
        } catch (Exception e) {
            Platform.runLater(() -> handlePlaybackError(e));
        } finally {
            workerConverter.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // JavaFX thread
    // ---------------------------------------------------------------------------------------------

    // Called on every JavaFX pulse. Only light work happens here. Frames are shown strictly in sequence
    // order, however the workers happen to finish them.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        discardStaleFrames();

        if (decodeFinished && readyFrames.isEmpty()
                && framesInFlight.availablePermits() == MAX_FRAMES_IN_FLIGHT) {
            finishPlayback();
            return;
        }

        if (playbackStartNs == 0 && !startClockWhenBuffered(now)) {
            return;
        }

        writeDueAudio(now);

        PreparedFrame next = readyFrames.peek();
        if (next != null
                && next.sequence() == nextSequenceToShow
                && dueTimeNs(next) <= now
                && (!pauseRequested || showOneFrameWhilePaused)) {
            readyFrames.poll();
            nextSequenceToShow++;
            framesInFlight.release();
            showOneFrameWhilePaused = false;
            displayPreparedFrame(next);
        }
    }

    // Starts the playback clock once the first frame and a few after it are ready.
    private boolean startClockWhenBuffered(long now) {
        PreparedFrame first = readyFrames.peek();
        boolean firstReady = first != null && first.sequence() == nextSequenceToShow;
        boolean enoughBuffered = readyFrames.size() >= PREBUFFER_FRAMES || decodeFinished;
        if (!firstReady || !enoughBuffered) {
            return false;
        }

        playbackStartNs = now - first.relativeTimestampUs() * 1_000L;
        if (pauseRequested) {
            pauseStartedNs = now;
        }
        return true;
    }

    // Safety net. Every thread is joined before a new generation starts, so this should never find anything.
    private void discardStaleFrames() {
        PreparedFrame head = readyFrames.peek();
        while (head != null && head.generation() != generation) {
            readyFrames.poll();
            head = readyFrames.peek();
        }
    }

    private long dueTimeNs(PreparedFrame frame) {
        return playbackStartNs + frame.relativeTimestampUs() * 1_000L;
    }

    private void displayPreparedFrame(PreparedFrame frame) {
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (dueTimeNs(frame) - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        stopPipeline(); // must come first, so no thread is inside the grabber when it closes
        playbackTimer.stop();
        playbackOpen = false;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        videoInfo = null;
        // Safe to reset here because every worker has been joined by stopPipeline above.
        java.util.Arrays.fill(workerProcessorsInitialised, false);

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    // A decoded frame waiting for a worker. The Frame is a private copy owned by whichever worker takes it.
    private record DecodedFrame(
            long sequence,
            int generation,
            Frame frame,
            InfoVideo videoInfo,
            InfoFrame info,
            long logicalTimestampUs,
            long relativeTimestampUs) {
    }

    // A fully processed frame waiting to be shown, ordered by generation then sequence.
    private record PreparedFrame(
            long sequence,
            int generation,
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long relativeTimestampUs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}