package com.linearlite.server.service;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.config.RemoteTerminalProperties;
import com.linearlite.server.dto.RemoteTerminalDtos.ProfileRequest;
import com.linearlite.server.dto.RemoteTerminalDtos.ProfileView;
import com.linearlite.server.entity.RtProfile;
import com.linearlite.server.entity.RtTarget;
import com.linearlite.server.mapper.*;
import com.linearlite.server.sshca.CaKeyCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RemoteTerminalPasswordAdminTest {
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final RtTargetMapper targetMapper = mock(RtTargetMapper.class);
    private final RtProfileMapper profileMapper = mock(RtProfileMapper.class);
    private final RtGrantMapper grantMapper = mock(RtGrantMapper.class);
    private final RemoteTerminalAuditService audit = mock(RemoteTerminalAuditService.class);
    private CaKeyCipher cipher;
    private RemoteTerminalAdminService service;
    private RtProfile profile;

    @BeforeEach
    void setUp() {
        RemoteTerminalProperties properties = new RemoteTerminalProperties();
        properties.setCaKek(Base64.getEncoder().encodeToString(new byte[32]));
        cipher = new CaKeyCipher(properties);
        service = new RemoteTerminalAdminService(guard, mock(ProjectMapper.class), mock(ProjectMemberMapper.class),
                mock(RtCaKeyMapper.class), targetMapper, profileMapper, grantMapper, mock(RtCertificateMapper.class),
                mock(RtAuditEventMapper.class), cipher, audit);
        RtTarget target = new RtTarget();
        target.setId(10L);
        target.setProjectId(1L);
        target.setTargetId("target");
        when(targetMapper.selectById(10L)).thenReturn(target);
        profile = new RtProfile();
        profile.setId(20L);
        profile.setTargetRef(10L);
        profile.setProfileId("profile");
        profile.setLoginUser("user");
        profile.setAuthType("PASSWORD");
        profile.setMaxTtlSeconds(600);
        profile.setPermitPty(true);
        when(profileMapper.selectById(20L)).thenReturn(profile);
        when(grantMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(profileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    @Test
    void creatingPasswordIdentityStoresEncryptedPasswordAndNeverExposesIt() {
        String secret = UUID.randomUUID().toString();
        doAnswer(call -> { call.<RtProfile>getArgument(0).setId(20L); return 1; })
                .when(profileMapper).insert(any(RtProfile.class));
        ProfileRequest request = new ProfileRequest("profile", "user", 600, true, "PASSWORD", secret);
        ProfileView view = service.createProfile(1L, 10L, 7L, request);
        org.mockito.ArgumentCaptor<RtProfile> capture = org.mockito.ArgumentCaptor.forClass(RtProfile.class);
        verify(profileMapper).updateById(capture.capture());
        assertEquals(secret, cipher.decryptPassword(1L, 20L, capture.getValue().getPasswordCipher()));
        assertTrue(view.hasPassword());
        assertFalse(view.toString().contains(secret));
        assertFalse(request.toString().contains(secret));
        verify(audit).record(eq(1L), eq(7L), eq("profile.create"), anyString(), isNull(),
                argThat(value -> !value.toString().contains(secret)));
        assertThrows(IllegalArgumentException.class, () -> service.createProfile(1L, 10L, 7L,
                new ProfileRequest("profile", "user", 600, true, "PASSWORD", null)));
    }

    @Test
    void updatingPasswordRetainsReplacesAndClearsCipher() throws Exception {
        String initial = UUID.randomUUID().toString();
        String next = UUID.randomUUID().toString();
        byte[] initialCipher = cipher.encryptPassword(1L, 20L, initial);
        profile.setPasswordCipher(initialCipher);
        service.updateProfile(1L, 20L, 7L, new ProfileRequest("profile", "user", 600, true, "PASSWORD", null));
        assertArrayEquals(initialCipher, profile.getPasswordCipher());
        service.updateProfile(1L, 20L, 7L, new ProfileRequest("profile", "user", 600, true, "PASSWORD", next));
        assertEquals(next, cipher.decryptPassword(1L, 20L, profile.getPasswordCipher()));
        service.updateProfile(1L, 20L, 7L, new ProfileRequest("profile", "user", 600, true, "CERTIFICATE", null));
        assertNull(profile.getPasswordCipher());
        assertEquals(FieldStrategy.ALWAYS, RtProfile.class.getDeclaredField("passwordCipher").getAnnotation(TableField.class).updateStrategy());
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(1L, 20L, 7L,
                new ProfileRequest("profile", "user", 600, true, "PASSWORD", null)));
    }

    @Test
    void deletingIdentityClearsCipher() {
        profile.setPasswordCipher(cipher.encryptPassword(1L, 20L, UUID.randomUUID().toString()));
        service.deleteProfile(1L, 20L, 7L);
        assertNull(profile.getPasswordCipher());
        assertNotNull(profile.getDeletedAt());
        verify(profileMapper).updateById(profile);
    }
}
