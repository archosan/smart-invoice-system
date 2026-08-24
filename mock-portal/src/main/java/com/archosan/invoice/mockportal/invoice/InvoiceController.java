package com.archosan.invoice.mockportal.invoice;

import com.archosan.invoice.mockportal.PortalProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Fatura listesi/arama, giriş formu ve kayıt sayfası (FR-P1, FR-P2). Yalnızca HTML; API yoktur. Tüm sayfalar oturum
 * ister ({@link com.archosan.invoice.mockportal.auth.LoginInterceptor}).
 */
@Controller
@RequestMapping("/invoices")
class InvoiceController {

    private static final Logger log = LoggerFactory.getLogger(InvoiceController.class);

    private final InvoiceStore store;
    private final PortalProperties properties;

    InvoiceController(InvoiceStore store, PortalProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @GetMapping
    String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("result", store.page(q, page, properties.pageSize()));
        return "invoices";
    }

    @GetMapping("/new")
    String newForm(Model model) {
        return form(model, InvoiceForm.empty(), Map.of());
    }

    @PostMapping
    String submit(@ModelAttribute InvoiceForm form, Model model, HttpServletResponse response) {
        InvoiceForm.Result result = form.validate();
        if (!result.ok()) {
            return form(model, form, result.errors());
        }
        String invoiceNo = result.valid().invoiceNo();
        if (properties.failPattern().map(p -> p.matcher(invoiceNo).find()).orElse(false)) {
            // Kayıt yapılmadan düşer; RPA aynı faturayı yeniden denediğinde de düşer (B-28).
            log.warn("Hata bayrağı: fatura no deseni eşleşti, 500 dönülüyor: invoiceNo={}", invoiceNo);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return "portal-error";
        }
        PortalInvoice saved = store.add(result.valid());
        log.info("Fatura kaydedildi: refNo={}, invoiceNo={}", saved.refNo(), saved.invoiceNo());
        if (properties.slowPattern().map(p -> p.matcher(invoiceNo).find()).orElse(false)) {
            // Kayıt yapıldı, yanıt gecikiyor: bot bu arada ölürse portalda kayıt var, sistemde yok (US-08, B-35).
            log.warn("Yavaş yanıt: kayıt yapıldı, yanıt {} geciktiriliyor: refNo={}", properties.slowResponseDelay(),
                    saved.refNo());
            sleep(properties.slowResponseDelay());
        }
        return "redirect:/invoices/" + UriUtils.encodePathSegment(saved.refNo(), StandardCharsets.UTF_8)
                + "?created";
    }

    @GetMapping("/{refNo}")
    String detail(@PathVariable String refNo, @RequestParam(required = false) String created, Model model) {
        PortalInvoice invoice = store.find(refNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("invoice", invoice);
        model.addAttribute("created", created != null);
        return "invoice";
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String form(Model model, InvoiceForm form, Map<String, String> errors) {
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("currencies", InvoiceForm.CURRENCIES.stream().sorted().toList());
        return "invoice-form";
    }
}
