package com.archosan.invoice.document.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B-43, NFR-10: şifresiz kullanıcıyla servis açılmaz; şifre {@code toString}'de görünmez. */
class ApiUsersPropertiesTest {

    @Test
    void blankPasswordFailsStartupNamingOnlyTheUser() {
        assertThatThrownBy(() -> new ApiUsersProperties(Map.of("expert",
                new ApiUsersProperties.User(" ", List.of(Role.EXPERT)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expert");
        assertThatThrownBy(() -> new ApiUsersProperties(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiUsersProperties(Map.of("expert",
                new ApiUsersProperties.User("gizli-sifre", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("gizli-sifre");
    }

    @Test
    void toStringMasksPasswords() {
        ApiUsersProperties properties = new ApiUsersProperties(Map.of("admin",
                new ApiUsersProperties.User("gizli-sifre", List.of(Role.ADMIN))));

        assertThat(properties.toString()).contains("admin", "ADMIN", "****").doesNotContain("gizli-sifre");
    }
}
