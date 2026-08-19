package com.archosan.invoice.extraction.security;

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
 * Ham LLM çıktısı API'sinin yetkisi (B-47, ADR-22): document-service ile aynı yöntem ve aynı kullanıcılar. Okuma
 * {@code ADMIN} ve {@code EXPERT} (uzman düşük güvenli kaydı incelerken modelin ne çıkardığını görür); ham çıktı
 * fatura metnini içerdiği için onaycıya açık değildir. Kimliksiz 401, rolü yetmeyen 403.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

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
                        .requestMatchers(HttpMethod.GET, "/api/v1/extraction-runs")
                        .hasAnyRole(Role.ADMIN.name(), Role.EXPERT.name())
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Şifreler açılışta BCrypt'lenir. */
    @Bean
    UserDetailsService apiUsers(ApiUsersProperties properties, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(properties.users().entrySet().stream()
                .map(e -> User.withUsername(e.getKey())
                        .password(encoder.encode(e.getValue().password()))
                        .roles(e.getValue().roles().stream().map(Role::name).toArray(String[]::new))
                        .build())
                .toArray(UserDetails[]::new));
    }
}
