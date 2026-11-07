package com.delta.common.domain;

import java.util.List;

public record BatchResult(int success, int failed, List<Long> failedIds, List<String> messages) {
    public BatchResult {
        failedIds = failedIds == null ? List.of() : List.copyOf(failedIds);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static BatchResult success(int count) {
        return new BatchResult(count, 0, List.of(), List.of());
    }
}
