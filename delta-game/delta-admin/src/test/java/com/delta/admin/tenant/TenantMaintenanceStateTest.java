package com.delta.admin.tenant;

import com.delta.common.maintenance.MaintenanceState;
import com.delta.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantMaintenanceStateTest {
    @AfterEach
    void reset() {
        TenantContext.runAs("tenant-a", () -> new MaintenanceState().setEnabled(true));
        TenantContext.runAs("tenant-b", () -> new MaintenanceState().setEnabled(true));
        TenantContext.clear();
    }

    @Test
    void maintenanceSwitchOfOneTenantDoesNotStopAnotherTenant() throws Exception {
        MaintenanceState state = new MaintenanceState();
        TenantContext.runAs("tenant-a", () -> state.setEnabled(false));

        assertThat(TenantContext.callAs("tenant-a", state::isEnabled)).isFalse();
        assertThat(TenantContext.callAs("tenant-b", state::isEnabled)).isTrue();
        assertThat(TenantContext.currentIdOrNull()).isNull();
    }
}
