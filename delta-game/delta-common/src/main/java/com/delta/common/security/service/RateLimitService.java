package com.delta.common.security.service;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.delta.common.tenant.TenantKeyEncoder;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RateLimitService {
    private final StringRedisTemplate redis;
    @Autowired(required = false)
    private TenantKeyEncoder tenantKeys;
    private static final DefaultRedisScript<Long> COUNTER=new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n",Long.class);
    public void check(String key,int limit,int seconds) {
        String redisKey=tenantKeys==null?"rate:"+key:tenantKeys.key("rate:"+key);
        Long count=redis.execute(COUNTER,List.of(redisKey),String.valueOf(seconds));
        if(count==null || count>limit) throw new BusinessException(429,"操作过于频繁，请稍后重试");
    }
}
