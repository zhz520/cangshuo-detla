package com.delta.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.system.entity.ContentConfig;
import com.delta.system.service.ContentConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/system/content")
@RequiredArgsConstructor
public class ContentConfigController {
    private final ContentConfigService contentConfigService;

    @GetMapping("/list")
    public R<List<ContentConfig>> list() {
        return R.ok(contentConfigService.list());
    }

    @GetMapping("/key/{key}")
    public R<ContentConfig> getByKey(@PathVariable String key) {
        return R.ok(contentConfigService.getOne(
                new LambdaQueryWrapper<ContentConfig>().eq(ContentConfig::getConfigKey, key)));
    }

    @OpLog(module = "system", operation = "新增内容配置")
    @PostMapping
    public R<Void> add(@RequestBody ContentConfig config) {
        ContentConfig normalized = normalize(config, false);
        long duplicate = contentConfigService.count(new LambdaQueryWrapper<ContentConfig>()
                .eq(ContentConfig::getConfigKey, normalized.getConfigKey()));
        if (duplicate > 0) throw new BusinessException(409, "该内容键已存在");
        contentConfigService.save(normalized);
        return R.ok();
    }

    @OpLog(module = "system", operation = "编辑内容配置")
    @PutMapping
    public R<Void> update(@RequestBody ContentConfig config) {
        if (config == null || config.getId() == null || config.getId() <= 0)
            throw new BusinessException(400, "内容配置 ID 无效");
        ContentConfig existing = contentConfigService.getById(config.getId());
        if (existing == null) throw new BusinessException(404, "内容配置不存在");
        ContentConfig normalized = normalize(config, true);
        if (!existing.getConfigKey().equals(normalized.getConfigKey()))
            throw new BusinessException(400, "内容键创建后不能修改");
        normalized.setCreatedAt(null);
        contentConfigService.updateById(normalized);
        return R.ok();
    }

    @OpLog(module = "system", operation = "删除内容配置")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        if (!contentConfigService.removeById(id)) throw new BusinessException(404, "内容配置不存在");
        return R.ok();
    }

    private ContentConfig normalize(ContentConfig source, boolean editing) {
        if (source == null) throw new BusinessException(400, "内容配置不能为空");
        String key = source.getConfigKey() == null ? "" : source.getConfigKey().trim().toLowerCase(java.util.Locale.ROOT);
        if (!key.matches("[a-z][a-z0-9_-]{0,127}"))
            throw new BusinessException(400, "内容键须以小写字母开头，仅包含小写字母、数字、下划线或短横线");
        String value = source.getConfigValue() == null ? "" : source.getConfigValue().trim();
        if (value.isEmpty()) throw new BusinessException(400, "页面内容不能为空");
        if (value.length() > 1000000) throw new BusinessException(400, "页面内容不能超过 100 万个字符");
        String remark = source.getRemark() == null ? "" : source.getRemark().trim();
        if (remark.length() > 255) throw new BusinessException(400, "备注不能超过 255 个字符");
        ContentConfig normalized = new ContentConfig();
        normalized.setId(editing ? source.getId() : null);
        normalized.setConfigKey(key);
        normalized.setConfigValue(value);
        normalized.setRemark(remark);
        return normalized;
    }
}
