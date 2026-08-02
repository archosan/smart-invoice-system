package com.archosan.invoice.document.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Rol bazlı yetki (B-43, NFR-10): HTTP Basic, durumsuz, CSRF kapalı (tarayıcı arayüzü ve oturum çerezi yok). Kimlik
 * doğrulamasız istek 401, rolü yetmeyen 403. Kural uç bazında burada; durum bazlı olanlar (ret: kayıt
 * {@code NEEDS_REVIEW} ise uzman, {@code PENDING_APPROVAL} ise onaycı; kendi yüklediğini onaylama yasağı) servistedir.
 * Basic şifreyi her istekte taşır: gerçek kurulumda TLS arkasında çalışmalıdır.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final String EXPERT = Role.EXPERT.name();
    private static final String APPROVER = Role.APPROVER.name();
    private static final String ADMIN = Role.ADMIN.name();

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(withDefaults())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        // Metrikler (B-48): Prometheus yönetici kimliğiyle okur.
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole(Role.ADMIN.name())
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/documents").hasRole(EXPERT)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/documents/*/fields").hasRole(EXPERT)
                        .requestMatchers(HttpMethod.POST, "/api/v1/documents/*/duplicate-decision").hasRole(EXPERT)
                        .requestMatchers(HttpMethod.POST, "/api/v1/documents/*/approve").hasRole(APPROVER)
                        .requestMatchers(HttpMethod.POST, "/api/v1/documents/*/reject").hasAnyRole(EXPERT, APPROVER)
                        .requestMatchers(HttpMethod.GET, "/api/v1/documents", "/api/v1/documents/**").authenticated()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Şifreler açılışta BCrypt'lenir; düz hali bellekte yalnız {@link ApiUsersProperties}'te kalır. */
    @Bean
    UserDetailsService apiUsers(ApiUsersProperties properties, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(properties.users().entrySet().stream()
                .map(e -> User.withUsername(e.getKey())
                        .password(encoder.encode(e.getValue().password()))
                        .roles(e.getValue().roles().stream().map(Role::name).toArray(String[]::new))
                        .build())
                .toList()
                .toArray(new UserDetails[0]));
    }
}
