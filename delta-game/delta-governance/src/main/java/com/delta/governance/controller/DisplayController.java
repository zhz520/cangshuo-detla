package com.delta.governance.controller;

import com.delta.common.domain.R;
import com.delta.governance.service.DisplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/app/display")
@RequiredArgsConstructor
public class DisplayController {
    private final DisplayService display;

    @GetMapping("/spend-ranking")
    public R<Map<String, Object>> spending(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(display.spending(limit));
    }

    @GetMapping("/purchase-feed")
    public R<Map<String, Object>> feed(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(display.feed(limit));
    }
}
