package com.delta.workbench;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/workbench")
@PreAuthorize("hasAnyRole('ADMIN','CS','PLAYER','USER')")
@RequiredArgsConstructor
public class WorkbenchController {
    private final WorkbenchService workbench;

    @GetMapping({"/summary", "/badges"})
    public R<WorkbenchService.Summary> summary(HttpServletRequest request) {
        return R.ok(workbench.summary(scope(request)));
    }

    @GetMapping("/tasks")
    public R<WorkbenchService.TaskPage> tasks(HttpServletRequest request,
            @RequestParam(defaultValue = "") String kind,
            @RequestParam(defaultValue = "ALL") WorkbenchService.Filter filter,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(workbench.tasks(scope(request), kind, filter, page, size));
    }

    @PostMapping("/read")
    public R<Void> read(HttpServletRequest request, @RequestBody List<WorkbenchService.TaskRef> tasks) {
        workbench.read(scope(request), tasks, false);
        return R.ok();
    }

    @PostMapping("/read-all")
    public R<Void> readAll(HttpServletRequest request) {
        workbench.readAll(scope(request));
        return R.ok();
    }

    @PostMapping("/acknowledge")
    public R<Void> acknowledge(HttpServletRequest request, @RequestBody WorkbenchService.TaskRef task) {
        workbench.read(scope(request), List.of(task), true);
        return R.ok();
    }

    private WorkbenchService.Scope scope(HttpServletRequest request) {
        return workbench.scope(SecurityUtils.getUserType(), SecurityUtils.getUserId(), request.getServerName());
    }
}
