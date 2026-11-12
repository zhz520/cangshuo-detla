package com.delta.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.delta.common.exception.BusinessException;
import com.delta.system.controller.SensitiveWordController;
import com.delta.system.entity.SensitiveWord;
import com.delta.system.service.SensitiveWordService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SensitiveWordControllerTest {
    @Test
    void addNormalizesWhitespaceAndCategory() {
        SensitiveWordService service = mock(SensitiveWordService.class);
        when(service.count(any(Wrapper.class))).thenReturn(0L);
        SensitiveWordController controller = new SensitiveWordController(service);
        SensitiveWord input = new SensitiveWord();
        input.setWord("  外挂  ");
        input.setCategory(" ad ");

        assertThat(controller.add(input).getCode()).isEqualTo(200);
        ArgumentCaptor<SensitiveWord> captor = ArgumentCaptor.forClass(SensitiveWord.class);
        verify(service).save(captor.capture());
        assertThat(captor.getValue().getWord()).isEqualTo("外挂");
        assertThat(captor.getValue().getCategory()).isEqualTo("AD");
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
    }

    @Test
    void invalidOrDuplicateWordIsRejectedBeforeWriting() {
        SensitiveWordService service = mock(SensitiveWordService.class);
        SensitiveWordController controller = new SensitiveWordController(service);
        SensitiveWord empty = new SensitiveWord();
        empty.setWord("   ");
        assertThatThrownBy(() -> controller.add(empty))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));

        SensitiveWord invalidStatus = new SensitiveWord();
        invalidStatus.setWord("广告");
        invalidStatus.setStatus(2);
        assertThatThrownBy(() -> controller.add(invalidStatus))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));

        when(service.count(any(Wrapper.class))).thenReturn(1L);
        SensitiveWord duplicate = new SensitiveWord();
        duplicate.setWord("外挂");
        assertThatThrownBy(() -> controller.add(duplicate))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        verify(service, never()).save(any());
    }

    @Test
    void updateAndDeleteDistinguishMissingRows() {
        SensitiveWordService service = mock(SensitiveWordService.class);
        SensitiveWordController controller = new SensitiveWordController(service);
        SensitiveWord update = new SensitiveWord();
        update.setId(9L);
        update.setWord("导流");
        when(service.count(any(Wrapper.class))).thenReturn(0L);
        when(service.getById(9L)).thenReturn(null);
        assertThatThrownBy(() -> controller.update(update))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));

        when(service.removeById(8L)).thenReturn(false);
        assertThatThrownBy(() -> controller.delete(8L))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));
    }
}
