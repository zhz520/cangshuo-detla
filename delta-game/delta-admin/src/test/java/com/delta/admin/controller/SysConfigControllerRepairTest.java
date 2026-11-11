package com.delta.admin.controller;

import com.delta.system.controller.SysConfigController;
import com.delta.system.entity.SysConfig;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysConfigControllerRepairTest {
    @Test
    void repairsAndPersistsMojibakeMetadataBeforeReturningTheList() {
        SysConfigService service = mock(SysConfigService.class);
        when(service.exists(any())).thenReturn(true);
        SysConfig broken = new SysConfig();
        broken.setId(7L);
        broken.setConfigKey("wechat.official.staff_redirect_url");
        broken.setConfigName("管理员与客服绑定回调页");
        broken.setConfigGroup("å…¬ä¼—å\u008f·é€šçŸ¥");
        broken.setRemark("绑定回调页");
        when(service.list(org.mockito.ArgumentMatchers.
                <com.baomidou.mybatisplus.core.conditions.Wrapper<SysConfig>>any()))
                .thenReturn(new ArrayList<>(List.of(broken)));

        SysConfig returned = new SysConfigController(service).list().getData().get(0);

        assertThat(returned.getConfigGroup()).isEqualTo("公众号通知");
        verify(service).updateById(any(SysConfig.class));
    }
}
