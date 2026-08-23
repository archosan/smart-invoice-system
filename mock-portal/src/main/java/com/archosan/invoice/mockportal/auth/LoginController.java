package com.archosan.invoice.mockportal.auth;

import com.archosan.invoice.mockportal.PortalProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** Kullanıcı adı / şifre ile giriş; oturum servlet oturumunda (cookie) tutulur (FR-P1). */
@Controller
class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final PortalProperties properties;

    LoginController(PortalProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/")
    String root() {
        return "redirect:/invoices";
    }

    @GetMapping("/login")
    String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    String login(@RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String password,
            HttpServletRequest request, Model model) {
        if (!(matches(username, properties.username()) & matches(password, properties.password()))) {
            log.warn("Başarısız giriş denemesi");
            model.addAttribute("error", true);
            return "login";
        }
        // Oturum sabitlemeye karşı girişte yeni oturum.
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(LoginInterceptor.USER_ATTRIBUTE, properties.username());
        session.setAttribute(LoginInterceptor.LAST_ACCESS_ATTRIBUTE, Instant.now());
        return "redirect:/invoices";
    }

    @PostMapping("/logout")
    String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login";
    }

    /** Sabit süreli karşılaştırma. */
    private static boolean matches(String given, String expected) {
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
