package com.delta.common.domain;

import com.delta.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class BatchIds {
    private BatchIds() {}

    public static List<Long> normalize(List<Long> values, int maximum) {
        if (values == null || values.isEmpty()) throw new BusinessException(400, "请选择要处理的数据");
        if (values.size() > maximum) throw new BusinessException(400, "单次最多处理 " + maximum + " 项");
        if (values.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(400, "批量操作包含无效编号");
        }
        var unique = new LinkedHashSet<>(values);
        if (unique.size() != values.size()) throw new BusinessException(400, "批量操作包含重复编号");
        return new ArrayList<>(unique);
    }
}
