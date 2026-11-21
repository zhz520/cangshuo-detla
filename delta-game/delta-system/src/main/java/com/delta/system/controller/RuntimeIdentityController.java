package com.delta.system.controller;

import com.delta.common.domain.R;
import com.delta.system.service.RuntimeBuildIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, non-secret build identity used to compare deployed backend code. */
@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
public class RuntimeIdentityController {
    private final RuntimeBuildIdentity identity;

    @GetMapping("/runtime-identity")
    public R<RuntimeBuildIdentity.Snapshot> identity() {
        return R.ok(identity.current());
    }
}
