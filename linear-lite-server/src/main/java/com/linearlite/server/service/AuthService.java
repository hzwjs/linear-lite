package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.LoginResponse;
import com.linearlite.server.dto.RegisterRequest;
import com.linearlite.server.entity.EmailVerificationCode;
import com.linearlite.server.entity.ProjectInvitation;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.UnauthorizedException;
import com.linearlite.server.mapper.EmailVerificationCodeMapper;
import com.linearlite.server.mapper.ProjectInvitationMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.UserMapper;
import com.linearlite.server.util.EmailNormalization;
import com.linearlite.server.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

@Service
public class AuthService {

    private static final String REGISTER_PURPOSE = "register";

    private final UserMapper userMapper;
    private final EmailVerificationCodeMapper emailVerificationCodeMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectInvitationMapper projectInvitationMapper;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final Supplier<String> verificationCodeGenerator;

    @Autowired
    public AuthService(
            UserMapper userMapper,
            EmailVerificationCodeMapper emailVerificationCodeMapper,
            ProjectMemberMapper projectMemberMapper,
            ProjectInvitationMapper projectInvitationMapper,
            JwtUtil jwtUtil,
            EmailService emailService,
            PasswordEncoder passwordEncoder
    ) {
        this(userMapper, emailVerificationCodeMapper, projectMemberMapper, projectInvitationMapper, jwtUtil, emailService,
                passwordEncoder,
                () -> String.format("%06d", (int) (Math.random() * 1_000_000)));
    }

    AuthService(
            UserMapper userMapper,
            EmailVerificationCodeMapper emailVerificationCodeMapper,
            ProjectMemberMapper projectMemberMapper,
            ProjectInvitationMapper projectInvitationMapper,
            JwtUtil jwtUtil,
            EmailService emailService,
            PasswordEncoder passwordEncoder,
            Supplier<String> verificationCodeGenerator
    ) {
        this.userMapper = userMapper;
        this.emailVerificationCodeMapper = emailVerificationCodeMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectInvitationMapper = projectInvitationMapper;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.verificationCodeGenerator = verificationCodeGenerator;
    }

    public LoginResponse login(String identity, String password) {
        String normalizedIdentity = requireText(identity, "Email or username is required.");
        String normalizedPassword = requireText(password, "Password is required.");

        User user = findUserByIdentity(normalizedIdentity);

        if (user == null || !matchesPassword(normalizedPassword, user)) {
            throw new UnauthorizedException("Incorrect email/username or password.");
        }
        acceptPendingInvitations(user);
        return new LoginResponse(
                jwtUtil.generateToken(user.getId(), user.getUsername()),
                user.getId(),
                user.getUsername()
        );
    }

    public void sendRegisterCode(String email) {
        String normalizedEmail = normalizeEmail(email);
        ensureEmailNotRegistered(normalizedEmail);

        EmailVerificationCode invalidated = new EmailVerificationCode();
        invalidated.setUsedAt(LocalDateTime.now());
        emailVerificationCodeMapper.update(
                invalidated,
                new LambdaQueryWrapper<EmailVerificationCode>()
                        .eq(EmailVerificationCode::getEmail, normalizedEmail)
                        .eq(EmailVerificationCode::getPurpose, REGISTER_PURPOSE)
                        .isNull(EmailVerificationCode::getUsedAt)
        );

        String code = verificationCodeGenerator.get();
        EmailVerificationCode record = new EmailVerificationCode();
        record.setEmail(normalizedEmail);
        record.setCode(code);
        record.setPurpose(REGISTER_PURPOSE);
        record.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        record.setCreatedAt(LocalDateTime.now());
        emailVerificationCodeMapper.insert(record);

        emailService.sendVerificationCode(normalizedEmail, code);
    }

    public LoginResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        String code = requireText(request.getCode(), "Verification code is required.");
        String username = requireText(request.getUsername(), "Username is required.");
        String password = requireText(request.getPassword(), "Password is required.");
        if (password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters.");
        }

        ensureEmailNotRegistered(email);
        ensureUsernameNotRegistered(username);

        EmailVerificationCode verificationCode = emailVerificationCodeMapper.selectOne(
                new LambdaQueryWrapper<EmailVerificationCode>()
                        .eq(EmailVerificationCode::getEmail, email)
                        .eq(EmailVerificationCode::getPurpose, REGISTER_PURPOSE)
                        .isNull(EmailVerificationCode::getUsedAt)
                        .orderByDesc(EmailVerificationCode::getCreatedAt)
                        .last("LIMIT 1")
        );

        if (verificationCode == null || !code.equals(verificationCode.getCode())) {
            throw new IllegalArgumentException("Incorrect verification code.");
        }
        if (verificationCode.getExpiresAt() == null || verificationCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Verification code has expired.");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        userMapper.insert(user);

        verificationCode.setUsedAt(LocalDateTime.now());
        emailVerificationCodeMapper.updateById(verificationCode);
        acceptPendingInvitations(user);

        return new LoginResponse(
                jwtUtil.generateToken(user.getId(), user.getUsername()),
                user.getId(),
                user.getUsername()
        );
    }

    private void ensureEmailNotRegistered(String email) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (count != null && count > 0) {
            throw new IllegalArgumentException("Email is already registered.");
        }
    }

    private void ensureUsernameNotRegistered(String username) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (count != null && count > 0) {
            throw new IllegalArgumentException("Username already exists.");
        }
    }

    private String normalizeEmail(String email) {
        return EmailNormalization.normalizeAndValidate(
                email,
                "Email is required.",
                "Email format is invalid.");
    }

    private User findUserByIdentity(String identity) {
        if (identity.contains("@")) {
            User emailMatch = userMapper.selectOne(
                    new LambdaQueryWrapper<User>().eq(User::getEmail, identity).last("LIMIT 1")
            );
            if (emailMatch != null) {
                return emailMatch;
            }
        }
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, identity).last("LIMIT 1")
        );
    }

    private void acceptPendingInvitations(User user) {
        if (user == null || user.getId() == null || user.getEmail() == null) {
            return;
        }
        List<ProjectInvitation> invitations = projectInvitationMapper.selectList(
                new LambdaQueryWrapper<ProjectInvitation>()
                        .eq(ProjectInvitation::getEmail, user.getEmail())
                        .isNull(ProjectInvitation::getAcceptedAt)
        );
        for (ProjectInvitation invitation : invitations) {
            Long exists = projectMemberMapper.selectCount(
                    new LambdaQueryWrapper<ProjectMember>()
                            .eq(ProjectMember::getProjectId, invitation.getProjectId())
                            .eq(ProjectMember::getUserId, user.getId())
            );
            if (exists == null || exists == 0) {
                ProjectMember member = new ProjectMember();
                member.setProjectId(invitation.getProjectId());
                member.setUserId(user.getId());
                member.setRole("member");
                member.setCreatedAt(LocalDateTime.now());
                projectMemberMapper.insert(member);
            }
            invitation.setAcceptedAt(LocalDateTime.now());
            projectInvitationMapper.updateById(invitation);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private boolean matchesPassword(String rawPassword, User user) {
        String stored = user.getPassword();
        if (stored == null || stored.isBlank()) {
            return false;
        }
        if (looksLikeBcrypt(stored)) {
            return passwordEncoder.matches(rawPassword, stored);
        }
        if (!rawPassword.equals(stored)) {
            return false;
        }
        user.setPassword(passwordEncoder.encode(rawPassword));
        userMapper.updateById(user);
        return true;
    }

    private static boolean looksLikeBcrypt(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }
}
