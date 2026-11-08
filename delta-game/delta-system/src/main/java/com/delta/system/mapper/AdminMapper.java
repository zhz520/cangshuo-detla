package com.delta.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.delta.system.entity.Admin;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdminMapper extends BaseMapper<Admin> {
    @Select("SELECT * FROM admin WHERE username=#{username} LIMIT 1 FOR UPDATE")
    Admin findAnyByUsernameForUpdate(@Param("username") String username);

    @Update("UPDATE admin SET username=#{archivedUsername} WHERE id=#{id} AND deleted=1")
    int archiveDeletedUsername(@Param("id") Long id, @Param("archivedUsername") String archivedUsername);
}
