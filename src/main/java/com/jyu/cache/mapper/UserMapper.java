package com.jyu.cache.mapper;

import com.jyu.cache.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户表 Mapper
 */
@Mapper
public interface UserMapper {

    SysUser selectByUsername(@Param("username") String username);

    SysUser selectById(@Param("id") Long id);

    int insert(SysUser user);
}
