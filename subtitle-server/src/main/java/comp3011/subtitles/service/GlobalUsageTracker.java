package comp3011.subtitles.service;

import org.springframework.stereotype.Component;

@Component
public class GlobalUsageTracker {
    private double totalSeconds;

    public synchronized void recordUsage(double seconds) {
        totalSeconds += seconds;
    }

    public synchronized double getUsageSeconds() {
        return totalSeconds;
    }
}
