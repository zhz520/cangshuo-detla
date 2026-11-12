package com.delta.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.delta.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM `user` WHERE is_guest=0 AND phone=#{phone} AND (#{excludeId,jdbcType=BIGINT} IS NULL OR id != #{excludeId,jdbcType=BIGINT})")
    long countReservedPhone(@org.apache.ibatis.annotations.Param("phone") String phone,@org.apache.ibatis.annotations.Param("excludeId") Long excludeId);
    @org.apache.ibatis.annotations.Update("UPDATE `user` SET phone='',openid=CONCAT('closed:',id),nickname='已注销用户',avatar='',password=NULL,status=0,auth_version=COALESCE(auth_version,0)+1 WHERE is_guest=0 AND deleted=1 AND phone=#{phone}")
    int releaseDeletedPhone(@org.apache.ibatis.annotations.Param("phone") String phone);
    @org.apache.ibatis.annotations.Update("UPDATE `user` SET phone='',openid=CONCAT('closed:',id),nickname='已注销用户',avatar='',password=NULL,status=0,auth_version=COALESCE(auth_version,0)+1 WHERE is_guest=0 AND deleted=1 AND openid=#{openid}")
    int releaseDeletedOpenid(@org.apache.ibatis.annotations.Param("openid") String openid);
    @org.apache.ibatis.annotations.Update("UPDATE `user` SET phone='',openid=CONCAT('closed:',id),nickname='已注销用户',avatar='',password=NULL,status=0,auth_version=COALESCE(auth_version,0)+1 WHERE deleted=1 AND id=#{id}")
    int archiveDeletedUser(@org.apache.ibatis.annotations.Param("id") Long id);
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM player_reward WHERE status='REVERSAL_PENDING' AND (user_id=#{userId} OR player_id=#{playerId,jdbcType=BIGINT})")
    long countPendingRewards(@org.apache.ibatis.annotations.Param("userId") Long userId,@org.apache.ibatis.annotations.Param("playerId") Long playerId);
}
