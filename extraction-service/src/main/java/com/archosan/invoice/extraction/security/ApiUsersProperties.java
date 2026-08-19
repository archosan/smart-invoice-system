package com.archosan.invoice.extraction.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * API kullanıcıları ({@code invoice.extraction.security.users}; B-47, document-service ile aynı, ADR-22): ad → şifre
 * ve roller. Şifreler {@code .env}'den gelir (NFR-10); biri boşsa servis açılmaz. {@code toString} şifreleri
 * göstermez.
 */
@ConfigurationProperties("invoice.extraction.security")
public record ApiUsersProperties(Map<String, User> users) {

    public ApiUsersProperties {
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException(
                    "invoice.extraction.security.users boş: en az bir API kullanıcısı gerekli");
        }
        users.forEach((name, user) -> {
            if (user == null || user.password() == null || user.password().isBlank()) {
                throw new IllegalArgumentException("API kullanıcısının şifresi yok: " + name
                        + " (.env'deki API_*_PASSWORD satırlarına bakın)");
            }
            if (user.roles() == null || user.roles().isEmpty()) {
                throw new IllegalArgumentException("API kullanıcısının rolü yok: " + name);
            }
        });
        users = Map.copyOf(users);
    }

    public record User(String password, List<Role> roles) {

        public User {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        @Override
        public String toString() {
            return "User[password=****, roles=" + roles + "]";
        }
    }

    @Override
    public String toString() {
        return "ApiUsersProperties[users=" + users.entrySet().stream()
                .map(e -> e.getKey() + "=" + Objects.toString(e.getValue()))
                .collect(Collectors.joining(", ", "{", "}")) + "]";
    }
}
