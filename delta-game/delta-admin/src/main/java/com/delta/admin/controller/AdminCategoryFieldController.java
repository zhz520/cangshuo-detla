package com.delta.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.product.entity.CategoryFormField;
import com.delta.product.service.CategoryFormFieldService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/category-field")
@RequiredArgsConstructor
public class AdminCategoryFieldController {
    private final CategoryFormFieldService categoryFormFieldService;

    @GetMapping("/list")
    public R<List<CategoryFormField>> list(@RequestParam Long categoryId) {
        return R.ok(categoryFormFieldService.list(
                new LambdaQueryWrapper<CategoryFormField>()
                        .eq(CategoryFormField::getCategoryId, categoryId)
                        .orderByAsc(CategoryFormField::getSortOrder)));
    }

    @OpLog(module = "product", operation = "新增下单字段")
    @PostMapping
    public R<Void> add(@RequestBody CategoryFormField field) {
        String invalid = validate(field);
        if (invalid != null) return R.fail(400, invalid);
        categoryFormFieldService.save(field);
        return R.ok();
    }

    @OpLog(module = "product", operation = "编辑下单字段")
    @PutMapping
    public R<Void> update(@RequestBody CategoryFormField field) {
        String invalid = validate(field);
        if (invalid != null) return R.fail(400, invalid);
        categoryFormFieldService.updateById(field);
        return R.ok();
    }

    private String validate(CategoryFormField field) {
        if (field == null) return "下单字段不能为空";
        if ("SELECT".equalsIgnoreCase(field.getFieldType())) {
            String options = field.getOptions() == null ? "" : field.getOptions().trim();
            if (options.isEmpty() || java.util.Arrays.stream(options.split(",")).noneMatch(value -> !value.trim().isEmpty()))
                return "下拉选择字段至少需要一个选项";
        }
        return null;
    }

    @OpLog(module = "product", operation = "删除下单字段")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        categoryFormFieldService.removeById(id);
        return R.ok();
    }
}
