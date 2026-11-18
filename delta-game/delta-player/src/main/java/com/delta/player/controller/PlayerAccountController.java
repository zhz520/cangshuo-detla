package com.delta.player.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.player.entity.PlayerAccount;
import com.delta.player.service.PlayerAccountService;
import com.delta.player.service.PlayerIdentityNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/player/account")
@RequiredArgsConstructor
public class PlayerAccountController {
    private final PlayerAccountService playerAccountService;
    private final PlayerIdentityNameService identityNames;

    @GetMapping({"" , "/list"})
    public R<List<PlayerAccount>> list() {
        Long playerId=SecurityUtils.getUserId();
        String realName="";
        try { realName=identityNames.resolve(playerId).name(); } catch (com.delta.common.exception.BusinessException ignored) {}
        List<PlayerAccount> accounts=playerAccountService.list(
                new LambdaQueryWrapper<PlayerAccount>().eq(PlayerAccount::getPlayerId, playerId));
        for(PlayerAccount account:accounts)account.setIdentityMatched(PlayerIdentityNameService.matches(realName,account.getAccountName()));
        return R.ok(accounts);
    }

    @PostMapping
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public R<Void> add(@RequestBody PlayerAccount account) {
        Long playerId=SecurityUtils.getUserId();
        if(playerAccountService.count(new LambdaQueryWrapper<PlayerAccount>().eq(PlayerAccount::getPlayerId,playerId))>=10)
            return R.fail(409,"最多保存10个收款账户");
        playerAccountService.save(clean(account,null));
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody PlayerAccount account) {
        requireOwned(account.getId());
        playerAccountService.updateById(clean(account,account.getId()));
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        requireOwned(id);
        playerAccountService.removeById(id);
        return R.ok();
    }

    private void requireOwned(Long id) {
        PlayerAccount existing=id==null?null:playerAccountService.getById(id);
        if(existing==null || !SecurityUtils.getUserId().equals(existing.getPlayerId()))
            throw new com.delta.common.exception.BusinessException(404,"收款账户不存在");
    }
    private PlayerAccount clean(PlayerAccount input,Long id) {
        if(input.getType()==null || !java.util.Set.of("ALIPAY","WECHAT","BANK").contains(input.getType())
                || input.getAccountName()==null || input.getAccountName().isBlank() || input.getAccountName().length()>128
                || input.getAccountNo()==null || input.getAccountNo().isBlank() || input.getAccountNo().length()>256)
            throw new com.delta.common.exception.BusinessException(400,"收款账户信息不完整");
        if(input.getQrcodeUrl()!=null && !(input.getQrcodeUrl().isBlank() || input.getQrcodeUrl().matches("/file/.{1,500}")))
            throw new com.delta.common.exception.BusinessException(400,"收款码地址无效");
        if(identityNames.required()) identityNames.requireMatches(SecurityUtils.getUserId(),input.getAccountName(),"添加收款账户：");
        PlayerAccount result=new PlayerAccount();result.setId(id);result.setPlayerId(SecurityUtils.getUserId());
        result.setType(input.getType());result.setAccountName(input.getAccountName());result.setAccountNo(input.getAccountNo());result.setQrcodeUrl(input.getQrcodeUrl());
        result.setIsDefault(Integer.valueOf(1).equals(input.getIsDefault())?1:0);return result;
    }

}
