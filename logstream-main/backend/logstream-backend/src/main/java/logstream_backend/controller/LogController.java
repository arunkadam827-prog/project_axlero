package logstream_backend.controller;

import logstream_backend.repository.LogRepository;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogRepository logRepository;

    public LogController(LogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @GetMapping("/count")
    public Map<String, Object> getLogCount() {

        return Map.of(
                "totalLogs",
                logRepository.count()
        );
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {

        return Map.of(
                "totalLogs", logRepository.count(),
                "info", logRepository.countByLevel("INFO"),
                "warning", logRepository.countByLevel("WARNING"),
                "error", logRepository.countByLevel("ERROR")
        );
    }
}