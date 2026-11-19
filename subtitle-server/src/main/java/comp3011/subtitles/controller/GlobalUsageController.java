package comp3011.subtitles.controller;

import java.util.Map;

import comp3011.subtitles.service.GlobalUsageTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GlobalUsageController {
    private final GlobalUsageTracker usageTracker;

    public GlobalUsageController(GlobalUsageTracker usageTracker) {
        this.usageTracker = usageTracker;
    }

    @GetMapping("/api/v1/global/usage")
    public Map<String, Double> usage() {
        return Map.of("usage", usageTracker.getUsageSeconds());
    }
}
