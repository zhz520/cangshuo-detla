package com.delta.player.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.delta.player.entity.Player;
import com.delta.player.mapper.PlayerMapper;
import com.delta.player.service.PlayerService;
import com.delta.player.service.PlayerWalletService;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PlayerServiceImpl extends ServiceImpl<PlayerMapper, Player> implements PlayerService {
    private final PlayerWalletService playerWalletService;
    private final CrossModuleMapper crossModuleMapper;
    private final JdbcTemplate jdbc;

    @Override
    public Player getByOpenid(String openid) {
        return getOne(new LambdaQueryWrapper<Player>().eq(Player::getOpenid, openid));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Player loginOrRegister(String openid, String nickname, String avatar) {
        Player player = getByOpenid(openid);
        if (player == null) {
            player = new Player();
            player.setOpenid(openid);
            player.setNickname(nickname);
            player.setAvatar(avatar);
            player.setStatus("PENDING");
            save(player);
            playerWalletService.initWallet(player.getId());
        }
        return player;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSafely(Long id) {
        Player player = getOne(new LambdaQueryWrapper<Player>()
                .eq(Player::getId, id)
                .last("FOR UPDATE"));
        if (player == null) throw new BusinessException(404, "打手不存在");
        if (crossModuleMapper.countActivePlayerOrders(id) > 0) {
            throw new BusinessException(409, "打手仍有进行中订单或队友任务，不能删除");
        }
        var wallet = playerWalletService.getByPlayerId(id);
        if (wallet != null && (nonzero(wallet.getBalance()) || nonzero(wallet.getPendingIncome()) || nonzero(wallet.getFrozenAmount()))) {
            throw new BusinessException(409, "打手仍有余额、待结算收益或冻结金额，请先处理资金");
        }
        Long pendingWithdrawals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM withdraw WHERE player_id=? AND status NOT IN ('COMPLETED','REJECTED','CANCELLED')",
                Long.class, id);
        if (pendingWithdrawals != null && pendingWithdrawals > 0) {
            throw new BusinessException(409, "打手仍有未完成提现，不能删除");
        }
        if (crossModuleMapper.selectPendingComplaintCount(id) > 0) {
            throw new BusinessException(409, "打手仍有未处理投诉，不能删除");
        }
        Long pendingRewards = jdbc.queryForObject(
                "SELECT COUNT(*) FROM player_reward WHERE status='REVERSAL_PENDING' AND player_id=?",
                Long.class, id);
        if (pendingRewards != null && pendingRewards > 0) {
            throw new BusinessException(409, "打手仍有待追回打赏，请先完成冲正");
        }

        Player update = new Player();
        update.setId(id);
        update.setStatus("FROZEN");
        update.setIsOnline(0);
        updateById(update);
        removeById(id);
        // Preserve player_id-based order and financial history, but release login identity.
        jdbc.update("UPDATE player SET openid=CONCAT('closed:',id),phone='',nickname='已注销打手',real_name='',avatar='',cover_image='',album_images='',story='',voice_url='',introduction='',proof_images='',status='FROZEN',is_online=0,deleted=1 WHERE id=?", id);
    }

    private boolean nonzero(BigDecimal amount) {
        return amount != null && amount.signum() != 0;
    }
}
