package dev.cgt.pixelplace.overview.web;

import dev.cgt.pixelplace.overview.application.OverviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/*
 * 마지막 정상 Overview PNG를 제공하는 read-only HTTP 진입점
 * global not-ready는 기존 interceptor, ready 상태의 no-image는 endpoint-local 503으로 구분
 */
@RestController
@RequestMapping("/api/overview")
public class OverviewController {

    public static final String IMAGE_NOT_AVAILABLE_MESSAGE = "Overview image is not available.";

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = Objects.requireNonNull(
                overviewService,
                "overviewService must not be null"
        );
    }

    /* 정상 PNG만 200/image/png로 제공하고 최초 생성 전에는 명시적 JSON 503 반환 */
    @GetMapping
    public ResponseEntity<?> getOverview() {
        return overviewService.currentPng()
                .<ResponseEntity<?>>map(png -> ResponseEntity
                        .ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.noCache())
                        .body(png))
                .orElseGet(() -> ResponseEntity
                        .status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("message", IMAGE_NOT_AVAILABLE_MESSAGE)));
    }
}
