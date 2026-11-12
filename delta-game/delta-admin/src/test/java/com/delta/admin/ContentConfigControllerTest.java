package com.delta.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.delta.common.exception.BusinessException;
import com.delta.system.controller.ContentConfigController;
import com.delta.system.entity.ContentConfig;
import com.delta.system.service.ContentConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContentConfigControllerTest {
    @Test
    void addNormalizesKeyAndStoresRichText() {
        ContentConfigService service = mock(ContentConfigService.class);
        when(service.count(any(Wrapper.class))).thenReturn(0L);
        ContentConfigController controller = new ContentConfigController(service);
        ContentConfig input = new ContentConfig();
        input.setConfigKey("  Privacy_Policy  ");
        input.setConfigValue("  <h1>隐私政策</h1>  ");
        input.setRemark(" 隐私政策 ");

        assertThat(controller.add(input).getCode()).isEqualTo(200);
        ArgumentCaptor<ContentConfig> captor = ArgumentCaptor.forClass(ContentConfig.class);
        verify(service).save(captor.capture());
        assertThat(captor.getValue().getConfigKey()).isEqualTo("privacy_policy");
        assertThat(captor.getValue().getConfigValue()).isEqualTo("<h1>隐私政策</h1>");
        assertThat(captor.getValue().getRemark()).isEqualTo("隐私政策");
    }

    @Test
    void addRejectsInvalidEmptyAndDuplicateKeys() {
        ContentConfigService service = mock(ContentConfigService.class);
        ContentConfigController controller = new ContentConfigController(service);
        ContentConfig invalid = new ContentConfig();
        invalid.setConfigKey("隐私政策");
        invalid.setConfigValue("内容");
        assertThatThrownBy(() -> controller.add(invalid))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));

        ContentConfig empty = new ContentConfig();
        empty.setConfigKey("privacy_policy");
        empty.setConfigValue("  ");
        assertThatThrownBy(() -> controller.add(empty))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));

        when(service.count(any(Wrapper.class))).thenReturn(1L);
        ContentConfig duplicate = new ContentConfig();
        duplicate.setConfigKey("privacy_policy");
        duplicate.setConfigValue("内容");
        assertThatThrownBy(() -> controller.add(duplicate))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        verify(service, never()).save(any());
    }

    @Test
    void updateKeepsTheStableKeyAndMissingRowsReturnNotFound() {
        ContentConfigService service = mock(ContentConfigService.class);
        ContentConfigController controller = new ContentConfigController(service);
        ContentConfig existing = new ContentConfig();
        existing.setId(1L);
        existing.setConfigKey("privacy_policy");
        existing.setConfigValue("old");
        when(service.getById(1L)).thenReturn(existing);

        ContentConfig changed = new ContentConfig();
        changed.setId(1L);
        changed.setConfigKey("other_key");
        changed.setConfigValue("new");
        assertThatThrownBy(() -> controller.update(changed))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));

        ContentConfig valid = new ContentConfig();
        valid.setId(1L);
        valid.setConfigKey("privacy_policy");
        valid.setConfigValue("new");
        valid.setRemark("隐私政策");
        assertThat(controller.update(valid).getCode()).isEqualTo(200);
        verify(service).updateById(argThat(item -> item.getId().equals(1L) && item.getCreatedAt() == null));

        when(service.removeById(8L)).thenReturn(false);
        assertThatThrownBy(() -> controller.delete(8L))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));
    }
}
