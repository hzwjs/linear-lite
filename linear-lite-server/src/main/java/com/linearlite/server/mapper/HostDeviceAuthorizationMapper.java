package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.HostDeviceAuthorization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface HostDeviceAuthorizationMapper extends BaseMapper<HostDeviceAuthorization> {
    @Select("SELECT id FROM users WHERE id = #{userId} FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM host_device_authorizations
            WHERE oauth_authorization_id = #{authorizationId} FOR UPDATE
            """)
    HostDeviceAuthorization selectAuthorizationForUpdate(@Param("authorizationId") String authorizationId);

    @Select("""
            SELECT * FROM host_device_authorizations
            WHERE oauth_authorization_id = #{authorizationId}
            """)
    HostDeviceAuthorization selectByAuthorization(@Param("authorizationId") String authorizationId);

    @Select("""
            SELECT * FROM host_device_authorizations
            WHERE user_id = #{userId} AND device_id = #{deviceId} AND revoked_at IS NULL
            ORDER BY id DESC LIMIT 1
            """)
    HostDeviceAuthorization selectActive(@Param("userId") Long userId, @Param("deviceId") String deviceId);

    @Select("""
            SELECT * FROM host_device_authorizations
            WHERE user_id = #{userId} AND device_id = #{deviceId} AND revoked_at IS NULL
            ORDER BY id DESC LIMIT 1 FOR UPDATE
            """)
    HostDeviceAuthorization selectActiveForUpdate(@Param("userId") Long userId,
                                                   @Param("deviceId") String deviceId);

    @Select("""
            SELECT * FROM host_device_authorizations
            WHERE user_id = #{userId} AND id = #{id} FOR UPDATE
            """)
    HostDeviceAuthorization selectOwnedForUpdate(@Param("userId") Long userId, @Param("id") Long id);

    @Update("""
            UPDATE host_device_authorizations SET revoked_at = #{now}
            WHERE user_id = #{userId} AND device_id = #{deviceId} AND revoked_at IS NULL
            """)
    int revokeActive(@Param("userId") Long userId, @Param("deviceId") String deviceId,
                     @Param("now") LocalDateTime now);
}
