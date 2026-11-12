package com.delta.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.system.entity.SensitiveWord;
import com.delta.system.service.SensitiveWordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/system/sensitive-word")
@RequiredArgsConstructor
public class SensitiveWordController {
    private static final Set<String> CATEGORIES = Set.of("DIRTY", "AD", "POLITICAL", "OTHER");
    private final SensitiveWordService sensitiveWordService;

    @GetMapping("/list")
    public R<Page<SensitiveWord>> list(PageQuery query,
                                       @RequestParam(value = "category", required = false) String category,
                                       @RequestParam(value = "keyword", required = false) String keyword) {
        String word = clean(keyword);
        if (word.length() > 64) throw new BusinessException(400, "搜索内容不能超过 64 个字符");
        LambdaQueryWrapper<SensitiveWord> wrapper = new LambdaQueryWrapper<SensitiveWord>()
                .eq(category != null && !category.isBlank(), SensitiveWord::getCategory, category)
                .like(!word.isEmpty(), SensitiveWord::getWord, word)
                .orderByDesc(SensitiveWord::getCreatedAt);
        return R.ok(sensitiveWordService.page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper));
    }

    @OpLog(module = "system", operation = "新增敏感词")
    @PostMapping
    public R<Void> add(@RequestBody SensitiveWord word) {
        sensitiveWordService.save(normalize(word, false));
        return R.ok();
    }

    @OpLog(module = "system", operation = "编辑敏感词")
    @PutMapping
    public R<Void> update(@RequestBody SensitiveWord word) {
        SensitiveWord normalized = normalize(word, true);
        if (sensitiveWordService.getById(normalized.getId()) == null)
            throw new BusinessException(404, "敏感词不存在");
        normalized.setCreatedAt(null);
        sensitiveWordService.updateById(normalized);
        return R.ok();
    }

    @OpLog(module = "system", operation = "删除敏感词")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        if (!sensitiveWordService.removeById(id)) throw new BusinessException(404, "敏感词不存在");
        return R.ok();
    }

    private SensitiveWord normalize(SensitiveWord source, boolean requireId) {
        if (source == null) throw new BusinessException(400, "敏感词内容不能为空");
        if (requireId && (source.getId() == null || source.getId() <= 0)) throw new BusinessException(400, "敏感词 ID 无效");
        String word = clean(source.getWord());
        if (word.isEmpty()) throw new BusinessException(400, "敏感词内容不能为空");
        if (word.length() > 64) throw new BusinessException(400, "敏感词不能超过 64 个字符");
        String category = clean(source.getCategory()).toUpperCase(Locale.ROOT);
        if (category.isEmpty()) category = "OTHER";
        if (!CATEGORIES.contains(category)) throw new BusinessException(400, "敏感词分类无效");
        int status = source.getStatus() == null ? 1 : source.getStatus();
        if (status != 0 && status != 1) throw new BusinessException(400, "敏感词状态无效");
        LambdaQueryWrapper<SensitiveWord> duplicate = new LambdaQueryWrapper<SensitiveWord>()
                .eq(SensitiveWord::getWord, word)
                .ne(requireId, SensitiveWord::getId, source.getId());
        if (sensitiveWordService.count(duplicate) > 0) throw new BusinessException(409, "该敏感词已存在");
        SensitiveWord normalized = new SensitiveWord();
        normalized.setId(source.getId());
        normalized.setWord(word);
        normalized.setCategory(category);
        normalized.setStatus(status);
        return normalized;
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }
}
