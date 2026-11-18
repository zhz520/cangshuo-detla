package com.delta.admin;

import com.delta.admin.controller.AdminPlayerController;
import com.delta.admin.controller.AdminUserController;
import com.delta.admin.service.UserManagementService;
import com.delta.common.exception.BusinessException;
import com.delta.order.service.OrderService;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.Player;
import com.delta.player.service.PlayerGovernanceService;
import com.delta.player.service.PlayerLevelService;
import com.delta.player.service.PlayerService;
import com.delta.player.service.PlayerWalletService;
import com.delta.system.service.FeatureAuditService;
import com.delta.user.service.UserService;
import com.delta.user.service.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.context.ApplicationEventPublisher;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminBatchControllerTest {
    @BeforeAll
    static void initLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Player.class);
    }
    @Test
    void playerBatchActionsRejectInvalidSourceStatuses() {
        PlayerService players = mock(PlayerService.class);
        Player active = new Player(); active.setId(1L); active.setStatus("ACTIVE");
        Player pending = new Player(); pending.setId(2L); pending.setStatus("PENDING");
        when(players.getById(1L)).thenReturn(active);
        when(players.getById(2L)).thenReturn(pending);
        var controller = playerController(players);

        var rejected = controller.batchReject(new AdminPlayerController.BatchRejectRequest(List.of(1L), "资料不完整")).getData();
        var unfrozen = controller.batchUnfreeze(new AdminPlayerController.BatchIdsRequest(List.of(2L))).getData();

        assertThat(rejected.success()).isZero();
        assertThat(rejected.failedIds()).containsExactly(1L);
        assertThat(unfrozen.success()).isZero();
        assertThat(unfrozen.failedIds()).containsExactly(2L);
        verify(players, never()).updateById(any(Player.class));
    }

    @Test
    void playerBatchApprovalReportsPerItemFailures() {
        PlayerService players = mock(PlayerService.class);
        PlayerGovernanceService governance = mock(PlayerGovernanceService.class);
        doThrow(new BusinessException(409, "媒体资料未通过")).when(governance).checkApplication(1L);
        when(players.update(any())).thenReturn(true);
        when(players.update(any(), any())).thenReturn(true);
        var controller = playerController(players, governance);

        var result = controller.batchApprove(new AdminPlayerController.BatchIdsRequest(List.of(1L, 2L))).getData();

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedIds()).containsExactly(1L);
        assertThat(result.messages().get(0)).contains("媒体资料未通过");
        verify(players).update(any());
        verify(players, never()).updateById(any(Player.class));
    }

    @Test
    void userBatchStatusKeepsGoingAndReturnsFailures() {
        UserService users = mock(UserService.class);
        WalletService wallets = mock(WalletService.class);
        OrderService orders = mock(OrderService.class);
        TransactionService transactions = mock(TransactionService.class);
        UserManagementService management = mock(UserManagementService.class);
        doThrow(new BusinessException(404, "用户不存在")).when(management).setStatus(1L, 0);
        var controller = new AdminUserController(users, wallets, orders, transactions, management);

        var result = controller.batchStatus(new AdminUserController.BatchStatusRequest(List.of(1L, 2L), 0)).getData();

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedIds()).containsExactly(1L);
        verify(management).setStatus(2L, 0);
    }

    private AdminPlayerController playerController(PlayerService players) {
        return playerController(players, mock(PlayerGovernanceService.class));
    }

    private AdminPlayerController playerController(PlayerService players, PlayerGovernanceService governance) {
        return new AdminPlayerController(players, governance, mock(PlayerLevelService.class),
                mock(PlayerWalletService.class), mock(OrderService.class), mock(TransactionService.class),
                mock(ApplicationEventPublisher.class), mock(FeatureAuditService.class),
                mock(com.delta.player.service.PlayerDepositService.class));
    }
}
