package com.archosan.invoice.document.api;

import com.archosan.invoice.document.settings.SettingsAdmin;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Yönetici ayarlar API'si (B-39, FR-A2): güven eşiği ve tutar eşiği. {@code PUT} ikisini ya da birini alır, verilmeyen
 * değişmez; tutarlar string olarak taşınır. Yalnız {@code ADMIN} (B-43); değişikliğin izinde yöneticinin adı.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class SettingsAdminController {

    private final SettingsAdmin admin;

    public SettingsAdminController(SettingsAdmin admin) {
        this.admin = admin;
    }

    @GetMapping
    public SettingsAdmin.Values get() {
        return admin.current();
    }

    @PutMapping
    public SettingsAdmin.Values update(@RequestBody SettingsAdmin.Values changes, Authentication authentication) {
        return admin.update(changes, authentication.getName());
    }
}
