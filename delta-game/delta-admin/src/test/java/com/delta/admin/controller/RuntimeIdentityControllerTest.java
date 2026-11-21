package com.delta.admin.controller;

import com.delta.system.controller.RuntimeIdentityController;
import com.delta.system.service.RuntimeBuildIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeIdentityControllerTest {
    @Test
    void exposesOnlyVersionAndNonSecretBuildId() {
        RuntimeBuildIdentity identity = mock(RuntimeBuildIdentity.class);
        when(identity.current()).thenReturn(new RuntimeBuildIdentity.Snapshot("1.18.4", "a".repeat(64)));

        RuntimeBuildIdentity.Snapshot value = new RuntimeIdentityController(identity).identity().getData();

        assertThat(value.version()).isEqualTo("1.18.4");
        assertThat(value.buildId()).isEqualTo("a".repeat(64));
    }
}
