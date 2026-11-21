package com.delta.platform;
import com.delta.common.domain.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequiredArgsConstructor
public class ProjectStatusController {
    private final PlatformRemote remote;
    @GetMapping("/system/maintenance/status")
    public R<Map<String,Boolean>> status(){return R.ok(Map.of("suspended",remote.suspended(),"updating",remote.updating()));}
}
