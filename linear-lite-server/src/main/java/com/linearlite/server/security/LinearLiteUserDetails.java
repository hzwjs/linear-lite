package com.linearlite.server.security;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

public final class LinearLiteUserDetails implements UserDetails {

    private final Long userId;
    private final String username;
    private final boolean enabled;

    public LinearLiteUserDetails(Long userId, String username, boolean enabled) {
        this.userId = userId;
        this.username = username;
        this.enabled = enabled;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public Collection<org.springframework.security.core.GrantedAuthority> getAuthorities() {
        return AuthorityUtils.createAuthorityList("ROLE_USER");
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
