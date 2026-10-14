package com.linearlite.server.security;

import com.linearlite.server.exception.UnauthorizedException;
import com.linearlite.server.service.AuthService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

@Component
public class LinearLiteAuthenticationProvider implements AuthenticationProvider {

    private final AuthService authService;
    private final UserDetailsService userDetailsService;

    public LinearLiteAuthenticationProvider(AuthService authService, UserDetailsService userDetailsService) {
        this.authService = authService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String identity = authentication.getName();
        Object credential = authentication.getCredentials();
        if (!(credential instanceof String password)) {
            throw new BadCredentialsException("Invalid Linear Lite credentials.");
        }
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(identity);
        } catch (UsernameNotFoundException exception) {
            throw new BadCredentialsException("Invalid Linear Lite credentials.");
        }
        if (!user.isEnabled()) {
            throw new DisabledException("Linear Lite account is disabled.");
        }
        try {
            authService.login(identity, password);
        } catch (UnauthorizedException | IllegalArgumentException exception) {
            throw new BadCredentialsException("Invalid Linear Lite credentials.");
        }
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
