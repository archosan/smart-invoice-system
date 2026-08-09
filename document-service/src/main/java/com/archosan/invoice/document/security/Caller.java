package com.archosan.invoice.document.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.EnumSet;
import java.util.Set;

/** İsteği yapan kullanıcı (B-43): adı geçişlerin aktörü ve {@code uploaded_by} olur, rolleri durum bazlı kontrole girer. */
public record Caller(String username, Set<Role> roles) {

    public Caller {
        roles = roles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(roles));
    }

    public static Caller of(Authentication authentication) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            for (Role role : Role.values()) {
                if (("ROLE_" + role.name()).equals(authority.getAuthority())) {
                    roles.add(role);
                }
            }
        }
        return new Caller(authentication.getName(), roles);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }
}
