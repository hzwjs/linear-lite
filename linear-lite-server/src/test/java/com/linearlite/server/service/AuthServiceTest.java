package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.LoginResponse;
import com.linearlite.server.dto.RegisterRequest;
import com.linearlite.server.entity.EmailVerificationCode;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.UnauthorizedException;
import com.linearlite.server.mapper.EmailVerificationCodeMapper;
import com.linearlite.server.mapper.UserMapper;
import com.linearlite.server.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private EmailVerificationCodeMapper emailVerificationCodeMapper;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private EmailService emailService;
    @Mock
    private Supplier<String> verificationCodeGenerator;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userMapper,
                emailVerificationCodeMapper,
                jwtUtil,
                emailService,
                verificationCodeGenerator
        );
    }

    @Test
    void loginSupportsEmailIdentity() {
        User user = new User();
        user.setId(9L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPassword("secret123");

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(jwtUtil.generateToken(9L, "alice")).thenReturn("jwt-token");

        LoginResponse response = authService.login("alice@example.com", "secret123");

        assertEquals("jwt-token", response.getToken());
        assertEquals(9L, response.getUserId());
        assertEquals("alice", response.getUsername());
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = new User();
        user.setId(9L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPassword("secret123");

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);

        UnauthorizedException error = assertThrows(
                UnauthorizedException.class,
                () -> authService.login("alice@example.com", "bad-pass")
        );

        assertEquals("Incorrect email/username or password.", error.getMessage());
    }

    @Test
    void sendRegisterCodeStoresCodeAndSendsEmail() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(verificationCodeGenerator.get()).thenReturn("123456");

        authService.sendRegisterCode("new@example.com");

        ArgumentCaptor<EmailVerificationCode> codeCaptor = ArgumentCaptor.forClass(EmailVerificationCode.class);
        verify(emailVerificationCodeMapper).insert(codeCaptor.capture());
        verify(emailVerificationCodeMapper).update(any(), any(LambdaQueryWrapper.class));
        verify(emailService).sendVerificationCode("new@example.com", "123456");

        EmailVerificationCode saved = codeCaptor.getValue();
        assertEquals("new@example.com", saved.getEmail());
        assertEquals("123456", saved.getCode());
        assertEquals("register", saved.getPurpose());
    }

    @Test
    void registerRejectsExpiredCode() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("new@example.com");
        request.setCode("123456");
        request.setUsername("new-user");
        request.setPassword("secret123");

        EmailVerificationCode code = new EmailVerificationCode();
        code.setId(5L);
        code.setEmail("new@example.com");
        code.setCode("123456");
        code.setPurpose("register");
        code.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(emailVerificationCodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(code);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register(request)
        );

        assertEquals("Verification code has expired.", error.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerCreatesUserMarksCodeUsedAndReturnsLoginPayload() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("new@example.com");
        request.setCode("123456");
        request.setUsername("new-user");
        request.setPassword("secret123");

        EmailVerificationCode code = new EmailVerificationCode();
        code.setId(5L);
        code.setEmail("new@example.com");
        code.setCode("123456");
        code.setPurpose("register");
        code.setExpiresAt(LocalDateTime.now().plusMinutes(10));

        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(emailVerificationCodeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(code);
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(42L);
            return 1;
        }).when(userMapper).insert(any(User.class));
        when(jwtUtil.generateToken(42L, "new-user")).thenReturn("jwt-token");

        LoginResponse response = authService.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        verify(emailVerificationCodeMapper).updateById(any(EmailVerificationCode.class));

        User saved = userCaptor.getValue();
        assertEquals("new-user", saved.getUsername());
        assertEquals("new@example.com", saved.getEmail());
        assertEquals("secret123", saved.getPassword());
        assertEquals("jwt-token", response.getToken());
    }
}
