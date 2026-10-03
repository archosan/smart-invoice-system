# Akıllı Fatura İşleme ve Sözleşme Uyum Sistemi — Ana Karar Dokümanı

## 1. Proje özeti

Sistem tedarikçi faturalarını PDF'ten okur, kurallarla ve fatura tarihinde geçerli tedarikçi sözleşmesiyle karşılaştırır, onaylanan faturayı API'si olmayan eski muhasebe portalına RPA ile girer. Hedef, kapsamı dar ama hata senaryoları derin bir backend sistemidir. İki temel garanti vardır: bir fatura portala en fazla bir kez girilir ve hiçbir mesaj sessizce kaybolmaz.

Bu doküman, "Gereksinimler ve Kapsam" (30 Eylül 2026) ve "Sistem Tasarımı" (1 Ekim 2026) dokümanlarındaki kararların tek kaynağıdır. Kodlamaya geçerken referans burasıdır; ID'ler (US-xx, FR-xx, NFR-xx) kaynak dokümanlarla aynıdır.

**Kısıtlar:** tek geliştirici, yan uğraş temposu; ücretli LLM yok, her şey yerelde (M3 Pro, 18 GB); canlı dağıtım yok, teslimat GitHub + README.

### Aktörler

| Aktör                        | Tür         | Sistemdeki rolü                                                                                                         |
| ---------------------------- | ----------- | ----------------------------------------------------------------------------------------------------------------------- |
| Muhasebe uzmanı (AP uzmanı)  | İnsan       | Fatura ve sözleşme yükler, durum takip eder, düşük güvenli kayıtları düzeltir                                           |
| Onaycı                       | İnsan       | Uyumsuz veya eşik üstü faturaları onaylar ya da gerekçeyle reddeder; kendi yüklediğini onaylayamaz (reddedebilir; B-43) |
| Sistem yöneticisi            | İnsan       | DLQ'daki mesajları inceler, yeniden işletir; eşikleri yönetir                                                           |
| Eski muhasebe portalı (mock) | Dış sistem  | API'si yok; login, oturum zaman aşımı, sayfalama ve ara sıra hata veren form                                            |
| Yerel LLM (Ollama)           | Dış bileşen | Metni JSON şemasına eşler, sözleşme maddesinden değer çıkarır; hatalı olabileceği varsayılır                            |

### Teknoloji yığını

| Katman             | Seçim                                                                 | Kullanıldığı yer                                       |
| ------------------ | --------------------------------------------------------------------- | ------------------------------------------------------ |
| Dil ve build       | Java 25 (LTS), Maven (wrapper, çok modüllü)                           | Tüm modüller                                           |
| Servis çatısı      | Spring Boot 4.1.1, Spring AMQP, Spring AI 2.0.x                       | Dört servis                                            |
| Mesajlaşma         | RabbitMQ 4.x, quorum kuyruklar                                        | Servisler arası tüm iletişim                           |
| Veritabanı         | PostgreSQL + pgvector (tek konteyner, dört veritabanı)                | Her servisin kendi veritabanı; sözleşme embedding'leri |
| Koordinasyon       | Redis + Redisson                                                      | LLM semaforu (v1), RPA kilidi ve portal oturumu (v2)   |
| PDF                | Apache PDFBox                                                         | Fatura ve sözleşme metin çıkarımı                      |
| LLM / embedding    | Ollama (macOS üzerinde), 7-8B sınıfı 4-bit model, bge-m3 (1024 boyut) | Alan çıkarımı, madde değer çıkarımı, embedding         |
| RPA                | Playwright (Java)                                                     | Mock portala giriş                                     |
| Test               | Testcontainers, stub LLM istemcisi                                    | Entegrasyon testleri                                   |
| Gözlemlenebilirlik | JSON log, `correlationId`, Actuator + Micrometer                      | Tüm servisler                                          |

Spring AI 2.0.x yalnızca Spring Boot 4.0–4.1 ile uyumludur (`>=4.0.0 <4.2.0-M1`); Boot 4.2'ye geçişte Spring AI 2.1 gerekir.

### Tasarımı taşıyan altı ilke

1. **Tek durum sahibi.** Faturanın durumu yalnızca document-service'te yaşar; diğer servisler komut alır, iş yapar, olay yayınlar (FR-D9).
2. **Komut ve olay ayrımı, orkestrasyon.** Komutlar tek alıcıya gider, olaylar olanı bildirir; sonraki adımı her zaman document-service başlatır.
3. **Transactional outbox, istisnasız.** Giden her mesaj iş verisiyle aynı transaction'da `outbox` tablosuna yazılır, relay yayınlar (NFR-02).
4. **En az bir kez teslim + idempotent tüketici.** İşlenen `messageId` aynı transaction'da `inbox` tablosuna yazılır (NFR-01 katman 1).
5. **Durum geçişi koşullu güncellemedir.** `UPDATE … WHERE id = ? AND status = ?`; geç ya da sırasız olay etkisiz kalır.
6. **Dış dünyaya yan etki için ek koruma.** Portal idempotent değildir; RPA tek aktif consumer (v1), Redis kilidi ve portalda ön arama (v2) ile korunur.

Servisten servise senkron çağrı yoktur; REST yalnızca insanların sisteme girdiği kapılardadır.

## 2. MVP kapsamı (v1) ve v2

v1 uçtan uca mutlu yoldur ve CV'ye eklenecek ilk sürümdür (yan uğraş temposunda yaklaşık 3-4 hafta). v1'in topolojisi v2 ile aynıdır: bütün kuyruklar, DLQ'lar, outbox ve inbox ilk günden kurulur; v2 yeni mesaj eklemez, mevcut iskeletin içine mantık ekler (tek istisna RPA bekleme odası).

### Senaryolar

| ID    | Senaryo                                     | Beklenen davranış                                                                                    | Sürüm |
| ----- | ------------------------------------------- | ---------------------------------------------------------------------------------------------------- | ----- |
| US-01 | Mutlu yol                                   | PDF yüklenir, `documentId` + `RECEIVED` döner; çıkarım, uyum, RPA sonrası `POSTED` + portal kayıt no | v1    |
| US-02 | Aynı PDF ikinci kez                         | Yeni kayıt açılmaz; HTTP 200, mevcut `documentId`, `duplicate: true`                                 | v1    |
| US-04 | LLM yanlış alan çıkarır                     | Güven skoru eşik altı → `NEEDS_REVIEW` (v1'de düzeltme ekranı yok)                                   | v1    |
| US-05 | LLM şemaya uymaz / yanıt vermez             | Sınırlı yeniden deneme; tükenirse `NEEDS_REVIEW`                                                     | v1    |
| US-03 | Farklı dosya, aynı fatura (VKN + fatura no) | `DUPLICATE_SUSPECTED`, portala girilmez                                                              | v2    |
| US-06 | Sözleşmeyle uyumsuzluk                      | `PENDING_APPROVAL`; onaycı madde ve farkı görür                                                      | v2    |
| US-07 | Geçerli sözleşme yok / birden fazla         | Uyum kontrolü atlanır, insan onayına gider                                                           | v2    |
| US-08 | RPA giriş sırasında çöker                   | Ön arama kaydı bulur, yeniden girmez; çift kayıt oluşmaz                                             | v2    |
| US-09 | Portal geçici hata verir                    | Artan beklemeli retry, yeniden login; tükenirse DLQ → `RPA_FAILED`                                   | v2    |
| US-10 | Yönetici DLQ'yu işler                       | FR-A1 ile güvenli yeniden işletme                                                                    | v2    |
| US-11 | Sözleşme yükleme                            | Geçerlilik tarihleriyle sözleşme indekslenir                                                         | v2    |
| US-12 | Fatura geçmişi                              | Kim, ne zaman, hangi durumdan hangisine                                                              | v2    |
| US-13 | Onay listesi ve ret gerekçesi               | Bekleyenleri listeler, gerekçeli ret                                                                 | v2    |

### Gereksinim dağılımı

|                    | v1                                                                                                                                       | v2                                                                                    |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| document-service   | FR-D1–D5, FR-D9–D10                                                                                                                      | FR-D6–D8, FR-D11                                                                      |
| extraction-service | FR-E1–E5                                                                                                                                 | FR-E6                                                                                 |
| compliance-service | FR-C5 (stub)                                                                                                                             | FR-C1–C4                                                                              |
| rpa-service        | FR-R1–R2, FR-R8                                                                                                                          | FR-R3–R7                                                                              |
| Mock portal        | FR-P1–P2                                                                                                                                 | FR-P3                                                                                 |
| Yönetim            | —                                                                                                                                        | FR-A1–A2                                                                              |
| NFR                | NFR-01 (1) + tek RPA consumer, NFR-02, NFR-03 (DLX + teslim limiti), NFR-06 (log + correlationId), NFR-07–09, NFR-10 (secret), NFR-11–14 | NFR-01 (2)(3), NFR-03 retry, NFR-04, NFR-05, NFR-06 metrikler, NFR-10 rol bazlı yetki |

### Fonksiyonel olmayan gereksinimler (özet)

| ID     | Konu                | Hedef                                                                  |
| ------ | ------------------- | ---------------------------------------------------------------------- |
| NFR-01 | Idempotency         | Fatura portala en fazla bir kez; dört katman (bölüm 5)                 |
| NFR-02 | Güvenilirlik        | Durable kuyruk, manuel ack, tüm yayınlarda outbox                      |
| NFR-03 | Hata toleransı      | Her kuyrukta DLX, teslim limiti 3; bozuk mesaj kuyruğu kilitleyemez    |
| NFR-04 | Kurtarılabilirlik   | Yeniden başlayan servis kaldığı yerden sürer                           |
| NFR-05 | Denetlenebilirlik   | Audit kayıtları güncellenmez, silinmez                                 |
| NFR-06 | Gözlemlenebilirlik  | JSON log, `correlationId` = `documentId`, kuyruk/DLQ metrikleri        |
| NFR-07 | Performans          | Yükleme API'si < 500 ms; tek sayfalık fatura yükleme → `POSTED` < 2 dk |
| NFR-08 | Kaynak              | Tüm yığın 18 GB'ta; LLM'e eşzamanlı 1-2 istek                          |
| NFR-09 | Değiştirilebilirlik | Model ve portal seçicileri konfigürasyonda                             |
| NFR-10 | Güvenlik            | Portal kimlik bilgisi secret'tan, loga yazılmaz; rol bazlı yetki (v2)  |
| NFR-11 | Gizlilik            | Veri makineden çıkmaz, LLM yerelde                                     |
| NFR-12 | Test edilebilirlik  | Testcontainers; LLM stub ile değiştirilebilir                          |
| NFR-13 | Kurulum             | Tek komut: `docker compose up`                                         |
| NFR-14 | Dil                 | Türkçe fatura (karakter, TL, virgüllü ondalık); çok dilli embedding    |

### Kapsam dışı

Taranmış PDF için OCR; e-Fatura/UBL ve GİB entegrasyonu; raporlama, dashboard ve ön yüz (API + Swagger ve basit durum sayfası yeterli); çok kiracılı yapı, SSO/LDAP; bulut ve Kubernetes; kur çevrimi. Bunlar README'de "sonraki adımlar" olarak durur.

### Varsayımlar

- Faturalar metin tabanlı PDF, tek fatura = tek dosya, çoğunlukla Türkçe ve TL.
- Tedarikçi VKN ile tanınır; birden fazla sözleşmesi olabilir, fatura tarihinde geçerli olan esas alınır.
- Portal fatura no ile aranabilir; çift kayıt önleme bu aramaya dayanır.
- Test verisi sentetik fatura ve sözleşme PDF'leridir.

**Bilinen kısıt (v1):** Ön arama v2'de geldiği için RPA giriş sırasında çöker ve mesaj yeniden işlenirse çift kayıt oluşabilir. Tek RPA consumer eşzamanlı çift girişi engeller; "en fazla bir kez" garantisi v2'de tamamlanır.

### v1 kabul kriterleri

- [ ] 10 sentetik faturanın en az 8'i insan müdahalesi olmadan `POSTED` olur. **Karşılanmadı (B-29):** gerçek modelle (`qwen2.5:7b-instruct`, prompt v3) 7/10; sahte LLM ile 8/10 (`EndToEndSystemTest`; B-39'dan beri 7 `POSTED` + 1 `PENDING_APPROVAL`: 3. fatura 1.534.908 TL ile tutar eşiğinin üstünde, onaya düşmesi doğru sonuçtur ve insansız sayılır). Yanlış çıkarılan fatura kurallarla yakalanır, portala yanlış veri gitmez; kalan hata türü bilinen kısıttır (B-29, B-32).
- [x] Aynı PDF'in ikinci yüklemesi yeni kayıt ve portal girişi oluşturmaz. (B-28, `EndToEndSystemTest`)
- [x] Toplamları tutmayan fatura `NEEDS_REVIEW`'a düşer, portala girilmez. (B-28, `EndToEndSystemTest`)
- [x] `docker compose up` + Ollama ile temiz makinede sistem ayağa kalkar. (B-32: temiz kopya + sıfır volume ile denendi; gerçek temiz makinede ilk derleme süresi ölçülmedi.)
- [x] README'de mimari şeması, demo adımları ve v2 yol haritası var. (B-32)
- [x] Portalda sürekli hata veren fatura teslim limitinden sonra DLQ'ya düşer, arkasındakileri engellemez. (B-28, `MessagingFaultsSystemTest`)
- [x] Mesaj yayını sırasında öldürülen servis yeniden başladığında hiçbir fatura ara durumda takılı kalmaz. (B-28, `CrashRecoverySystemTest`)

## 3. Mimari

![Mimari şeması](/docs/architecture.svg)

Servisler birbirini hiç çağırmaz: her biri yalnızca RabbitMQ'ya ve kendi veritabanına bağlıdır; dört veritabanı tek Postgres konteynerindedir. Redis yalnızca koordinasyon içindir: extraction ve compliance için LLM semaforu (v1), rpa için kilit ve oturum (v2).

### Mutlu yol (US-01)

Her servis aynı ritmi izler: mesajı al, inbox'a bak, işi yap, sonucu ve inbox satırını tek transaction'da yaz, ack et; yayını outbox relay yapar.

1. Uzman PDF'i yükler; document-service tek transaction'da `RECEIVED` + geçiş + `outbox(ExtractInvoice)` yazar, 202 döner.
2. extraction-service metni çıkarır, semafor izniyle LLM'e JSON ürettirir, kuralları ve güven skorunu hesaplar, `ExtractionCompleted` yayınlar.
3. document-service `RECEIVED → EXTRACTED → VALIDATED` uygular, `CheckCompliance` yayınlar.
4. compliance-service (v1 stub) uyumlu sonuçla `ComplianceCompleted` yayınlar.
5. document-service `COMPLIANCE_CHECKED → QUEUED_FOR_RPA` uygular, `PostToPortal` yayınlar.
6. rpa-service portala login olur, formu girer, kayıt numarasını alır, `RpaCompleted` yayınlar.
7. document-service kaydı `POSTED` yapar ve portal kayıt numarasını saklar.

### RPA giriş sırasında çöker (US-08, v2)

1. rpa-service kilidi alır, `portal_submissions` = `IN_PROGRESS` yazar, ön arama yapar (kayıt yok), formu gönderir.
2. Sonucu yazamadan ölür: transaction commit edilmez, ack gitmez, kilidin kirası dolar.
3. Mesaj yeniden teslim edilir; bot kilidi alır, ön aramada kaydı bulur.
4. `FOUND_EXISTING` + `RpaCompleted(foundExisting = true)` yazılır; form yeniden doldurulmaz, kayıt `POSTED` olur.

### Portal sürekli hata verir, yönetici yeniden işletir (US-09, US-10)

1. rpa-service geçici hatada mesajı bekleme odasına erteler (30 sn, aynı kimlik), `invoice.rpa.max-attempts` (4) dolunca reddeder; mesaj `rpa.post-to-portal.dlq`'ya düşer (B-37).
2. document-service DLQ dinleyicisi mesajı `dead_letters`'a park eder, kaydı `RPA_FAILED` yapar.
3. Portal düzelince yönetici `POST /api/v1/admin/dead-letters/{id}/reprocess` çağırır; `RPA_FAILED → QUEUED_FOR_RPA` geçişi ve yeni `PostToPortal` yazılır, bu komut bekleyen komut olur (B-38). rpa-service'te yeni komut yeni tur başlatır, kendi deneme hakkıyla.
4. rpa-service kilit + ön arama + giriş yapar; kayıt `POSTED` olur.

## 4. Servisler

Sınırlar değişme nedenine göre çizildi: iş akışı kuralları, LLM ile çıkarım, sözleşme bilgisi ve portal otomasyonu birbirinden bağımsız değişir; her servis tek bir "zor şey"in sahibidir. Her serviste aynı `outbox` ve `inbox` tabloları vardır (bölüm 5). DDL blokları tasarımdaki kolonlardan türetilmiş taslaktır; tipler ilk migration'da kesinleşir.

### 4.1 document-service (orkestratör)

**Sorumluluk:** faturanın yaşam döngüsü. Yükleme, hash ile tekrar kontrolü, durum makinesi, mükerrer ve eşik kararları, onay/ret, DLQ'ların kayda yansıması. Güven eşiği karşılaştırması buradadır: extraction-service yalnızca skoru ölçer, kararı document-service verir. Dış bağımlılığı yoktur.

#### REST API

| Metot ve yol                                                                                                           | Ne yapar                                                                                                                                                                                                                                                                                                                                                                                                                                             | Yanıt                                                                                                                                                                                                | Sürüm     |
| ---------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| `POST /api/v1/documents` (multipart, `file` parçası, ≤ 20 MB)                                                          | PDF'i alır, SHA-256 hesaplar; yeni ise kayıt + `ExtractInvoice` outbox satırı tek transaction'da                                                                                                                                                                                                                                                                                                                                                     | 202 `{documentId, status: RECEIVED, duplicate: false}` + `Location`; hash biliniyorsa 200 `{documentId, status, duplicate: true}`; PDF değil 415, sınır aşımı 413, parça eksik 400 (`ProblemDetail`) | v1        |
| `GET /api/v1/documents/{id}`                                                                                           | Durum, `documents` kolonları, `fields` + `ruleResults` (`invoice_data`), `compliance` (`compliance_results`), portal kayıt no; oluşmamış kısımlar `null`. Sürüm `ETag`'de (`"3"`, B-40)                                                                                                                                                                                                                                                              | 200; bulunamazsa 404, kimlik UUID değilse 400                                                                                                                                                        | v1        |
| `GET /api/v1/documents?status=&vkn=&from=&to=&page=&size=`                                                             | Filtreli, sayfalı özet liste; `from`/`to` fatura tarihine, uçlar dahil; `vkn` tam eşleşme; `page` 0'dan, `size` 20 (≤ 100); `created_at DESC`                                                                                                                                                                                                                                                                                                        | 200 `{content, page, size, totalElements, totalPages}`; geçersiz parametre 400                                                                                                                       | v1        |
| `GET /api/v1/documents/{id}/history`                                                                                   | Durum geçmişi (US-12, B-42): `status_transitions` satırları eskiden yeniye (`id` sırası), her biri `{from, to, event, actor, reason, messageId, at}`; yükleme satırında `from` null, `messageId` yalnız mesajla tetiklenen geçişte dolu. Geçmiş yalnız eklenir (NFR-05)                                                                                                                                                                              | 200; geçişsiz kayıtta boş liste; kayıt yoksa 404                                                                                                                                                     | v2 (B-42) |
| `PUT /api/v1/documents/{id}/fields`                                                                                    | `NEEDS_REVIEW` kaydında alan düzeltme (B-40): tam `InvoiceFields` eskisinin yerine geçer; `If-Match: "<version>"` zorunlu. Hata sırası 404 → 409 (durum) → 412 (sürüm) → 422 (kurallar, ihlaller `violations`'da `ruleResults` biçiminde). Yanıt güncel kayıt + yeni `ETag`                                                                                                                                                                          | 200; `If-Match` yoksa 428, biçimi bozuksa 400, sürüm uymazsa 412, durum uymazsa 409, kurallar geçmezse 422                                                                                           | v2 (B-40) |
| `POST /api/v1/documents/{id}/approve` · `/reject`                                                                      | Onay (`PENDING_APPROVAL`) veya gerekçeli ret (`NEEDS_REVIEW` ya da `PENDING_APPROVAL`; gövde `{reason}`, boş gerekçe 400). `If-Match` istenmez: `PENDING_APPROVAL`'da alanlar değişemez. Yanıt güncel kayıt + `ETag`. Rol: onay `APPROVER`, ret kaydın durumuna göre `EXPERT` (`NEEDS_REVIEW`) ya da `APPROVER` (`PENDING_APPROVAL`), yoksa 403; onaycı kendi yüklediğini onaylayamaz (403, B-43)                                                    | 200; geçersiz durumda 409, yoksa 404                                                                                                                                                                 | v2 (B-40) |
| `POST /api/v1/documents/{id}/duplicate-decision`                                                                       | Mükerrer şüphesini kapatır (B-41, US-03): gövde `{duplicate, reason}`; `true` → `REJECTED` (gerekçe zorunlu), `false` → `VALIDATED` + `CheckCompliance` (gerekçe isteğe bağlı). Eşleşen kayıtlar `GET /documents/{id}` yanıtında `duplicateOf`'ta (yalnız `DUPLICATE_SUSPECTED` kayıtta dolu). Yanıt güncel kayıt + `ETag`                                                                                                                           | 200; `duplicate` yoksa ya da mükerrer kararında gerekçe boşsa 400, durum uymazsa 409, yoksa 404                                                                                                      | v2 (B-41) |
| `GET /api/v1/admin/dead-letters[?status=&page=&size=]` · `GET …/{id}` · `POST …/{id}/reprocess` · `POST …/{id}/ignore` | DLQ park tablosu ve yeniden işletme (FR-A1, B-38): liste yeniden eskiye, detayda gövde ve `x-death` JSON. `reprocess` yalnız `OPEN`, `DLQ`, `PostToPortal` kaydında ve belge `RPA_FAILED` iken: tek transaction'da `RPA_FAILED → QUEUED_FOR_RPA`, yeni `PostToPortal` (bekleyen komut olur), kayıt `REPROCESSED`. `ignore`: `OPEN → IGNORED`. Yalnız `ADMIN` (B-43); aktör yöneticinin adı                                                           | 200 `{deadLetterId, documentId, commandMessageId}` · 204; durum uymazsa 409, yoksa 404                                                                                                               | v2 (B-38) |
| `GET /api/v1/admin/settings` · `PUT /api/v1/admin/settings`                                                            | Güven ve tutar eşikleri (FR-A2, B-39): `{confidenceThreshold, approvalAmountThreshold}`, string. `PUT` ikisini ya da birini alır, verilmeyen değişmez. Güven 0–1 (≤ 4 ondalık), tutar ≥ 0 (≤ 2 ondalık). Tek transaction'da `settings` + gerçek her değişiklik için `settings_changes` satırı (aynı değer iz bırakmaz); commit'ten sonra örneğin önbelleği boşalır, diğer örneklerde en geç TTL (5 sn). Yalnız `ADMIN` (B-43); aktör yöneticinin adı | 200 güncel değerler; geçersiz gövde 400, hiçbir şey yazılmaz                                                                                                                                         | v2 (B-39) |

**Yetki (B-43, NFR-10, ADR-22):** HTTP Basic, durumsuz, CSRF kapalı; kimliksiz istek 401, rolü yetmeyen 403. Uç bazlı kurallar `SecurityConfiguration`'da: `/actuator/health` ve `/error` açık; `/api/v1/admin/**` `ADMIN`; yükleme, alan düzeltme ve mükerrer kararı `EXPERT`; onay `APPROVER`; ret `EXPERT` ya da `APPROVER`; `GET /api/v1/documents…` giriş yapmış herkes; tanımsız her şey reddedilir. Durum bazlı kurallar servistedir (hata sırası 404 → 409 → 403): ret `NEEDS_REVIEW`'da uzman, `PENDING_APPROVAL`'da onaycı ister; onaycı `uploaded_by`'ı kendisi olan kaydı onaylayamaz, reddedebilir. Geçişlerin ve ayar izinin aktörü, `uploaded_by` kullanıcı adıdır. Kullanıcılar `invoice.document.security.users`'ta (`application.yml`): `expert` (EXPERT), `approver` (APPROVER), `admin` (ADMIN), `expert-approver` (EXPERT + APPROVER; yasak ancak iki rollü kullanıcıyla görülür). Şifreler `.env`'den (`API_*_PASSWORD`), açılışta BCrypt'lenir; biri boşsa servis açılmaz, `toString` maskeler.

#### Yükleme isteğinin içi

1. İlk baytlar `%PDF-` değilse 415 döner, dosya yazılmaz (content-type'a güvenilmez). Dosya depolama dizinindeki `.staging/` altına (aynı dosya sistemi, atomik taşıma için) akış halinde yazılırken `DigestInputStream` ile SHA-256 hesaplanır; bellek dosya boyutundan bağımsızdır.
2. `INSERT INTO documents … ON CONFLICT (file_sha256) DO NOTHING RETURNING id` çalışır. Satır dönmezse mevcut kayıt okunur, 200 `duplicate: true` döner. Eşzamanlı iki yüklemeyi unique kısıt çözer; Redis kilidi gerekmez.
3. Yeni kayıtta aynı transaction'da `status_transitions` ve `outbox` (`ExtractInvoice`) yazılır, dosya `/data/documents/{sha256}.pdf` yoluna taşınır.
4. Commit olursa 202 döner. Commit öncesi çökmede yalnızca sahipsiz dosya kalır; gece çalışan temizlik işi kaydı olmayan dosyaları siler.

API JSON'unda `BigDecimal` her yerde string yazılır (`grandTotal`, `confidenceScore`, `fields` içindekiler; B-13). Hatalar `ProblemDetail` (RFC 9457) biçimindedir.

Ayrıntılar (B-10): taşıma commit'ten öncedir, çünkü ters sırada çökme `ExtractInvoice`'ı olmayan dosyaya yönlendirirdi. İlk geçiş `— → RECEIVED`, `trigger_event = UPLOAD`; aktör ve `uploaded_by` B-43'ten beri yükleyen kullanıcının adıdır (öncesinde `actor = api`, `uploaded_by` boş). Sınırı aşan gövdeyi Tomcat okuyup atar ki istemci 413 alsın (`server.tomcat.max-swallow-size: 100MB`; varsayılan 2 MB aşılınca bağlantı yanıtsız kesiliyordu). Veri erişimi `JdbcClient` ile (ADR-21).

#### Durum makinesi

Ana akış `RECEIVED → EXTRACTED → VALIDATED → COMPLIANCE_CHECKED → QUEUED_FOR_RPA → POSTED`; yan dallar `NEEDS_REVIEW`, `PENDING_APPROVAL`, `REJECTED`, `DUPLICATE_SUSPECTED`, `RPA_FAILED`. `POSTED` ve `REJECTED` son durumlardır. Tabloda olmayan her geçiş reddedilir (FR-D4).

**Öncelik kuralı:** `ExtractionCompleted` alındığında sırayla `NEEDS_REVIEW` > `DUPLICATE_SUSPECTED` > `VALIDATED` değerlendirilir. Güven düşükse VKN ve fatura no da güvenilmezdir, mükerrer kontrolü yapılmaz. `EXTRACTED` ve `VALIDATED` aynı transaction'da ardışık uygulanır, `status_transitions`'a iki ayrı satır düşer. **Mükerrer kontrolü (B-41, FR-D8, `DuplicateCheck`):** eşleşme = kırpılmış VKN ve kırpılmış, büyük harfli fatura no aynı, kendisi hariç `REJECTED` olmayan herhangi bir kayıt (`POSTED`, işlemdeki, `NEEDS_REVIEW`, başka bir `DUPLICATE_SUSPECTED`); reddedilen belge yeniden yüklenebilir. VKN veya fatura no yoksa kontrol yapılmaz. Aynı faturanın eşzamanlı iki belgesi kendi transaction'larında birbirini göremezdi; kontrol öncesi `pg_advisory_xact_lock(hashtextextended(anahtar, 0))` alınır, ikinci belge birincinin commit'ini bekler ve onu görür (yalnız aynı anahtar bekler). Gerekçe `mükerrer şüphesi: <id> (<durum>), …`. Uzman düzeltmesinden sonra da uygulanır (VKN veya fatura no değişmiş olabilir). Uzman "mükerrer değil" derse ve portalda kayıt varsa ön arama onu bulur (`FOUND_EXISTING`), portala ikinci giriş olmaz.

| Kaynak                | Hedef                             | Tetikleyen                                                                                                     | Üreten           | Sürüm     |
| --------------------- | --------------------------------- | -------------------------------------------------------------------------------------------------------------- | ---------------- | --------- |
| —                     | `RECEIVED`                        | PDF yüklendi, hash yeni                                                                                        | Uzman (API)      | v1        |
| `RECEIVED`            | `EXTRACTED`                       | `ExtractionCompleted`                                                                                          | extraction       | v1        |
| `RECEIVED`            | `NEEDS_REVIEW`                    | `ExtractionFailed` veya `ExtractInvoice` DLQ'da                                                                | extraction / DLQ | v1        |
| `EXTRACTED`           | `NEEDS_REVIEW`                    | Güven skoru < eşik (öncelik 1)                                                                                 | document         | v1        |
| `EXTRACTED`           | `DUPLICATE_SUSPECTED`             | VKN + fatura no başka kayıtla eşleşti (öncelik 2; uzman düzeltmesinden sonra da)                               | document         | v2 (B-41) |
| `EXTRACTED`           | `VALIDATED`                       | Skor ≥ eşik, mükerrer yok                                                                                      | document         | v1        |
| `NEEDS_REVIEW`        | `EXTRACTED`                       | Uzman alanları düzeltti (`If-Match`), ardından `EXTRACTED → VALIDATED` + `CheckCompliance`                     | Uzman (API)      | v2 (B-40) |
| `NEEDS_REVIEW`        | `REJECTED`                        | Uzman reddetti (gerekçe zorunlu)                                                                               | Uzman (API)      | v2 (B-40) |
| `DUPLICATE_SUSPECTED` | `VALIDATED` / `REJECTED`          | Uzman mükerrer değil (+ `CheckCompliance`) / mükerrer dedi (gerekçe zorunlu)                                   | Uzman (API)      | v2 (B-41) |
| `VALIDATED`           | `COMPLIANCE_CHECKED`              | `ComplianceCompleted`: uyumlu ve tutar ≤ eşik (v1 stub hep uyumlu)                                             | compliance       | v1        |
| `VALIDATED`           | `PENDING_APPROVAL`                | `ComplianceCompleted`: uyumlu ama tutar > eşik veya para birimi TRY değil                                      | document         | v2 (B-39) |
| `VALIDATED`           | `PENDING_APPROVAL`                | `ComplianceCompleted`: `NON_COMPLIANT`, `NO_CONTRACT` ya da `CONTRACT_CONFLICT` (gerekçe: sonuç + bulgu özeti) | compliance       | v2 (B-46) |
| `VALIDATED`           | `PENDING_APPROVAL`                | `CheckCompliance` DLQ'da                                                                                       | DLQ              | v1        |
| `PENDING_APPROVAL`    | `COMPLIANCE_CHECKED` / `REJECTED` | Onaycı onayladı (ardından `QUEUED_FOR_RPA` + `PostToPortal`) / reddetti                                        | Onaycı (API)     | v2 (B-40) |
| `COMPLIANCE_CHECKED`  | `QUEUED_FOR_RPA`                  | Durum + `PostToPortal` aynı transaction'da outbox'a                                                            | document         | v1        |
| `QUEUED_FOR_RPA`      | `POSTED`                          | `RpaCompleted` (ön aramada bulunan dahil)                                                                      | rpa              | v1        |
| `QUEUED_FOR_RPA`      | `RPA_FAILED`                      | `PostToPortal` DLQ'da                                                                                          | DLQ              | v1        |
| `RPA_FAILED`          | `QUEUED_FOR_RPA`                  | Yönetici yeniden işletti, yeni `PostToPortal`                                                                  | Yönetici (API)   | v2        |

DLQ'dan mesajı doğrudan kuyruğa geri taşımak geçerli bir geçiş değildir; yeniden işleme yalnızca FR-A1 üzerinden, yeni komutla yapılır.

**Uygulama (B-11):** tablo kodda, `TransitionTable`'dadır (v1 ve v2 satırlarının tamamı; v2 satırlarını tetikleyen kod v2'de gelir). Durumu değiştirmenin tek yolu `StatusTransitions.apply(documentId, from, to, trigger)`'dır ve yalnızca transaction içinde çağrılır:

1. Çift tabloda yoksa `IllegalTransitionException` (programlama hatası; mesaj geri konur, limitten sonra DLQ).
2. `UPDATE documents SET status = :to, version = version + 1, updated_at = now() WHERE id = :id AND status = :from`.
3. 1 satır → `status_transitions`'a yazılır, `APPLIED`. 0 satır → `STALE`; tetikleyici mesajsa olay `dead_letters`'a `LATE_EVENT` / `OPEN` olarak yazılır ve uyarı loglanır (bilinmeyen `documentId` de böyle kaydedilir); API tetikleyicisinde (v2) kayıt yazılmaz, çağıran 409 verir.

**Olay işleme (B-12):** üç olay kuyruğu `on(...)` (kısa iş) ile dinlenir; inbox ve tek transaction dinleyicide, geç olay kaydı durum makinesindedir. Prefetch ve eşzamanlılık `invoice.document.listener.{prefetch, concurrency}` (varsayılan 10 · 2).

- `ExtractionCompleted`: `RECEIVED → EXTRACTED` (aktör `extraction-service`; `STALE` ise durur). Alanlar `documents`'a (VKN, fatura no, tarih, genel toplam, para birimi, skor) ve `invoice_data`'ya (`fields`, `rule_results`, `source = LLM`) yazılır; güveni düşük kayıtta da. Sonra aynı transaction'da öncelik kuralıyla: `skor < eşik` → `NEEDS_REVIEW`; değilse mükerrer varsa `DUPLICATE_SUSPECTED` (komut yok); değilse `VALIDATED` + `CheckCompliance` (aktör `document-service`, gerekçe örn. `güven 0.62 < eşik 0.80`). Skor veya alanlar yoksa `NEEDS_REVIEW`. Mükerrer kontrolü B-41'de geldi.
- `ExtractionFailed`: `RECEIVED → NEEDS_REVIEW`, gerekçe `reason: detail`.
- `ComplianceCompleted`: `COMPLIANT` ise önce onay kararı (FR-D11, B-39): para birimi `TRY` değilse (kur çevrimi yok) ya da `grandTotal > approval_amount_threshold` ise (eşik dahil değil) `VALIDATED → PENDING_APPROVAL`, `compliance_results` yazılır, komut yazılmaz; onaycı karar verir (B-40). Aksi halde `VALIDATED → COMPLIANCE_CHECKED`, `compliance_results`, ardından `COMPLIANCE_CHECKED → QUEUED_FOR_RPA` + `PostToPortal`. Her iki yol tek transaction'da. Kararı document-service verir (FR-C4): geçişin aktörü `document-service`, gerekçe `tutar X > eşik Y` / `tutar X ≤ eşik Y` / `para birimi USD ≠ TRY`; tutar veya para birimi yoksa onaya. `NON_COMPLIANT`, `NO_CONTRACT`, `CONTRACT_CONFLICT` (B-46, US-06, US-07): `VALIDATED → PENDING_APPROVAL`, aktör `compliance-service`, gerekçe örn. `NON_COMPLIANT: UNIT_PRICE (Madde 4.1), PAYMENT_TERM güvenilmez`; bulgular `compliance_results`'a, komut yazılmaz; onaycı bulguları `GET /documents/{id}` → `compliance`'ta görür. Tutar eşiği yalnız uyumlu faturaya uygulanır.
- İnsan adımları (B-40, `DocumentReview`), her biri tek transaction'da. **Düzeltme:** `NEEDS_REVIEW → EXTRACTED` (aktör `expert`, koşulda `If-Match` sürümü), `invoice_data` (`source = EXPERT_CORRECTION`, kural sonuçlarıyla) ve `documents` arama kopyaları; güven skoru LLM'inki kalır. Sonra karar yeniden: mükerrer varsa `EXTRACTED → DUPLICATE_SUSPECTED` (B-41), yoksa `EXTRACTED → VALIDATED` + `CheckCompliance` (aktör `document-service`, gerekçe `uzman düzeltmesi, kurallar geçti`); güven eşiği uygulanmaz, insan girdisidir. Düzeltme yapısal kontrolden geçmezse hiçbir şey değişmez (422). **Onay:** `PENDING_APPROVAL → COMPLIANCE_CHECKED` (aktör `approver`) `→ QUEUED_FOR_RPA` + `PostToPortal` (`RpaDispatch`, bekleyen komut). **Ret:** `NEEDS_REVIEW` (aktör `expert`) veya `PENDING_APPROVAL` (aktör `approver`) `→ REJECTED`, gerekçe geçişin `reason`'ı. Aktör, isteği yapan kullanıcının adıdır (B-43; önceden sabit `expert` / `approver`). **Mükerrer kararı (B-41):** `DUPLICATE_SUSPECTED → VALIDATED` + `CheckCompliance` (tetikleyici `NOT_DUPLICATE`) ya da `→ REJECTED` (`DUPLICATE_CONFIRMED`, gerekçe zorunlu), aktör `expert`.
- `RpaCompleted`: `QUEUED_FOR_RPA → POSTED`, `portal_ref_no` yazılır.

**DLQ'lar (B-14, FR-D10):**

- Komut DLQ'ları (`extraction.extract-invoice.dlq` → `RECEIVED → NEEDS_REVIEW`, `compliance.check-compliance.dlq` → `VALIDATED → PENDING_APPROVAL`, `rpa.post-to-portal.dlq` → `QUEUED_FOR_RPA → RPA_FAILED`) `common-messaging`'deki `DeadLetterListener` ile dinlenir (prefetch 1 · 1). Tek transaction'da: inbox (`consumer` = DLQ adı), `dead_letters` satırı (`kind = DLQ`, `status = OPEN`, `source_queue` = `x-first-death-queue`, gövde, `x_death`), durum geçişi (tetikleyici `DLQ:<Tip>`, aktör `dlq`), sonra ack. Kayıt beklenen durumda değilse yalnızca park edilir, ayrıca `LATE_EVENT` yazılmaz. Zehirli mesaj da park edilir (gövde geçerli JSON değilse `{"raw": …}`, tipi yoksa `UNKNOWN`), geçiş yapılmaz.
- Olay DLQ'ları (`document.*-events.dlq`) tüketilmez; mesajlar hata düzeltilince yeniden yayınlanabilsin diye kuyrukta kalır. `EventDeadLetterMonitor` derinliklerini `invoice.document.event-dlq-check-interval` (varsayılan 1 dk) aralıkla pasif `queue.declare` ile okur ve dolu olanı `ERROR` loglar (v1'de alarm = log; uygulama kullanıcısının `configure` izni olmadan da çalıştığı doğrulandı). Metrik B-48'de.

`CheckCompliance` ve `PostToPortal` gövdeleri `invoice_data.fields`'tan üretilir (resmi veri; tedarikçi adı, vade, KDV yalnızca orada). Eşik `settings`'ten okunur ve bellekte `invoice.document.settings-cache-ttl` (varsayılan 5 sn) tutulur. `invoice_data` JSON'u mesajlarla aynı dönüştürücü kurallarıyla yazılır, tutarlar orada da string'dir.

Olaylarda koruma yalnızca durum koşuludur: olaylar sürüm taşımaz, eşzamanlı ikinci güncellemeyi satır kilidi ve durum koşulu zaten durdurur. `version` her geçişte artar; isteğe bağlı `expectedVersion` v2'deki `If-Match` içindir. Alan güncellemeleri (VKN, tutar, portal no) durum makinesinde değil, geçiş uygulandıktan sonra aynı transaction'da çağıranda yapılır (B-12).

#### Veritabanı: document_db

Kesin şema `document-service/src/main/resources/db/migration/V1__document_schema.sql`'dedir (B-09). Tip kararları: kod kolonları `TEXT` + `CHECK`; LLM'den gelen değerlerin kopyaları (`supplier_vkn`, `currency`, `grand_total`) gevşek tiplidir, böylece kötü çıktı INSERT hatasıyla DLQ'ya düşmez, kaydedilip `NEEDS_REVIEW`'a gider ve tutar sessizce yuvarlanmaz (doğrulama FR-E3 kurallarında); `updated_at` ve `version` uygulamada koşullu `UPDATE` içinde güncellenir, trigger yoktur; `settings` yer tutucu başlangıç değerleriyle gelir (A5).

```sql
CREATE TABLE documents (
    id               UUID PRIMARY KEY,
    file_sha256      CHAR(64)    NOT NULL UNIQUE CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),   -- US-02
    storage_uri      TEXT        NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN (…on bir durum…)),
    supplier_vkn     TEXT,                                -- LLM çıktısı; 10 hane kuralı FR-E3'te
    invoice_no       TEXT,
    invoice_date     DATE,
    grand_total      NUMERIC,                             -- ölçeksiz: yuvarlama yok
    currency         TEXT,
    confidence_score NUMERIC(5,4),
    portal_ref_no    TEXT,
    uploaded_by      TEXT,                                -- onaycı kendi yüklediğini onaylayamaz
    version          INT         NOT NULL DEFAULT 0,      -- If-Match, koşullu geçiş
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX documents_vkn_invoice ON documents (supplier_vkn, invoice_no);  -- unique değil (US-03)
CREATE INDEX documents_duplicate_key ON documents (btrim(supplier_vkn), upper(btrim(invoice_no)));  -- V4, B-41: mükerrer eşleşme anahtarı
CREATE INDEX documents_status      ON documents (status, updated_at);        -- liste + takılı kayıt dedektörü

CREATE TABLE invoice_data (            -- faturanın "resmi" verisi
    document_id  UUID PRIMARY KEY REFERENCES documents(id),
    fields       JSONB NOT NULL,
    rule_results JSONB NOT NULL,
    source       TEXT  NOT NULL CHECK (source IN ('LLM', 'EXPERT_CORRECTION')),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE compliance_results (      -- onaycı ekranının kaynağı (US-06)
    document_id UUID PRIMARY KEY REFERENCES documents(id),
    result      TEXT  NOT NULL CHECK (result IN ('COMPLIANT', 'NON_COMPLIANT', 'NO_CONTRACT', 'CONTRACT_CONFLICT')),
    findings    JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE status_transitions (      -- append-only; V5 (B-42): UPDATE/DELETE/TRUNCATE yetkisi geri alındı + reddeden tetikleyiciler
    id            BIGSERIAL PRIMARY KEY,
    document_id   UUID NOT NULL REFERENCES documents(id),
    from_status   TEXT CHECK (from_status IN (…)),
    to_status     TEXT NOT NULL CHECK (to_status IN (…)),
    trigger_event TEXT NOT NULL,
    message_id    UUID,
    actor         TEXT NOT NULL,
    reason        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX status_transitions_document ON status_transitions (document_id, created_at);  -- FK + geçmiş (US-12)

CREATE TABLE dead_letters (            -- DLQ park tablosu + geç olaylar
    id           UUID PRIMARY KEY,
    kind         TEXT NOT NULL CHECK (kind IN ('DLQ', 'LATE_EVENT')),
    source_queue TEXT NOT NULL,
    message_type TEXT NOT NULL,
    message_id   UUID NOT NULL,
    document_id  UUID,
    body         JSONB NOT NULL,
    x_death      JSONB,
    status       TEXT NOT NULL CHECK (status IN ('OPEN', 'REPROCESSED', 'IGNORED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX dead_letters_status ON dead_letters (status, created_at);  -- yönetici listesi (FR-A1)

CREATE TABLE settings (                -- FR-A2; bellekte birkaç saniye önbelleklenir
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Yer tutucu değerler (A5): confidence_threshold = 0.80, approval_amount_threshold = 100000.00 (v2)

CREATE TABLE settings_changes (        -- B-39 (V3); yalnız eklenir, ayarlar API'sinin izi
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key        TEXT NOT NULL REFERENCES settings (key),
    old_value  TEXT NOT NULL,
    new_value  TEXT NOT NULL,
    actor      TEXT NOT NULL,          -- B-43'ten beri yöneticinin kullanıcı adı (öncesi sabit 'admin')
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Dosyalar: `documents` volume'u (`/data/documents/{sha256}.pdf`), içerik adresli ve değişmez.

**Geçmişin değiştirilemezliği (B-42, NFR-05):** `V5__status_transitions_append_only` iki katman kurar: (1) migration'ı çalıştıran kullanıcıdan (`document_user`, aynı zamanda uygulama kullanıcısı ve tablo sahibi) `status_transitions` üzerindeki UPDATE, DELETE, TRUNCATE yetkisi geri alınır; (2) yetki yanlışlıkla geri verilse de UPDATE/DELETE (satır) ve TRUNCATE (komut) tetikleyicileri `status_transitions yalnızca eklenir` hatası fırlatır (superuser'ı da durdurur). Tablo sahibi yetkiyi kendine geri verebilir ve tetikleyiciyi silebilir: koruma uygulama hatasına ve kazaya karşıdır, kötü niyetli DB sahibine karşı değil. Alternatif: Flyway için ayrı sahip rolü (`document_owner`) ve yalnız SELECT/INSERT yetkili uygulama kullanıcısı. Gerçek yetki ayrımı sağlardı, ama yeni secret, init betiği, compose ve Testcontainers değişikliği ile mevcut dev volume'larında elle sahiplik taşıması gerektirirdi ve yalnız document-service'te olurdu. `settings_changes` da bir denetim tablosudur, ama henüz korunmuyor; gerekirse aynı yöntemle eklenebilir.

#### Mesajlar

| Yön      | Mesaj                                     | Kuyruk / routing key                                                                                                                               |
| -------- | ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Yayınlar | `ExtractInvoice`                          | `invoice.commands` · `extract.invoice`                                                                                                             |
| Yayınlar | `CheckCompliance`                         | `invoice.commands` · `compliance.check`                                                                                                            |
| Yayınlar | `PostToPortal`                            | `invoice.commands` · `rpa.post`                                                                                                                    |
| Dinler   | `ExtractionCompleted`, `ExtractionFailed` | `document.extraction-events.q`                                                                                                                     |
| Dinler   | `ComplianceCompleted`                     | `document.compliance-events.q`                                                                                                                     |
| Dinler   | `RpaCompleted`                            | `document.rpa-events.q`                                                                                                                            |
| Dinler   | Komut DLQ'ları                            | `extraction.extract-invoice.dlq` → `NEEDS_REVIEW`; `compliance.check-compliance.dlq` → `PENDING_APPROVAL`; `rpa.post-to-portal.dlq` → `RPA_FAILED` |

#### Arka plan işleri

- **Outbox relay:** 500 ms'de bir yayınlanmamış satırları gönderir (bölüm 5).
- **Takılı kayıt dedektörü:** dakikada bir `RECEIVED`, `VALIDATED`, `QUEUED_FOR_RPA` durumunda eşikten uzun kalan kayıtları sayar (`updated_at`'e göre); v1'de yalnızca alarm üretir, metrik B-48'de. Eşikler durum başınadır (B-15): `RECEIVED` 30 dk (LLM sırası), `VALIDATED` 10 dk, `QUEUED_FOR_RPA` 30 dk (portal ve retry'lar); `invoice.document.stuck-detection.*`. Alarm her turda tek `ERROR` satırıdır: durum başına sayı ve en eski en fazla 10 kayıt. İnsan bekleyen durumlar takılı sayılmaz.
- **Inbox temizliği:** 30 günden eski satırları siler (`common-messaging`'deki `InboxCleaner`, saatte bir, 10.000'lik parçalar; `invoice.messaging.inbox.{retention, cleanup-interval, cleanup-batch-size}`, `cleanup.enabled`).
- **Outbox temizliği (A7):** yayınlanmış ve 7 günden eski satırları siler (`OutboxCleaner`, saatte bir, 10.000'lik parçalar; `invoice.messaging.outbox.{retention, cleanup-interval, cleanup-batch-size}`, `cleanup.enabled`). Yayınlanmamış satıra yaşı ne olursa olsun dokunmaz.
- **Sahipsiz dosya temizliği:** gece, kaydı olmayan PDF'leri siler. Cron `invoice.document.orphan-cleanup.cron` (varsayılan `0 0 3 * * *`, `@Scheduled`). Yalnızca 1 saatten (`grace-period`) eski dosyalara dokunur: dosya commit'ten önce yerine taşındığı için (B-10) commit olmak üzere olan kaydın dosyası silinmemeli. `.staging/` altındaki eski geçici dosyaları da siler; hash adlı olmayan dosyalara dokunmaz.

### 4.2 extraction-service

**Sorumluluk:** PDF'ten yapılandırılmış fatura verisi üretmek ve ne kadar güvenilir olduğunu söylemek. Karar vermez; yalnızca skoru ölçer. Dış bağımlılıklar: Ollama, dosya deposu (salt okunur), Redis (LLM semaforu).

**REST API:** yok (yalnızca Actuator).

#### İşleyiş

1. `ExtractInvoice` alınır; ucuz bir `inbox.exists` kontrolü yapılır, iş transaction dışında yürütülür.
2. PDFBox ile metin deterministik çıkarılır (FR-E1). Metin çıkmazsa `ExtractionFailed` (neden: metin yok). Ayrıntılar (B-17, `PdfTextExtractor`): `storageUri` yalnızca `file:` şemasında ve depo kökünün (`invoice.extraction.storage-dir`) içinde olabilir; dosyanın SHA-256'sı mesajdaki `fileSha256` ile karşılaştırılır; ikisi de tutmazsa kalıcı hata (`DocumentIntegrityException`). Dosya yoksa altyapı sorunu sayılır (`DocumentNotAvailableException`, mesaj geri konur, sürerse DLQ → `NEEDS_REVIEW`). Metin konuma göre sıralı çıkarılır (aynı yükseklikteki sol ve sağ bloklar tek satıra düşer), çok sayfalıda `--- Sayfa n ---` ile ayrılır, Unicode NFC ve boşluk sadeleştirmesi uygulanır. Boşluk dışı 20 karakterden azı (`min-text-chars`) ve açılamayan PDF (bozuk, şifreli) `NO_TEXT` olur; ayrım `detail` alanındadır, mesaj sözleşmesine yeni neden eklenmedi.
3. Redis `sem:llm` izni alınır; Spring AI üzerinden LLM'e şemaya zorlanmış JSON ürettirilir (FR-E2, FR-E5). Ayrıntılar (B-18): model `qwen2.5:7b-instruct` (`invoice.extraction.llm.model` ← `OLLAMA_CHAT_MODEL`, host'ta önceden çekilir, açılışta indirilmez); Ollama'ya `format` olarak elle yazılmış JSON şeması (`InvoiceSchema`), sıcaklık 0, prompt v1 (`PromptV1`; satıcı/alıcı ayrımı ve ETTN/IBAN/MERSİS'in VKN olmadığı açıkça yazılı; B-29'dan beri varsayılan v3, `num_ctx` 8192, `num_predict` 4096, şemada alan başına `maxLength`). LLM bütün değerleri faturada **basıldığı gibi** string döner (`"1.234,56 TL"`, `"18/09/2026"`, `"%20"`); tutarlar ve tarihler deterministik Türkçe ayrıştırıcılarla çevrilir (`TurkishNumbers`, `TurkishDates`), çözülemeyen değer `null` kalır ve kural doğrulamasında (B-20) yakalanır. KDV oranı alandaki ilk sayıdır (`parseRate`): model bazen satırın devamını bu alana yazıyor (`"1 %1 9.200,00 TL"`), genel ayrıştırıcı boşlukları sildiği için bunu 119200 okuyordu (gerçek Ollama ile duman testinde bulundu). Semafor Redisson `RPermitExpirableSemaphore`'dur: 2 izin, izin kirası LLM zaman aşımının iki katı (servis izni tutarken çökerse izin kendiliğinden döner); Redis'e ulaşılamazsa 1 izinli yerel semafora düşülür; izin `permit-wait` (10 dk) içinde alınamazsa mesaj geri konur. `RedissonClient` tembel başlatılır, Redis kapalıyken servis açılır.
4. Parse hatası veya zaman aşımında hata mesajı prompt'a eklenerek sınırlı yeniden deneme yapılır. Tükenirse `ExtractionFailed` yayınlanır ve mesaj ack edilir: bu bir iş sonucudur, DLQ değil (US-05). Ayrıntılar (B-19, `LlmExtraction`): en fazla 3 deneme (`llm.max-attempts`); bozuk çıktıda önceki ham yanıt ve hata sonraki prompt'a eklenir; zaman aşımı `spring.http.clients.read-timeout` = `llm.timeout` (120 sn) ile HTTP düzeyinde uygulanır, istek gerçekten kesilir; semafor izni deneme başınadır. **Spring AI'ın kendi yeniden denemesi kapalıdır** (`spring.ai.retry.max-attempts: 0`): değer ilk denemeye ek tekrar sayısıdır, varsayılan (10) ve 1 bile her denemeyi Ollama'ya iki kez gönderip zaman aşımını katlıyordu (sahte Ollama sunucusuyla ölçüldü). Hatalar neden zinciriyle sınıflandırılır (`LlmFailures`); Boot burada Reactor Netty istemcisini seçer, okuma zaman aşımı Netty'nin `ReadTimeoutException`'ıdır.
5. Çıktı kurallarla doğrulanır (FR-E3), kural sonuçlarından güven skoru hesaplanır (FR-E4).
6. Kısa bir transaction'da `extraction_runs` + `inbox.tryInsert` + `outbox(ExtractionCompleted)` yazılır, sonra ack.

#### Çıkarım şeması (FR-E2)

| Alan                                  | Açıklama                                          |
| ------------------------------------- | ------------------------------------------------- |
| `supplierName`, `supplierVkn`         | Tedarikçi adı, 10 haneli VKN                      |
| `invoiceNo`, `invoiceDate`, `dueDate` | Fatura no, tarih, vade                            |
| `lines[]`                             | `description`, `quantity`, `unitPrice`, `vatRate` |
| `subtotal`, `vatTotal`, `grandTotal`  | Ara toplam, KDV, genel toplam                     |
| `currency`                            | Para birimi (kur çevrimi yok)                     |

#### Doğrulama kuralları (FR-E3)

- Kalem toplamı = ara toplam (tolerans dahilinde).
- Ara toplam + KDV = genel toplam (tolerans dahilinde).
- VKN 10 hane.
- Fatura tarihi ≤ vade.
- Zorunlu alanlar dolu.
- Türkçe sayı biçimi (virgüllü ondalık, TL) doğru ayrıştırılır (NFR-14).

Hangi ihlalin skoru ne kadar düşürdüğü ve eşiğin başlangıç değeri açık karardır (açık karar A5).

**Uygulama (B-20, `InvoiceValidator`, `ConfidenceScore`):** her kural bir `RuleResult(rule, passed, detail)` üretir; açıklamalar uzmanın okuyacağı Türkçe metinlerdir (örn. `kalem toplamı 15.100,00 ≠ ara toplam 15.400,00 (fark 300,00, tolerans 0,02)`), `invoice_data.rule_results` ve `GET /documents/{id}` ile görünür. Girdi LLM'in ham çıktısı ve ayrıştırılmış alanlardır.

| Kod                               | Kontrol                                                                                          | Ağırlık |
| --------------------------------- | ------------------------------------------------------------------------------------------------ | ------- |
| `REQUIRED_FIELDS_PRESENT`         | tedarikçi adı, VKN, fatura no, tarih, vade, ≥1 kalem, ara toplam, KDV, genel toplam, para birimi | 0,50    |
| `VKN_10_DIGITS`                   | tam 10 hane                                                                                      | 0,30    |
| `LINES_SUM_EQUALS_SUBTOTAL`       | Σ(miktar × birim fiyat) ≈ ara toplam; tolerans max(0,02; 0,01 × kalem sayısı)                    | 0,40    |
| `SUBTOTAL_PLUS_VAT_EQUALS_TOTAL`  | ara toplam + KDV ≈ genel toplam; tolerans 0,02                                                   | 0,40    |
| `AMOUNTS_PARSED`                  | LLM'in dolu döndürdüğü her tutar, tarih ve oran Türkçe ayrıştırıcıdan geçti (NFR-14)             | 0,30    |
| `INVOICE_DATE_NOT_AFTER_DUE_DATE` | fatura tarihi ≤ vade (hafif)                                                                     | 0,15    |
| `VAT_MATCHES_LINES`               | Σ(kalem net × oran) ≈ KDV toplamı, kalem toleransıyla (FR-E3'e ek, hafif)                        | 0,15    |

Güven skoru = 1,00 − kalan kuralların ağırlıkları, alt sınır 0, iki ondalık. Kritik kurallar tek başına skoru varsayılan eşiğin (0,80) altına iter, hafif kurallardan biri itmez, ikisi iter. Eksik değer yüzünden hesaplanamayan kural kalır. Toleranslar ve ağırlıklar `invoice.extraction.validation.*` ile ayarlanır (NFR-09); B-16 setinde stub LLM ile 1–8 skor 1,00, 9. fatura 0,45 alır.

**Uzman düzeltmesinin kontrolü (B-40, `CorrectionValidator`, document-service):** yukarıdaki kuralların LLM'den bağımsız olanları, aynı kodlar ve varsayılan toleranslarla (`AMOUNTS_PARSED` hariç; değerler JSON'dan tipli gelir), ek olarak `LINES_VALID` (her kalemde açıklama, miktar > 0, birim fiyat ≥ 0, KDV oranı 0–100). Skor yoktur: ya hepsi geçer ya düzeltme 422 ile reddedilir. Bedeli: aritmetik ve toleranslar iki serviste (`invoice.document.correction.*`, varsayılanlar `invoice.extraction.validation.*` ile aynı); servisler arası senkron çağrı olmadığı için (ADR-15) bilinçli tekrar. Alternatifler: uzmana tam güven (tutarsız düzeltme portala gidebilirdi), kuralları ortak modüle taşımak (yeni modül ve extraction-service refaktörü).

#### Sentetik test seti (B-16)

`extraction-service/src/test/resources/invoices/` altında 10 fatura PDF'i ve her birinin `invoice-XX.expected.json`'u (basılı alanlar `InvoiceFields` biçiminde, tutarlar string; beklenen sonuç; not). Kabul kriterine göre 8 temiz + 2 bilerek bozuk: 9. faturada kalem toplamı basılı ara toplamı tutmaz (`NEEDS_REVIEW`), 10. fatura yalnızca resimdir (`NO_TEXT`). Temizler sırasıyla: tek kalem; karışık KDV oranları (%1/%10/%20, oran başına KDV satırları); binlik ayırıcılı büyük tutarlar; `₺` ve TL karışık; Türkçe karakter yoğun metin; e-Arşiv gürültüsü (ETTN, vergi dairesi, IBAN, MERSİS); 2 sayfa (kalemler taşar); `gg/aa/yyyy` tarih. Firmalar ve VKN'ler kurgusal; alıcı bloğunda ayrı bir VKN vardır (tedarikçiyle karışmamalı).

PDF'ler katalogdan (`SyntheticInvoices`) PDFBox ile üretilip commit edilir; üretme komutu `SyntheticInvoiceGenerator` Javadoc'unda. Türkçe karakterler için DejaVu Sans gömülür (`src/test/resources/fonts`, lisansı yanında). Tablo çizgileri gerçek faturalardaki gibi grafik olarak çizilir; tire karakterleriyle çizilen çizgi konuma göre sıralı metin çıkarımında başlığa karışıyordu (B-17'de bulundu). `SyntheticInvoiceSetTest` commit edilmiş dosyaların katalogla aynı olduğunu (yeniden üretmeyi unutmamak için), toplamların tutarlılığını ve metin katmanını doğrular. PDFBox sürümü kök pom'da sabittir (`pdfbox.version`, Boot yönetmez).

#### Veritabanı: extraction_db

```sql
CREATE TABLE extraction_runs (         -- FR-E6: LLM'in neyi neden yanlış çıkardığını açıklamak için
    id             BIGSERIAL PRIMARY KEY,
    document_id    UUID        NOT NULL,
    attempt_no     INT         NOT NULL,
    model          TEXT        NOT NULL,
    prompt_version TEXT        NOT NULL,
    raw_output     TEXT,
    parse_error    TEXT,
    duration_ms    INT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, attempt_no)
);
-- + outbox, inbox (ortak desen)
```

Kesin şema `extraction-service/src/main/resources/db/migration/V1__extraction_schema.sql`'dedir (B-21). Her LLM denemesi bir satırdır: model, prompt sürümü, süre, ham yanıt (`raw_output`; zaman aşımında boş) ve hata (`parse_error`: bozuk çıktı veya zaman aşımı; başarılıda boş). Satırlar sonuçla (`ExtractionCompleted`/`ExtractionFailed`) aynı transaction'da, inbox kaydıyla birlikte yazılır; tekrar teslimde bir kez yazılır, ama denemeler sırasında çöken çalışmanın denemeleri kaydedilmez. `NO_TEXT` ve Ollama'ya ulaşılamaması (A1) satır üretmez. Ham çıktı v1'de yazılır (B-29 kalibrasyonu için; B-47'nin kayıt kısmı böylece öne alındı).

**İnceleme ve saklama (B-47, FR-E6):** `GET /api/v1/extraction-runs?documentId=` (extraction-service, compose'da 8083) belgenin bütün denemelerini sırayla döner: `attemptNo`, `model`, `promptVersion`, `rawOutput`, `parseError`, `durationMs`, `createdAt`, `rawOutputPurgedAt`. Kayıt yoksa boş liste (belge bu serviste bilinmez; 404 değil); `documentId` eksik ya da UUID değilse 400. Yetki ADR-22: `ADMIN` ve `EXPERT` okur (uzman düşük güvenli kaydı incelerken modelin ne çıkardığını görür), onaycıya kapalı (ham çıktı fatura metnini içerir). Saklama: `RawOutputRetention` gece (`invoice.extraction.retention.cron`, varsayılan 03:30) `invoice.extraction.retention.raw-output`'tan (90 gün) eski denemelerin yalnız `raw_output`'unu siler ve `raw_output_purged_at` yazar (`V2__raw_output_retention`); model, prompt sürümü, süre ve hata kalır. Tek koşullu `UPDATE`, çok örnekte de güvenli. Alternatifler: API'yi document-service'e taşımak (olaya ham çıktı eklenirdi, mesaj sözleşmesi değişir, veri iki yerde), satırı tamamen silmek (kalibrasyon istatistiği kaybolur).

Testlerde LLM `StubChatModel`'dir (test kaynakları, NFR-12): Spring AI `ChatModel`'ini uygular, varsayılanı sentetik faturanın basılı değerlerini döndüren iyi bir LLM'dir; bozuk çıktı, zaman aşımı ve bağlantı hatası Reactor Netty'nin gerçek istisna biçimleriyle sıralanabilir. HTTP düzeyini `FakeOllamaServer` (Ollama API taklidi) sınar. Production'da stub yoktur.

#### Mesajlar

| Yön      | Mesaj                 | Kuyruk / routing key                                        |
| -------- | --------------------- | ----------------------------------------------------------- |
| Dinler   | `ExtractInvoice`      | `extraction.extract-invoice.q` (prefetch 1, eşzamanlılık 1) |
| Yayınlar | `ExtractionCompleted` | `invoice.events` · `extraction.completed`                   |
| Yayınlar | `ExtractionFailed`    | `invoice.events` · `extraction.failed`                      |

**Konfigürasyon (NFR-09):** model adı, prompt sürümü, LLM zaman aşımı, deneme sayısı, toplam toleransları.

### 4.3 compliance-service

**Sorumluluk:** sözleşme bilgisini tutmak ve faturayı fatura tarihinde geçerli sözleşmeyle karşılaştırmak. `PENDING_APPROVAL` kararını vermez, yalnızca sonucu ve bulguları bildirir (FR-C4). Faturayı okumaz: ihtiyacı olan VKN, tarih, kalemler ve tutarlar `CheckCompliance` mesajındadır. Dış bağımlılık: Ollama (LLM + bge-m3), Redis (LLM semaforu).

**v1:** stub (FR-C5), her faturaya `COMPLIANT`. **v2 (B-46):** gerçek kontrol; mesaj sözleşmesi ve akış değişmedi, dinleyici uzun işe geçti (embedding + LLM transaction dışında).

#### REST API (v2)

| Metot ve yol                                                                                  | Ne yapar                                                                                                                                                                                                                                                                                                                                | Yanıt                                                                                                                                                                   |
| --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `POST /api/v1/contracts` (multipart: `file`, `supplierVkn`, `validFrom`, `validTo`; `EXPERT`) | Dosyayı içerik adresli saklar; tek transaction'da `INGESTING` kaydı ve iç `IngestContract` komutu outbox'a. Aynı tedarikçide aralığı kesişen (`FAILED` olmayan) sözleşme varsa kabul eder ama `warnings` döner. Aynı dosya: mevcut kayıt; mevcut `FAILED` ise `FAILED → INGESTING` + yeni komut (tedarikçi ve tarihler ilk yüklemedeki) | 202 `{contractId, status, duplicate, warnings}` (`Location`); aynı dosya 200; VKN 10 hane değil, tarih eksik/biçimsiz ya da başlangıç > bitiş 400; PDF değil 415 (B-45) |
| `GET /api/v1/contracts?vkn=` · `/{id}` (giriş yapmış herkes)                                  | Sözleşmeler (tedarikçi, başlangıç yeniden eskiye); detayda indeksleme durumu, `failureReason` ve chunk özetleri (embedding'siz)                                                                                                                                                                                                         | 200; yoksa 404 (B-45)                                                                                                                                                   |

#### İndeksleme (FR-C1, v2)

1. PDFBox ile metin, sayfa numarası korunarak. Metin çıkmazsa sözleşme `FAILED`.
2. Normalizasyon: Unicode NFC (İ/ı), satır sonu tirelerini birleştirme, tekrar eden üst/alt bilgiyi silme.
3. Madde sınırında chunking: `^(MADDE|Madde)\s+\d+` ve `^\d+(\.\d+)*[.)]\s`; her madde bir chunk.
4. \~500 token'ı aşan madde paragraf sınırından bölünür, her parçaya madde başlığı eklenir. Kalıp bulunamazsa \~400 token, 50 token örtüşmeli bölme.
5. bge-m3 ile toplu embedding (1024 boyut, normalize, kosinüs); model adı her satıra yazılır.
6. Tüm chunk'lar tek transaction'da yazılır, sözleşme `READY` olur; yarım indeksli sözleşme asla seçilmez.

**Uygulama (B-45, `ingest` paketi):** dinleyici uzun iş (1 · 1); metin, chunking ve embedding transaction dışında, sonuç tek transaction'da (önceki yarım denemenin chunk'ları silinir). Paragraf sınırı PDFBox'ın satır aralığından okunur (varsayılan eşik). Üst/alt bilgi: sayfanın ilk ya da son dolu satırı, rakamlar `#` sayılarak, en az iki ve sayfaların en az yarısında aynıysa silinir. Tire birleştirme satır sonunda iki harf arasındaki her tireyi siler (kısıt: satır sonuna denk gelen gerçek tireli sözcük, örn. "e-Arşiv", birleşir). Alt madde ve uzun maddenin parçaları madde başlığıyla başlar ("MADDE 4 – FİYATLAR\n\n4.1. …"); yalnız başlıktan oluşan madde chunk olmaz; ilk maddeden önceki metin numarasız chunk'tır. Sınırlar karakterle: ~4 karakter = 1 token; `max-clause-chars` 2000, `window-chars` 1600, `overlap-chars` 200 (`invoice.compliance.ingest.*`); tek paragraf sığmazsa cümle, o da yetmezse sözcük sınırından bölünür. Embedding Spring AI Ollama (`spring.ai.ollama.embedding.model` = `OLLAMA_EMBEDDING_MODEL`), 16'lık toplu istek, L2 normalize; vektör `CAST(:v AS vector)` ile yazılır (pgvector kütüphanesi yok). LLM semaforuna girmez (kısa ve seyrek iş; B-46'da gözden geçirilir).

| Durum                                                 | Sonuç                                                                             |
| ----------------------------------------------------- | --------------------------------------------------------------------------------- |
| Metin yok ya da 20 karakterden kısa                   | `FAILED`, `NO_TEXT: …` (iş sonucu, ack)                                           |
| Dosya yok, PDF okunamıyor, boyut ≠ 1024, sıfır vektör | kalıcı: `basic.reject(requeue=false)` → DLQ                                       |
| Ollama'ya ulaşılamıyor                                | geçici: geri konur, teslim limitinden (3) sonra DLQ                               |
| `compliance.ingest-contract.dlq`                      | `INGESTING → FAILED`, `DLQ: <neden> (<kuyruk>)`, `ERROR` log (alarm, metrik B-48) |
| Sözleşme `INGESTING` değil (tekrar gelen komut)       | hiçbir şey yapılmaz                                                               |

#### Retrieval ve karşılaştırma (FR-C2, FR-C3, v2)

1. **Sözleşme seçimi SQL ile:** `WHERE supplier_vkn = ? AND ? BETWEEN valid_from AND valid_to AND status = 'READY'`. 0 sonuç → `NO_CONTRACT`, 2+ → `CONTRACT_CONFLICT`. Sözleşme süresi kontrolü burada biter, LLM'e gitmez.
2. **Kontrol başına sorgu:** fiyat için her kalem ayrı ("{kalem açıklaması} birim fiyat"), vade için tek sorgu. Fatura metni sorgu olarak kullanılmaz.
3. **Filtreli tam tarama:** `WHERE contract_id = ? ORDER BY embedding <=> :q LIMIT 4`. HNSW indeksi yok (ADR-09).
4. **Değer çıkarımı:** LLM'e maddeler + tek soru; yanıt `{bulunduMu, deger, birim, maddeNo, alinti}`.
5. **Grounding:** `alinti` getirilen chunk'larda (boşluk normalize) birebir aranır; yoksa bulgu "güvenilmez", fatura insan onayına gider.
6. **Karşılaştırma Java'da:** fatura birim fiyatı > sözleşme fiyatı, fatura vadesi ≠ sözleşme vadesi; birim testle sınanır.

Sonuç değerleri: `COMPLIANT`, `NON_COMPLIANT`, `NO_CONTRACT`, `CONTRACT_CONFLICT`. Retrieval kalitesi yetmezse ilk adım hibrit aramadır: Postgres `turkish` tam metin + vektör, Reciprocal Rank Fusion ile.

**Uygulama (B-46, `check` paketi):** `ComplianceEvaluator` adımları sırayla uygular; seçim 0/2+ sonuç verirse LLM'e hiç gidilmez (bulgu `CONTRACT_VALIDITY`; çakışmada sözleşmeler ve aralıkları `contractValue`'da). Fiyat sorgusu her farklı kalem açıklaması için bir kez (aynı açıklama birden çok satırda ise en yüksek fiyat). LLM (`ClauseValueClient`): sıcaklık 0, `num_ctx` 4096, `num_predict` 512, JSON şeması `{found, value: number|null, unit, clauseNo, quote}`; sistem mesajı kalem adının sözleşmede daha genel yazılabileceğini söyler ("Steril gazlı bez — parti 3" → "Steril gazlı bez"). Çağrı `sem:llm`'den geçer (`llm-support`, ADR-07). Grounding (`Grounding`): alıntı (en az 8 karakter) getirilen chunk'larda boşluk normalize edilerek harfi harfine geçmeli **ve** değer alıntıda yazılı olmalı (fiyat `1.840,00`/`1840,00`/`1840.00`, gün ayrı sayı). Bulgu yalnız aykırılık ve güvenilmez değer için yazılır: güvenilmez bulgu (`reliable=false`, değer bulunamadı, alıntı chunk'ta yok, değer alıntıda yok, yanıt çözülemedi) sonucu `NON_COMPLIANT` yapar; ayrı sonuç değeri yok (mesaj sözleşmesi değişmez). Bulgu alanları: fiyatta `invoiceValue` = `"Kalem: 86.50"`, `contractValue` = `"80.00 / adet"`; vadede `"14 gün"` / `"30 gün"`. Birim karşılaştırılmaz (faturada birim yok), bulguda gösterilir. Ollama'ya ulaşılamaması geçicidir: mesaj geri konur, teslim limitinden sonra DLQ → `PENDING_APPROVAL` (v1'den beri). Her kontrol `compliance_checks`'e yazılır.

**Gerçek modelle ölçüm (2026-10-02, `qwen2.5:7b-instruct` + `bge-m3`, `OllamaComplianceSmokeTest`, `-Dsmoke.ollama=true`):** B-44'ün 8 sözleşmesi 3,6 sn'de indekslendi; B-16'nın 10 faturası **10/10 doğru sonuç**, bulgular beklenenle aynı (02: Madde 4.1, 86,50 > 80,00; 04: Madde 5, 14 ≠ 30 gün). İlk denemede `value` şemada metindi; model değerin önüne çöp ekledi (`"strconv(425.00)"`, `"os30"`), grounding hepsini güvenilmez saydı, yanlış veri geçmedi ama 2 temiz fatura onaya düştü (8/10). `value` sayı tipine alındı ve kalem adının genel yazılabileceği eklendi → 10/10. Süre: tek kalemli faturada ~14 sn; 48 kalemli fatura-07 ~3,3 dk (kalem başına bir çağrı, ~4 sn). **Bilinen kısıt:** çok kalemli faturada uyum kontrolü uzun sürer; aday çözüm, aynı chunk'a düşen kalemleri tek soruda sormak (§4.3 adım 4'ten sapma, ölçümle karar verilir).

#### Veritabanı: compliance_db (pgvector)

v1'de yalnızca ortak outbox ve inbox vardı (B-22). `contracts` ve `contract_chunks` B-45'te (`V1__compliance_schema.sql`, kesin şema orada): ek olarak `failure_reason` (`FAILED` ⇔ dolu), `uploaded_by`, `updated_at`; VKN 10 hane ve başlangıç ≤ bitiş kontrolleri; `file_sha256` tekil; chunk'ta `chunk_index` (sıra, `(contract_id, chunk_index)` tekil, indeksi filtreli taramayı da karşılar), `part` (uzun madde parçası) ve `page` zorunlu. `compliance_checks` B-46'da (`V2__compliance_checks.sql`): `retrieved_chunk_ids` boş dizi varsayılanlı, `model` LLM'e gidilmediyse `NULL`; aynı belge yeniden kontrol edilirse yeni satır.

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE contracts (
    id           UUID PRIMARY KEY,
    supplier_vkn CHAR(10)    NOT NULL,
    valid_from   DATE        NOT NULL,
    valid_to     DATE        NOT NULL,
    storage_uri  TEXT        NOT NULL,
    file_sha256  CHAR(64)    NOT NULL,
    status       TEXT        NOT NULL,   -- INGESTING | READY | FAILED
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX contracts_selection ON contracts (supplier_vkn, valid_from, valid_to);

CREATE TABLE contract_chunks (
    id              BIGSERIAL PRIMARY KEY,
    contract_id     UUID NOT NULL REFERENCES contracts(id),
    clause_no       TEXT,
    title           TEXT,
    content         TEXT NOT NULL,
    page            INT,
    embedding       vector(1024) NOT NULL,   -- bge-m3
    embedding_model TEXT NOT NULL            -- model değişirse yeniden indeksleme tespiti
);
CREATE INDEX contract_chunks_contract ON contract_chunks (contract_id);

CREATE TABLE compliance_checks (
    id                  BIGSERIAL PRIMARY KEY,
    document_id         UUID  NOT NULL,
    contract_id         UUID,
    result              TEXT  NOT NULL,
    findings            JSONB NOT NULL,
    retrieved_chunk_ids BIGINT[],
    model               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- + outbox, inbox (ortak desen)
```

Dosyalar: sözleşme PDF'leri compliance-service'in kendi volume'unda.

#### Sentetik sözleşme seti (B-44)

8 sözleşme, B-16 faturalarının tedarikçilerine yazılıdır (VKN, unvan ve kalem açıklamaları fatura kataloğundan okunur); her biri bir faturanın uyum sonucunu belirler. Katalog `SyntheticContracts` (compliance-service test kaynakları), PDF'ler ve `*.expected.json` `src/test/resources/contracts`'ta commit edilir; üretme komutu `SyntheticContractGenerator` Javadoc'unda. Metin `MADDE n – BAŞLIK` ve alt maddeli düzende `n.m.` kalıbındadır; fiyat `1.840,00 TL`, tarih `01.01.2026`, vade "fatura tarihinden itibaren 30 (otuz) gün".

| Sözleşme | Fatura | Senaryo                                                                                                                                 | Beklenen            |
| -------- | ------ | --------------------------------------------------------------------------------------------------------------------------------------- | ------------------- |
| 01       | 01     | Fiyat ve vade aynı, sade düzen                                                                                                          | `COMPLIANT`         |
| 02       | 02     | Bir kalemde fiyat aşımı (Rulman 86,50 > 80,00); alt maddeli düzen (4.1…, 5.1…)                                                          | `NON_COMPLIANT`     |
| 03       | 04     | Vade farkı (fatura 14 gün, sözleşme 30 gün)                                                                                             | `NON_COMPLIANT`     |
| 04 + 05  | 06     | Aynı tedarikçide çakışan iki aralık                                                                                                     | `CONTRACT_CONFLICT` |
| 06       | 08     | Süresi dolmuş (2025)                                                                                                                    | `NO_CONTRACT`       |
| 07       | 07     | 2 sayfa; her sayfada üst/alt bilgi, satır sonu tireleri, ~500 token'ı aşan numarasız cezai şart maddesi; 48 kalem tavan fiyatın altında | `COMPLIANT`         |
| 08       | 10     | Yalnız resim, metin katmanı yok                                                                                                         | indeksleme `FAILED` |

Cezai şart fıkraları bilerek numarasızdır: `7.1.` gibi numaralar alt madde kalıbına takılır, madde zaten bölünmüş olurdu; paragraf sınırından bölme sınanamazdı. Tireleme heceye göre değildir, normalizasyon birleştirdiği için sınamada fark etmez. Sözleşmesiz faturalar (03, 05, 09) B-46'dan sonra `NO_CONTRACT` → `PENDING_APPROVAL` alır; sistem testlerinin beklentisi o zaman ele alınır. `SyntheticContractSetTest`: katalog ile JSON aynı; sözleşme faturasının tedarikçisine ait ve her kalem bir fiyatla eşleşir; beklenen sonuç §4.3 kurallarının faturaya elle uygulanmasıyla aynı; metin katmanı (maddeler, fiyatlar, tarihler, vade; alt madde yalnız alt maddeli düzende); contract-07'nin her sayfasında üst/alt bilgi, tire ve uzun madde; 04 ile 05 fatura tarihinde çakışır.

#### Mesajlar

| Yön      | Mesaj                           | Kuyruk / routing key                                                   |
| -------- | ------------------------------- | ---------------------------------------------------------------------- |
| Dinler   | `CheckCompliance`               | `compliance.check-compliance.q` (1 · 1)                                |
| Dinler   | `IngestContract` (iç komut, v2) | `compliance.ingest-contract.q` (1 · 1); DLQ → sözleşme `FAILED`, alarm |
| Yayınlar | `ComplianceCompleted`           | `invoice.events` · `compliance.completed`                              |
| Yayınlar | `IngestContract` (kendine)      | `invoice.commands` · `contract.ingest`                                 |

### 4.4 rpa-service

**Sorumluluk:** onaylı faturayı portala bir kez girmek ve kayıt numarasını almak. Portal idempotent olmadığı için dört idempotency katmanının hepsine ihtiyacı olan tek servistir. Dış bağımlılık: mock portal (Playwright ile HTML), Redis (v2: kilit, oturum).

**REST API:** yok (yalnızca Actuator).

#### İşleyiş

| Adım             | v1                                                       | v2                                                                                                                                                                                                   |
| ---------------- | -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Eşzamanlılık     | `x-single-active-consumer`, prefetch 1 (FR-R8)           | + Redis kilidi `lock:rpa:{supplierVkn}:{invoiceNo}`, 60 sn kira, watchdog 20 sn'de bir yeniler (FR-R7)                                                                                               |
| Kilit alınamazsa | —                                                        | Aynı `messageId` ile `invoice.retry` · `rpa.post.wait` bekleme odasına yayınla, orijinali ack et; teslim limitini tüketmez                                                                           |
| Başlangıç kaydı  | `portal_submissions` = `IN_PROGRESS` (commit)            | Aynı                                                                                                                                                                                                 |
| Ön arama         | —                                                        | Kilitten sonra fatura no ile ara, sayfaları dolaş (FR-R3); bulunursa `FOUND_EXISTING`, form doldurulmaz                                                                                              |
| Oturum           | Her faturada login                                       | Her faturada login (paylaşım yok, B-37 kararı); bir adımda login sayfasına düşülürse bir kez yeniden login ve akış ön aramadan baştan (FR-R4)                                                        |
| Giriş            | Login, formu doldur, kaydet, kayıt no oku (FR-R1, FR-R2) | Aynı                                                                                                                                                                                                 |
| Geçici hata      | Teslim limiti (3) → DLQ                                  | Bekleme odasında 30 sn ve yeniden deneme, `invoice.rpa.max-attempts` (4) dolunca `reject(requeue=false)` → DLQ (FR-R5, B-37); her başarısız denemede adım + ekran görüntüsü `rpa_attempts`'a (FR-R6) |
| Sonuç            | TX: `SUBMITTED` + inbox + `outbox(RpaCompleted)`, ack    | + kilidi bırak                                                                                                                                                                                       |

Redis yoksa RPA fail-closed davranır: kilit alınamaz sayılır, mesaj bekleme odasına gider, portala dokunulmaz.

**Ön arama (B-35, FR-R3):** her girişten önce, kilit alındıktan sonra yapılır (FR-R3'ün metni; elle girilmiş ya da aynı faturanın başka belgesinden gelmiş kaydı da yakalar). Arama sayfasında fatura no ile aranır, sonuç sayfaları `#next-page` bitene kadar dolaşılır (en fazla 100 sayfa, süre bütçesi içinde); fatura no **ve** VKN'si tam eşleşen ilk satırın kayıt numarası alınır (`data-ref-no`). Bulunursa form doldurulmaz: `portal_submissions` = `FOUND_EXISTING`, `RpaCompleted(foundExisting = true)`; document-service bunu da `POSTED` yapar. `FOUND_EXISTING` tamamlanmış sayılır: aynı belge yeniden gelirse portala dokunulmaz, olay `foundExisting`'i korur. Aynı fatura farklı bir PDF ile ikinci kez yüklenirse ikinci belge B-41'den beri `DUPLICATE_SUSPECTED`'da durur; uzman "mükerrer değil" derse ön arama ilk girişi bulur ve belge yeni portal kaydı açmadan aynı numarayla `POSTED` olur. Seçiciler `invoice.rpa.portal.selectors.*`'ta (§4.5).

**Süre ve kapanış zinciri (B-27):** her sayfa adımı `invoice.rpa.portal.timeout` (15 sn) ile, girişin tamamı `submit-timeout` (60 sn) ile sınırlıdır; adım ikisinden kalanı kadar bekler, tarayıcı açılışı da `timeout` ile sınırlıdır. Girişin üst sınırı `maxProcessingTime` = timeout + submit-timeout + 15 sn pay (90 sn). Listener'ın `shutdownTimeout`'u bu değerdir (Spring varsayılanı 5 sn; daha kısa beklenirse kanal kapanır, mesaj yedek örneğe geçer ve aynı fatura iki örnekte birden girilebilir). Zincir: `maxProcessingTime` (90 sn) < `spring.lifecycle.timeout-per-shutdown-phase` (2 dk; yoksa DB giriş bitmeden kapanır) < compose `stop_grace_period` (150 sn; yoksa Docker öldürür). İlk iki halka `RpaServiceApplicationTests`'te sınanır.

#### Veritabanı: rpa_db

```sql
CREATE TABLE portal_submissions (      -- "girmeye başladım" bilgisi, forma yazmadan önce
    document_id   UUID PRIMARY KEY,
    supplier_vkn  CHAR(10)    NOT NULL,
    invoice_no    TEXT        NOT NULL,
    status        TEXT        NOT NULL,   -- IN_PROGRESS | SUBMITTED | FOUND_EXISTING
    portal_ref_no TEXT,
    attempt_count INT         NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rpa_attempts (            -- FR-R6
    id              BIGSERIAL PRIMARY KEY,
    document_id     UUID        NOT NULL,
    attempt_no      INT         NOT NULL,
    failed_step     TEXT,
    error           TEXT,
    screenshot_path TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- + outbox, inbox (ortak desen)
```

Dosyalar: ekran görüntüleri rpa-service'in volume'unda.

#### Mesajlar

| Yön           | Mesaj                    | Kuyruk / routing key                                              |
| ------------- | ------------------------ | ----------------------------------------------------------------- |
| Dinler        | `PostToPortal`           | `rpa.post-to-portal.q` (1 · 1, single active consumer)            |
| Yayınlar      | `RpaCompleted`           | `invoice.events` · `rpa.completed`                                |
| Yayınlar (v2) | `PostToPortal` (bekleme) | `invoice.retry` · `rpa.post.wait` → `rpa.post-to-portal.wait-30s` |

**Konfigürasyon:** portal URL, seçiciler (NFR-09), kimlik bilgileri ortam değişkeninden; loga yazılmaz (NFR-10).

### 4.5 mock-portal

**Sorumluluk:** API'si olmayan eski sistemi taklit etmek. Sistemin parçası sayılmaz; kendi fatura tablosu vardır ve mesajlaşmaya katılmaz.

| Sayfa                  | Davranış                                                                                 | Sürüm     |
| ---------------------- | ---------------------------------------------------------------------------------------- | --------- |
| Login                  | Kullanıcı adı / şifre, oturum cookie'si                                                  | v1        |
| Fatura giriş formu     | Başarılı girişte kayıt numarası gösterir (FR-P2)                                         | v1        |
| Fatura listesi / arama | Sayfalı; fatura no ile arama                                                             | v1        |
| Hata enjeksiyonu       | Konfigüre edilebilir oturum zaman aşımı, rastgele 500, gecikmeli yüklenen eleman (FR-P3) | v2 (B-36) |

Teknoloji (A6, B-24): Spring Boot + Thymeleaf; kayıtlar bellekte (yeniden başlayınca silinir, kayıt no 1000'den artar). Oturum `HttpSession` cookie'sinde (`tracking-modes: cookie`; URL'ye `;jsessionid` yazılmaz), girişte yeni oturum; oturumsuz istek `/login`'e 302. Kimlik bilgileri `PORTAL_USERNAME` / `PORTAL_PASSWORD`, boşsa portal açılmaz. Doğrulama sunucu tarafında: VKN 10 hane, tarihler ISO, tutarlar nokta ondalıklı (`5100.00`), para birimi TRY/USD/EUR; hatalı form 200 ile aynı değerlerle döner. Portal idempotent değildir: aynı fatura iki kayıt üretir. Hata bayrağı: fatura numarası `PORTAL_FAIL_INVOICE_NO_PATTERN` (regex, `find`) ile eşleşen gönderim kaydedilmeden 500 döner; boşsa kapalı. Belirli bir faturanın hep düşüp arkasındakinin işlenmesi böyle sınanır (B-28). Yavaş yanıt bayrağı (B-35): fatura numarası `PORTAL_SLOW_INVOICE_NO_PATTERN` ile eşleşen gönderim **kaydedilir**, yanıt `slow-response-delay` (20 sn) gecikir; bot bu arada öldürülebilsin diye (US-08: portalda kayıt var, sistemde yok). Hata enjeksiyonu (FR-P3, B-36), hepsi ortam değişkeniyle ve varsayılanda kapalı: `PORTAL_SESSION_IDLE_TIMEOUT` (boşta kalan oturum düşer, istek login'e yönlenir; saniye hassasiyetinde portalın kendisinde, Tomcat'inki dakikalık), `PORTAL_ERROR_RATE` (0–1) + isteğe bağlı `PORTAL_ERROR_SEED` (login, liste, arama, form dahil her sayfa bu olasılıkla 500 + `#portal-error`; Actuator hariç ki compose sağlık kontrolü bozulmasın; tohumla dizi tekrarlanabilir), `PORTAL_ELEMENT_DELAY` (fatura formu ve sonuç tablosu `data-delayed` ile gizli gelir, süre sonra JavaScript ile açılır). Çalışırken aç-kapa uç noktası yok (A6).

Seçiciler rpa-service ile sözleşmedir; rpa tarafında `invoice.rpa.portal.selectors.*` ayarlarındadır (NFR-09, B-25):

| Sayfa                | Seçiciler                                                                                                                                                                                                                     |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/login`             | `#username`, `#password`, `#login-submit`; hata `#login-error`                                                                                                                                                                |
| Her oturumlu sayfa   | `#nav-invoices` (üst menü; rpa girişin başarılı olduğunu bununla anlar)                                                                                                                                                       |
| `/invoices/new`      | `#invoice-form`; alanlar `#supplierVkn`, `#supplierName`, `#invoiceNo`, `#invoiceDate`, `#dueDate`, `#grandTotal`, `#vatTotal`, `#currency` (select); `#invoice-submit`; hatalar `#form-errors`, `.field-error[data-field=…]` |
| `/invoices/{refNo}`  | `#ref-no`, `#success-message` (gönderimden sonra `?created`)                                                                                                                                                                  |
| `/invoices?q=&page=` | `#search-invoice-no`, `#search-submit`, `#invoice-table tr.invoice-row[data-ref-no]` (hücreler `.invoice-no`, `.ref-no` …), `#next-page`, `#prev-page`, `#page-info`                                                          |
| Hata                 | 500 + `#portal-error`                                                                                                                                                                                                         |

## 5. Mesaj sözleşmeleri ve RabbitMQ topolojisi

Sekiz mesaj vardır: dört komut tek alıcıya `invoice.commands` (direct) üzerinden, dört olay document-service'e `invoice.events` (topic) üzerinden gider. Mesaj adları sabittir; v2 yeni mesaj eklemez.

### Mesaj listesi

| Ad                    | Tür      | Üreten → Tüketen        | Exchange · routing key                    | Sürüm |
| --------------------- | -------- | ----------------------- | ----------------------------------------- | ----- |
| `ExtractInvoice`      | Komut    | document → extraction   | `invoice.commands` · `extract.invoice`    | v1    |
| `ExtractionCompleted` | Olay     | extraction → document   | `invoice.events` · `extraction.completed` | v1    |
| `ExtractionFailed`    | Olay     | extraction → document   | `invoice.events` · `extraction.failed`    | v1    |
| `CheckCompliance`     | Komut    | document → compliance   | `invoice.commands` · `compliance.check`   | v1    |
| `ComplianceCompleted` | Olay     | compliance → document   | `invoice.events` · `compliance.completed` | v1    |
| `PostToPortal`        | Komut    | document → rpa          | `invoice.commands` · `rpa.post`           | v1    |
| `RpaCompleted`        | Olay     | rpa → document          | `invoice.events` · `rpa.completed`        | v1    |
| `IngestContract`      | İç komut | compliance → compliance | `invoice.commands` · `contract.ingest`    | v2    |

### Zarf (her mesajda)

| AMQP alanı                 | Değer                                                       |
| -------------------------- | ----------------------------------------------------------- |
| `message_id`               | Outbox satırının id'si (UUID); inbox anahtarı               |
| `correlation_id`           | `documentId`; MDC'ye konur, her log satırına girer (NFR-06) |
| `type`                     | Mesaj adı, örn. `ExtractInvoice`                            |
| `x-schema-version` başlığı | Şema sürümü, başlangıç `1`                                  |
| `delivery_mode`            | persistent; `content_type` = `application/json`             |

Şema evrimi kuralı: yalnızca alan ekleme; tüketici bilinmeyen alanı yok sayar. Alan silme veya anlam değişikliği `x-schema-version` artırır.

Tip bilgisi Java sınıf adıyla (`__TypeId__`) değil, `type` alanındaki mesaj adıyla taşınır; eşleme `MessageType` enum'undadır (ad, gövde sınıfı, exchange, routing key, şema sürümü). Dönüştürücü `InvoiceMessageConverter` kendi `JsonMapper`'ını kullanır, servisin web JSON ayarı mesaj sözleşmesini etkilemez. Bilinmeyen `type`, eksik veya UUID olmayan `message_id` / `correlation_id`, eksik veya desteklenmeyen `x-schema-version`, JSON olmayan `content_type` ve çözümlenemeyen gövde zehirli mesaj sayılır.

### Gövdeler (v1 şema taslağı)

```json
// ExtractInvoice
{ "documentId": "uuid", "storageUri": "file:///data/documents/{sha256}.pdf", "fileSha256": "hex" }

// ExtractionCompleted
{ "documentId": "uuid",
  "fields": { "supplierName": "", "supplierVkn": "1234567890", "invoiceNo": "", "invoiceDate": "2026-09-30",
              "dueDate": "2026-10-30", "lines": [{ "description": "", "quantity": "1", "unitPrice": "100.00", "vatRate": 20 }],
              "subtotal": "100.00", "vatTotal": "20.00", "grandTotal": "120.00", "currency": "TRY" },
  "confidenceScore": "0.92",
  "ruleResults": [{ "rule": "LINES_SUM_EQUALS_SUBTOTAL", "passed": true, "detail": null }],
  "model": "", "promptVersion": "" }

// ExtractionFailed
{ "documentId": "uuid", "reason": "NO_TEXT | LLM_RETRIES_EXHAUSTED", "detail": "", "attempts": 3 }

// CheckCompliance
{ "documentId": "uuid", "supplierVkn": "", "invoiceDate": "", "dueDate": "",
  "lines": [ ... ], "subtotal": "", "vatTotal": "", "grandTotal": "", "currency": "TRY" }

// ComplianceCompleted
{ "documentId": "uuid", "result": "COMPLIANT | NON_COMPLIANT | NO_CONTRACT | CONTRACT_CONFLICT",
  "contractId": "uuid|null",
  "findings": [{ "check": "UNIT_PRICE | PAYMENT_TERM | CONTRACT_VALIDITY", "clauseNo": "7.2",
                 "quote": "", "invoiceValue": "", "contractValue": "", "reliable": true }] }

// PostToPortal
{ "documentId": "uuid", "supplierVkn": "", "supplierName": "", "invoiceNo": "", "invoiceDate": "",
  "dueDate": "", "grandTotal": "", "vatTotal": "", "currency": "TRY" }

// RpaCompleted
{ "documentId": "uuid", "portalRefNo": "4711", "foundExisting": false }

// IngestContract (v2)
{ "contractId": "uuid", "storageUri": "file:///data/contracts/{sha256}.pdf" }
```

Tutarlar, `quantity` ve `confidenceScore` `BigDecimal`'dır ve JSON'da string olarak taşınır (bilimsel gösterim yok, `1E+3` → `"1000"`), float yuvarlama hatası olmasın diye. Tarihler ISO (`2026-09-30`), `vatRate` tam sayıdır. `reason`, `result` ve `check` Java enum'udur; `rule` ise yeni kural sözleşmeyi değiştirmesin diye string'dir. Record'lar `common-messaging`'de `com.archosan.invoice.messaging.message` paketindedir (B-04).

### Kuyruklar

| Kuyruk                                              | Bağlandığı exchange · key               | Tüketen           | Prefetch · eşzamanlılık       | DLQ ve sonucu                                                        |
| --------------------------------------------------- | --------------------------------------- | ----------------- | ----------------------------- | -------------------------------------------------------------------- |
| `extraction.extract-invoice.q`                      | `invoice.commands` · `extract.invoice`  | extraction        | 1 · 1 (LLM sınırı)            | `.dlq` → document, `NEEDS_REVIEW`                                    |
| `compliance.check-compliance.q`                     | `invoice.commands` · `compliance.check` | compliance        | 1 · 1                         | `.dlq` → document, `PENDING_APPROVAL`                                |
| `compliance.ingest-contract.q`                      | `invoice.commands` · `contract.ingest`  | compliance        | 1 · 1                         | `.dlq` → sözleşme `FAILED`, alarm                                    |
| `rpa.post-to-portal.q`                              | `invoice.commands` · `rpa.post`         | rpa               | 1 · 1, single active consumer | `.dlq` → document, `RPA_FAILED`                                      |
| `document.extraction-events.q`                      | `invoice.events` · `extraction.*`       | document          | 10 · 2                        | `.dlq` → tüketici yok, alarm                                         |
| `document.compliance-events.q`                      | `invoice.events` · `compliance.*`       | document          | 10 · 2                        | `.dlq` → alarm                                                       |
| `document.rpa-events.q`                             | `invoice.events` · `rpa.*`              | document          | 10 · 2                        | `.dlq` → alarm                                                       |
| `rpa.post-to-portal.wait-30s` (v2, B-34'te kuruldu) | `invoice.retry` · `rpa.post.wait`       | — (bekleme odası) | —                             | TTL dolunca DLX `invoice.commands` · `rpa.post` → aslı kuyruğa döner |

Exchange'ler: `invoice.commands` (direct), `invoice.events` (topic), `invoice.dlx` (direct), `invoice.retry` (direct, v2).

### Kuyruk argümanları

```text
x-queue-type              = quorum
x-delivery-limit          = 3                  # RabbitMQ 4.x varsayılanı 20, açıkça verilir; 3 başarısız teslime izin verir, 4. reject'te DLQ
x-dead-letter-exchange    = invoice.dlx
x-dead-letter-routing-key = <kuyruk adı>        # her kuyruk kendi .dlq'suna
x-dead-letter-strategy    = at-least-once
x-overflow                = reject-publish     # at-least-once için zorunlu
```

DLQ'lar da quorum ve kalıcıdır, kendi DLX'leri yoktur. DLQ'larda `x-delivery-limit = -1` (limitsiz) verilir: varsayılan 20'de, DLX olmadığı için 20 başarısız teslimden sonra mesaj sessizce silinirdi.

### Yayıncı ve tüketici kuralları

- **Outbox relay:** 500 ms'de bir `SELECT … WHERE published_at IS NULL ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED`; `persistent`, `mandatory`, publisher confirm. `published_at` yalnızca broker ack'inden sonra yazılır. Her tur tek transaction'dır, kilit confirm'ler gelene kadar (en fazla `confirm-timeout`, 5 sn) tutulur. Ack gelse bile return edilen (yönlendirilemeyen) satır yayınlanmış sayılmaz; nack, return ve zaman aşımı `attempts`'i artırır, satır sonraki turda yeniden denenir, relay hiçbir satırdan vazgeçmez. Broker'a hiç ulaşılamazsa partinin kalanı gönderilmez ve `attempts` artmaz (kusur altyapıda). Tam dolu ve tamamen yayınlanmış partiden sonra beklemeden yeni tura geçilir. Relay kendi thread'inde çalışan bir `SmartLifecycle`'dır; açılışta `publisher-confirm-type=correlated` ve `publisher-returns=true` değilse servis başlamaz (B-05).
- **Outbox yazımı:** yalnızca `OutboxWriter.add(aggregateId, mesaj)` ile ve açık bir iş transaction'ı içinde (yoksa `IllegalTransactionStateException`). `aggregate_id` yayında `correlation_id` olur; şema sürümü yayın anında `MessageType`'tan alınır. `RabbitTemplate`'i yalnızca relay kullanır. Bekleme odası için (B-34) `OutboxWriter.republish(envelope, mesaj, exchange, routingKey)`: alınmış mesajı **aynı `messageId`** ve `correlationId` ile başka hedefe yazar; aynı mesaj yeniden ertelenirse satır upsert edilir (yayınlanmamış hale gelir, `attempts` sıfırlanır).
- **Servis entegrasyonu:** `common-messaging` auto-configuration ile kurulur (ADR-20). JDBC, Flyway ve Postgres sürücüsü kütüphanede `optional`'dır; servis kendi DB'sini kurarken `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, `flyway-database-postgresql` ve `postgresql`'i ekler, `spring.rabbitmq.publisher-confirm-type: correlated` ve `spring.rabbitmq.publisher-returns: true` verir. Relay `invoice.messaging.outbox.relay.enabled=false` ile kapatılabilir; ayarlar `invoice.messaging.outbox.{poll-interval, batch-size, confirm-timeout}`.
- **Ack:** listener başarıyla dönünce ack, istisnada `basic.reject`. İş transaction'ı commit olmadan ack yok. Container `AcknowledgeMode.MANUAL` ile çalışır, kararı `InvoiceMessageListener` verir (ADR-19).
- **Teslim limiti yalnızca reject'i sayar:** RabbitMQ 4.3'te `x-delivery-count` `basic.reject`, bağlantı kopması ve consumer timeout ile artar; `basic.nack` ile artmaz (B-03'te doğrulandı). Spring AMQP 4.1.1'in varsayılan listener container'ı istisnada toplu `basicNack` gönderir; olduğu gibi kullanılırsa hatalı mesaj DLQ'ya hiç düşmez, kuyruğu sonsuza kadar tıkar. B-04'te manuel ack + açık `basicReject` seçildi (ADR-19). B-07'de gerçek kuyrukla kanıtlandı (`DeliveryLimitIntegrationTest`): hep hata atan handler 4 kez çağrılır (ilk teslim + 3 yeniden teslim), mesaj DLQ'ya `x-first-death-reason = delivery_limit` ile düşer; zehirli mesaj ve `AmqpRejectAndDontRequeueException` tek teslimde `rejected` nedeniyle düşer.
- **Zehirli mesaj:** çözümlenemeyen mesaj, kuyrukta handler'ı olmayan tip ve handler'ın fırlattığı `AmqpRejectAndDontRequeueException` (neden zincirinde de aranır) `basic.reject(requeue=false)` ile doğrudan DLQ'ya gider. Handler'lar aynı veriyle yeniden denemenin sonucu değiştirmeyeceği hataları bu yolla kalıcı sayar: extraction'da adres/hash hatası; rpa'da portal biçimine çevrilemeyen değer (eksik alan, ikiden fazla ondalık; portala gidilmez) ve portalın form doğrulaması (B-25).
- **DLQ tüketicisi hiçbir mesajı düşürmez (B-14):** DLQ'ların kendi DLX'i yoktur, `requeue=false` ile reddedilen mesajı broker siler. Bu yüzden DLQ'lar `InvoiceMessageListener` ile değil `DeadLetterListener` ile tüketilir: mesaj katı biçimde çözülmez (zehirli mesaj ham haliyle handler'a gelir), başarıda ack, her hatada `basic.reject(requeue=true)`; DLQ'larda teslim limiti olmadığı için mesaj sorun düzelene kadar bekler. Kayda geçen kuyruk ve neden mesajı DLQ'ya **düşüren son ölümdür** (`x-last-death-queue` / `-reason`, yoksa `x-first-death-*`): bekleme odasından geçen mesajın ilk ölümü oradaki TTL'dir (`expired`, B-37).
- **Uzun işler (LLM, RPA):** önce `inbox.exists`, iş transaction dışında, sonuç kısa bir transaction'da `tryInsert` ile. İş şimdi yapılamıyorsa (B-34: fatura kilidi alınamadı) handler `completion.defer(work)` çağırır: `work` kısa bir transaction'da çalışır, mesaj inbox'a **yazılmaz**, ack edilir; `work` mesajı `republish` ile bekleme odasına koyar ve mesaj aynı kimlikle döndüğünde inbox onu işlenmemiş bulur. Teslim limiti tüketilmez. Handler `complete` ya da `defer`'den birini tam bir kez çağırır.
- **Inbox dinleyicide (B-06):** servis kodu inbox'ı doğrudan çağırmaz; `consumer` kuyruk adıdır. Kısa iş `on(Tip, handler)` ile kaydedilir: dinleyici transaction açar, `tryInsert` yapar, handler'ı aynı transaction'da çağırır, commit sonrası ack eder; tekrar gelen mesajda handler çağrılmadan ack edilir. Uzun iş `onLongRunning(Tip, handler)` ile kaydedilir: dinleyici önce `exists`'e bakar, handler işi transaction dışında yapar ve sonucu `completion.complete(() -> …)` ile yazar; dinleyici bunu kısa bir transaction'da `tryInsert`'ten sonra çalıştırır, mesaj bu arada başka kopyada işlendiyse sonuç atılır. Handler `complete` çağırmadan dönerse programlama hatası sayılır: inbox'a yazılmaz, mesaj `basicReject(requeue=true)` ile geri konur ve teslim limitinden sonra DLQ'ya düşer (hata sessizce kaybolmasın diye; bedeli uzun işin tekrarlanması). Dinleyiciler auto-config'in `InvoiceMessageListenerFactory.forQueue(kuyruk)` kısa yoluyla kurulur.
- **İki ayrı retry sayacı:** geçici iş hatası servis içinde artan beklemeyle denenir; çökme veya beklenmeyen istisna broker teslim sayacını artırır; limit 3 olduğu için 4. başarısız teslimden sonra DLQ.
- **Log ve correlationId (B-08, NFR-06):** MDC anahtarları `correlationId` (= documentId), `messageId`, `messageType`. Dinleyici bunları işlem boyunca ham AMQP alanlarından MDC'ye koyar (zehirli mesaj da eldeki kimliklerle loglanır), relay satır başına loglarda satırın değerlerini kullanır; kapanınca MDC önceki haline döner. Servis giriş noktaları (REST) `try (var s = CorrelationScope.open(documentId))` kullanır. JSON log Spring Boot'un yerleşik structured logging'i ile, ECS formatında (`service.name` = `spring.application.name`); yalnızca konteynerde açıktır (compose `x-app-env`: `LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs`), yerelde ve testlerde düz metin. Servisin kendi açacağı thread havuzlarına MDC aktarımı (`TaskDecorator`) ihtiyaç doğunca eklenir.
- **Metrikler ve alarmlar (B-48, NFR-06):** her servis `/actuator/prometheus` yayınlar (`micrometer-registry-prometheus`; ortak etiket `application`). Yetki: document, compliance, extraction'da `ADMIN` (ADR-22); rpa-service'in portu host'a açılmaz, ucu iç ağda ve kimliksiz. Kuyruk/DLQ derinliği ve tüketiciler broker'ın kendi eklentisinden (`rabbitmq_prometheus`, `infra/rabbitmq/enabled_plugins` ile sabit, `127.0.0.1:15692/metrics/per-object`); uygulama aynı sayıyı tekrar ölçmez. Uygulama metrikleri: `invoice_messages_processed_seconds{queue,type,outcome}` (timer; `outcome` = `ack`, `duplicate`, `deferred`, `requeue`, `dead_letter`; `requeue` retry metriğidir; zehirli mesajda `type="unknown"`), `invoice_dead_letters_received_total{queue,type}` (DLQ dinleyicisinin yeni aldığı), `invoice_outbox_relay_total{type,result}`, `invoice_outbox_pending`, `invoice_outbox_oldest_pending_age_seconds` (okumada sorgulanır), `invoice_llm_permit_wait_seconds{mode}`, `invoice_llm_calls_seconds{mode}`, `invoice_llm_permit_unavailable_total{mode}` (`llm-support`; `mode` = `redis` | `local`), document-service'te `invoice_documents_stuck{status}` (dedektörün son sayımı) ve `invoice_dead_letters_open{kind}`. Ölçüm dinleyicinin ack/reject kararını değiştirmez; kayıt defteri yoksa no-op. Alarmlar `infra/prometheus/alerts.yml`'de (olay DLQ'su dolu, yeni komut DLQ'su, 1 saattir bekleyen park kaydı, kuyruk birikimi, yüksek retry oranı, outbox duruşu, takılı kayıt, LLM izni alınamadı, servis kazınamıyor), `alerts.test.yml` promtool birim testleri, `prometheus.yml` kazıma örneği (yönetici şifresi dosyadan). Prometheus compose'da çalışmaz (bellek bütçesi, B-31); v1'in `ERROR` log alarmları sürer. Alternatifler: Prometheus + Grafana compose'da (~400–600 MB, iki servis), yalnız `/actuator/metrics` (alarm kuralı yazılamaz).
- **Postgres çökmesi:** listener içinde DB hatalarında kısa artan beklemeli retry; health DOWN iken listener container'ları durdurulur, sağlam mesajlar teslim limitini boşuna tüketmesin.

### Ortak tablolar (her serviste)

```sql
CREATE TABLE outbox (
    id            UUID PRIMARY KEY,          -- = messageId
    aggregate_id  UUID NOT NULL,             -- = documentId
    message_type  TEXT NOT NULL,
    exchange      TEXT NOT NULL,
    routing_key   TEXT NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INT NOT NULL DEFAULT 0
);
CREATE INDEX outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE inbox (
    message_id    UUID NOT NULL,
    consumer      TEXT NOT NULL,             -- kuyruk adı (B-06)
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer)
);
CREATE INDEX inbox_processed_at ON inbox (processed_at);   -- 30 günlük temizlik için
```

### Dört idempotency katmanı

| Katman                  | Nerede                                           | Neyi önler                                      | Sürüm                       |
| ----------------------- | ------------------------------------------------ | ----------------------------------------------- | --------------------------- |
| 1. Inbox                | Her tüketici, iş verisiyle aynı transaction      | Aynı `messageId`'nin iki kez işlenmesi          | v1                          |
| 2. Koşullu durum geçişi | document-service, `WHERE status = ?` + `version` | Geç, sırasız veya eski komutun sonucu olan olay | v1                          |
| 3. Dağıtık kilit        | rpa-service, Redis, VKN + fatura no              | İki worker'ın aynı faturayı aynı anda girmesi   | v2 (v1: tek aktif consumer) |
| 4. Portalda ön arama    | rpa-service, kilitten sonra                      | Girilmiş ama sisteme yansımamış kaydın tekrarı  | v2                          |

### Hata matrisi

| Senaryo                                       | Sonuç                             | Koruma                                                  | Sürüm |
| --------------------------------------------- | --------------------------------- | ------------------------------------------------------- | ----- |
| Commit sonrası, yayın öncesi çökme            | Outbox satırı yayınlanmamış kalır | Relay açılışta yayınlar                                 | v1    |
| Relay yayınlar, `published_at` yazmadan çöker | Mesaj iki kez gider               | Alıcının inbox'ı                                        | v1    |
| RabbitMQ çöker                                | Yükleme yine 202, outbox birikir  | Quorum kuyruk + kalıcı mesaj                            | v1    |
| extraction LLM çağrısında ölür                | Tekrar teslim                     | 4. başarısız teslimden sonra DLQ → `NEEDS_REVIEW`       | v1    |
| LLM bozuk JSON / zaman aşımı                  | Servis içi retry                  | Tükenirse `ExtractionFailed`, ack                       | v1    |
| Zehirli mesaj                                 | Dönüştürme istisnası              | Doğrudan DLQ                                            | v1    |
| compliance saatlerce kapalı                   | Kayıtlar `VALIDATED`'da bekler    | Kalıcı mesaj; takılı kayıt alarmı                       | v1    |
| Geç / sırasız olay                            | Koşullu UPDATE 0 satır            | `dead_letters` (LATE_EVENT) + alarm                     | v1    |
| İki rpa-service örneği                        | İkincisi yedekte                  | Single active consumer; v2'de kilit                     | v1    |
| RPA formu gönderdikten sonra çöker (US-08)    | Portalda kayıt var, sistemde yok  | Kilit + ön arama; v1'de çift kayıt riski                | v2    |
| Portal geçici hata (US-09)                    | Servis içi retry, yeniden login   | Tükenirse DLQ → `RPA_FAILED`                            | v2    |
| Redis çöker                                   | Semafor yerel sınıra düşer        | RPA fail-closed                                         | v2    |
| `RPA_FAILED` iken `RpaCompleted`              | Geçersiz geçiş                    | Geç olay olarak kayıt; yeniden işletmede ön arama bulur | v2    |
| document-service kendi olay kuyruğunda bug    | Olay DLQ'ya düşer                 | DLQ derinliği alarmı; düzeltince yeniden yayın          | v1    |

## 6. Altyapı (docker-compose)

`docker compose up` beş uygulama konteyneri (dört servis + mock portal) ve üç altyapı konteyneri, toplam sekiz konteyner ayağa kaldırır; Ollama compose'un dışında, macOS üzerinde doğrudan çalışır. Docker Desktop konteynerleri Mac GPU'suna (Metal) erişemediği için Ollama konteynerde CPU'ya düşer; servisler ona `host.docker.internal:11434` üzerinden ulaşır. Image sürümleri ve bellek limitleri öneridir, ilk tam yığın ölçümünden sonra sabitlenir (NFR-08).

| Konteyner            | Image (öneri)                                                       | Port        | Volume                 | Ne için                                                          |
| -------------------- | ------------------------------------------------------------------- | ----------- | ---------------------- | ---------------------------------------------------------------- |
| `postgres`           | `pgvector/pgvector:pg17`                                            | 5432        | `pgdata`               | Dört veritabanı + dört kullanıcı; init script ile kurulur        |
| `rabbitmq`           | `rabbitmq:4-management`                                             | 5672, 15672 (yalnız localhost) | `rabbitdata` | Exchange, kuyruk, DLQ topolojisi `definitions.json` ile yüklenir; arayüz isteğe bağlı yönetici kullanıcısıyla |
| `redis`              | `redis:7-alpine`                                                    | 6379        | — (kalıcılık gerekmez) | LLM semaforu; v2'de kilit ve oturum                              |
| `document-service`   | Proje image'ı                                                       | 8081        | `documents` (rw)       | Orkestratör, dışa açık API                                       |
| `extraction-service` | Proje image'ı                                                       | 8083        | `documents` (ro)       | Ollama'ya `host.docker.internal` ile erişir; ham LLM çıktısı API'si (B-47) |
| `compliance-service` | Proje image'ı                                                       | 8082        | `contracts` (rw)       | Sözleşme API'si (v2)                                             |
| `rpa-service`        | Playwright Java taban image'ı (`mcr.microsoft.com/playwright/java`) | —           | `screenshots` (rw)     | Headless Chromium dahil                                          |
| `mock-portal`        | Proje image'ı                                                       | 8090        | —                      | Eski portal taklidi                                              |

### Depo içinde gereken dosyalar

- `infra/postgres/init/01-databases.sql`: `document_db`, `extraction_db`, `compliance_db`, `rpa_db`; her biri için ayrı kullanıcı, `REVOKE CONNECT … FROM PUBLIC`; `compliance_db`'de `CREATE EXTENSION vector`. Şifreler dosyaya yazılmaz; psql `\getenv` ile postgres konteynerinin ortamından (`.env`) okunur.
- `infra/rabbitmq/definitions.json` + `rabbitmq.conf` (`load_definitions`): `invoice` vhost'u, exchange'ler, kuyruklar, argümanlar ve binding'ler. Açılışta definitions yüklenince varsayılan guest kullanıcısı oluşturulmaz.
- `infra/rabbitmq/init-user.sh`: RabbitMQ'nun entrypoint'i; uygulama kullanıcısını ve izinlerini `.env`'deki `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD`'den üretip topolojiyle birleştirir, sonra broker'ı başlatır. Kullanıcının `configure` izni yoktur (servisler topoloji ilan etmez), `write` ve `read` izni vardır. `RABBITMQ_ADMIN_PASSWORD` doluysa yönetim arayüzü için ayrı bir insan kullanıcısı (`RABBITMQ_ADMIN_USERNAME`, varsayılan `rabbit-admin`) da üretilir: `monitoring` etiketi, `invoice` vhost'unda yalnız `read`. Kuyrukları, DLQ'ları ve mesajları görür; yayınlayamaz (`ACCESS_REFUSED`), topoloji değiştiremez; RabbitMQ'da `read` mesaj almayı ve kuyruk boşaltmayı da kapsar. Şifre boşsa üretilmez. Arayüz (15672) ve metrik ucu (15692) yalnız `127.0.0.1`'e açılır.
- `.env.example`: DB şifreleri, RabbitMQ kullanıcısı, portal kimlik bilgileri, model adları. Gerçek `.env` git'e girmez (NFR-10). `.env` compose'un değişken kaynağıdır, konteynerlere toptan verilmez: uygulama konteynerlerinde `env_file` yoktur, her servis yalnızca ihtiyacı olan değişkeni `${…:?}` ile alır (B-26). Kendi DB şifresi ve RabbitMQ kullanıcısı her serviste; portal kimlik bilgileri yalnızca rpa-service ve mock-portal'da; `OLLAMA_CHAT_MODEL` yalnızca extraction-service'te (zorunlu). Yeni değişken compose'a da yazılmalıdır, yoksa konteynere ulaşmaz. B-43: `API_EXPERT_PASSWORD`, `API_APPROVER_PASSWORD`, `API_ADMIN_PASSWORD`, `API_EXPERT_APPROVER_PASSWORD`; compose yalnız document-service'e verir ve biri eksikse başlamaz (`:?`). Betikler (`smoke-e2e.sh`, `measure-nfr07.sh`) uzman şifresini `.env`'den okur ve curl'e argüman değil `-K <(…)` yapılandırmasıyla verir (süreç listesinde görünmez); sistem testleri dört şifreyi de rastgele üretir. B-45: compose `OLLAMA_EMBEDDING_MODEL`'i ve dört API şifresini compliance-service'e de verir (eksikse başlamaz); sistem testleri `bge-m3` adını verir. B-46: betikler `OLLAMA_EMBEDDING_MODEL`'in de çekilmiş olmasını ister.
- `docker-compose.yml` ve geliştirme için yalnızca altyapıyı kaldıran `infra` profili.
- `test-support/` (B-07): yalnızca test scope'ta kullanılan modül. `InfrastructureContainers` RabbitMQ'yu (`rabbitmq.conf`, `definitions.json`, `init-user.sh`), Postgres'i (`01-databases.sql`) ve Redis'i compose ile aynı imajlarla, JVM başına bir kez ve ilk kullanımda açar; şifreler her çalıştırmada rastgele üretilir. `AbstractIntegrationTest` RabbitMQ'yu bağlar; servis kendi veritabanını `InfrastructureContainers.registerPostgres(registry, ServiceDatabase.X)` ile seçer ve kendi kullanıcısıyla bağlanır. B-42: Postgres superuser şifresi her çalıştırmada rastgele; `superuserConnection(...)` yalnız test temizliği içindir (yalnız eklenen tabloları servis kullanıcısı silemez). `DocumentIntegrationTest` temizliği bununla ve `session_replication_role = replica` ile (tetikleyiciler atlanarak) yapar; servis kodu testte de kendi kullanıcısıyla çalışır, koruma etkin kalır. B-44: compliance-service, B-16 kataloğu ve DejaVu Sans için extraction-service'in test-jar'ına test scope'ta, geçişli bağımlılıklar `*:*` dışlanarak bağlanır (extraction'ın Spring AI'ı compliance testlerine girmesin). test-jar `package` fazında oluşur; tek modül komutu `-am` ile `verify` (ya da `package`) çalıştırılmalı. B-46: `llm-support` modülü (LLM semaforu). Sistem testlerinde uyum gerçektir: yığın kurulurken her B-16 tedarikçisine faturayla uyumlu bir sözleşme (`SyntheticContracts.compliantWith`) compliance API'sinden yüklenir; sahte Ollama `/api/embed` (sözcük torbası) ve uyum sorularını (`ClauseAnswerer`, "iyi LLM") yanıtlar; `ContractComplianceSystemTest` kendi rastgele VKN'siyle fiyat aşımı (US-06) ve sözleşmesizlik (US-07) senaryolarını sınar. compliance-service bu yüzden test-jar üretir; boot jar'ı `-exec` sınıflandırıcılıdır.

### docker-compose.yml iskeleti

```yaml
name: invoice-system

x-app: &app
  restart: unless-stopped
  # env_file yok (B-26): secret'lar servis başına ${…:?} ile verilir
  environment:
    SPRING_RABBITMQ_HOST: rabbitmq
    REDIS_HOST: redis
    OLLAMA_BASE_URL: http://host.docker.internal:11434
  extra_hosts: ["host.docker.internal:host-gateway"]
  depends_on:
    postgres: { condition: service_healthy }
    rabbitmq: { condition: service_healthy }
    redis:    { condition: service_healthy }

services:
  postgres:
    image: pgvector/pgvector:pg17
    profiles: ["infra", "all"]
    environment: { POSTGRES_PASSWORD: ${POSTGRES_ADMIN_PASSWORD} }
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./infra/postgres/init:/docker-entrypoint-initdb.d:ro
    ports: ["5432:5432"]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U postgres"], interval: 5s, retries: 10 }

  rabbitmq:
    image: rabbitmq:4-management
    profiles: ["infra", "all"]
    volumes:
      - rabbitdata:/var/lib/rabbitmq
      - ./infra/rabbitmq/rabbitmq.conf:/etc/rabbitmq/rabbitmq.conf:ro
      - ./infra/rabbitmq/definitions.json:/etc/rabbitmq/definitions.json:ro
    ports: ["5672:5672", "127.0.0.1:15672:15672"]
    healthcheck: { test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"], interval: 10s, retries: 10 }

  redis:
    image: redis:7-alpine
    profiles: ["infra", "all"]
    ports: ["6379:6379"]
    healthcheck: { test: ["CMD", "redis-cli", "ping"], interval: 5s, retries: 10 }

  document-service:
    <<: *app
    build: { context: ., dockerfile: document-service/Dockerfile }
    profiles: ["all"]
    ports: ["8081:8080"]
    volumes: ["documents:/data/documents"]

  extraction-service:
    <<: *app
    build: { context: ., dockerfile: extraction-service/Dockerfile }
    profiles: ["all"]
    volumes: ["documents:/data/documents:ro"]

  compliance-service:
    <<: *app
    build: { context: ., dockerfile: compliance-service/Dockerfile }
    profiles: ["all"]
    ports: ["8082:8080"]
    volumes: ["contracts:/data/contracts"]

  rpa-service:
    <<: *app
    build: { context: ., dockerfile: rpa-service/Dockerfile }   # FROM mcr.microsoft.com/playwright/java
    profiles: ["all"]
    environment:
      PORTAL_URL: http://mock-portal:8080
    volumes: ["screenshots:/data/screenshots"]

  mock-portal:
    build: { context: ., dockerfile: mock-portal/Dockerfile }
    profiles: ["all"]
    ports: ["8090:8080"]

volumes: { pgdata: {}, rabbitdata: {}, documents: {}, contracts: {}, screenshots: {} }
```

rpa-service'in taban image'ı (`mcr.microsoft.com/playwright/java`) JDK 25 ile gelir; diğer servislerle aynı JDK kullanılır.

Build context repo köküdür, çünkü servisler `common-messaging` modülüne bağlıdır; her modülün kendi multi-stage Dockerfile'ı vardır (`eclipse-temurin:25-jdk` ile `./mvnw -pl <modül> -am package`, `eclipse-temurin:25-jre` üzerinde root olmayan kullanıcıyla çalışma).

`<<: *app` ile `environment` bloğu birleşmez, üzerine yazılır; bu yüzden ortak değişkenler ayrı bir `x-app-env` anchor'ındadır ve rpa-service `<<: *app-env` ile kendi değişkenini ekler. Her uygulama servisi Actuator health'i healthcheck olarak açar; imajlarda curl olmadığı için kontrol bash `/dev/tcp` ile yapılır. Güncel hâli için `docker-compose.yml` esastır; bu iskelet özet niteliğindedir.

### Bellek bütçesi (18 GB, B-31'de ölçüldü)

Ölçüm 2026-10-02, M3 Pro 18 GB, Docker Desktop VM 7,7 GB / 8 CPU, `qwen2.5:7b-instruct` (`num_ctx` 8192). Yük: 6 fatura aynı anda yüklendi, 76 sn'de hepsi `POSTED`. Limit = yük altında tepenin ~2 katı (`docker-compose.yml`, `mem_limit`).

| Bileşen                      | Boşta   | Yük altında tepe | Limit             |
| ---------------------------- | ------- | ---------------- | ----------------- |
| document-service             | 408 MiB | 410 MiB          | 1 GB              |
| extraction-service           | 470 MiB | 479 MiB          | 1 GB              |
| compliance-service           | 383 MiB | 386 MiB          | 1 GB              |
| rpa-service (Chromium dahil) | 304 MiB | 484 MiB          | 1,5 GB            |
| mock-portal                  | 319 MiB | 320 MiB          | 1 GB              |
| postgres                     | 103 MiB | 104 MiB          | 512 MB            |
| rabbitmq                     | 140 MiB | 141 MiB          | 512 MB            |
| redis                        | 12 MiB  | 17 MiB           | 128 MB            |
| **Konteynerler**             | ~2,1 GB | **~2,3 GB**      | üst sınır ~6,6 GB |
| Ollama (macOS, compose dışı) | 4,6 GB  | **4,9 GB**       | —                 |

Tepe toplam ~7,2 GB; limitlerin tamamı dolsa ~11,5 GB, 18 GB'ın içinde (NFR-08). JVM imajları heap için limitin %65'ini kullanır (`-XX:MaxRAMPercentage=65`; 1 GB'ta heap tavanı 666 MB, rpa'da 1 000 MB), kalanı heap dışı belleğe (metaspace, thread'ler; servis başına ~200–250 MB) ve rpa'da Chromium'a kalır. %75'te dar limitle heap genişleyince konteyner OOM ile öldürülürdü.

RabbitMQ cgroup limitini görmez, bellek alarmını VM'in toplamından hesaplar (ölçüldü: 4,99 GB; alarm hiç çalmadan konteyner 512 MB'ta öldürülürdü). `rabbitmq.conf`'ta `total_memory_available_override_value = 512MB`: alarm eşiği %60 = 307 MB. Disk alarmı `disk_free_limit.absolute = 1GB` (varsayılan 50 MB'ta disk alarm çalmadan doldu ve broker metadata'sı silindi, B-30).

**Disk:** proje imajları ~6,3 GB (rpa-service 3,8 GB, Playwright tabanı), volume'lar < 100 MB. Diski dolduran tekrarlanan derlemelerdir: her derleme eski imajı etiketsiz bırakır, Maven derleme önbelleği 23 GB'a ulaştı. Docker'da derleme için en az ~10 GB boş yer bırakılmalı; temizlik `docker image prune -f` (etiketsiz imajlar) ve gerekirse `docker builder prune -f`. Volume'lar silinmez (geliştirme veritabanları).

### Ollama (host)

- Kurulum: `brew install ollama`; LLM (7-8B, 4-bit) ve `bge-m3` çekilir. Model adları `.env`'de.
- `OLLAMA_NUM_PARALLEL` ve Redis `sem:llm` (2 izin) eşzamanlılığı birlikte sınırlar.
- README demo adımları: Ollama'yı başlat → `docker compose --profile all up` → örnek fatura yükle.

## 7. Karar kayıtları (ADR)

Yirmi bir karar kayıtlıdır; ADR-17 bu dokümanda önerildi, ADR-19 B-04'te, ADR-20 B-05'te, ADR-21 B-10'da alındı, diğerleri kaynak dokümanlardan gelir. Açık kararlar bölümün sonundadır.

### ADR-01 Orkestrasyon, tek durum sahibi

**Durum:** Kabul · v1

**Karar:** Faturanın durumu yalnızca document-service'te yaşar; sonraki adımı her zaman o başlatır. Diğer servisler komut alır, olay yayınlar.

**Neden:** Öncelik kuralı, eşik ve mükerrer kararları tek yerde olur; durum makinesi tek başına test edilir; "fatura nerede?" tek sorguyla yanıtlanır.

**Alternatif:** Koreografi (her servis bir sonrakini tetikler). Akış servislere dağılır, durum dört veritabanından yeniden kurulmak zorunda kalır.

**Bedeli:** document-service merkezidir; kapalıyken akış durur ama mesajlar kuyrukta bekler, kayıp olmaz.

### ADR-02 Mesaj aracısı: RabbitMQ

**Durum:** Kabul · v1

**Karar:** Servisler arası tüm iletişim RabbitMQ 4.x üzerinden; komutlar direct, olaylar topic exchange.

**Neden:** İş yükü "iş kuyruğu"dur: mesaj başına ack/nack, DLX, quorum kuyrukta teslim limiti, `x-single-active-consumer` ve TTL ile bekleme odası yerleşik gelir. Tek düğüm hafiftir (18 GB sınırı) ve Spring AMQP olgundur.

**Alternatifler:** Kafka: log ve offset tabanlıdır; mesaj başına retry ve DLQ yerleşik değildir, bozuk mesaj partition'ı tıkar, replay ihtiyacı bu projede yoktur. Redis Streams: Redis'i doğruluk kaynağı yapar (ADR-07 ile çelişir), DLQ semantiği elle yazılır.

**Bedeli:** Olay geçmişini yeniden oynatma yok; tam olarak bir kez teslim yok, bu yüzden inbox gerekir (ADR-05).

### ADR-03 Quorum kuyruk, teslim limiti 3, kuyruk başına DLQ

**Durum:** Kabul · v1

**Karar:** Her iş kuyruğu quorum, `x-delivery-limit = 3`, `at-least-once` dead-letter stratejisi ve kendi `.dlq`'su.

**Neden:** Bozuk mesaj sınırlı denemeden sonra kenara çekilir, arkasındakileri bekletmez (NFR-03). Limit 3 = ilk teslim + 3 yeniden teslim; 4. başarısız teslimde DLQ (B-07'de ölçüldü). Teslim sayacı broker'da tutulduğu için servis çökse de sıfırlanmaz.

**Alternatif:** Classic kuyruk + Spring retry interceptor. Sayaç uygulama belleğindedir, çökmede sıfırlanır; classic kuyrukta at-least-once dead-lettering yoktur.

**Bedeli:** RabbitMQ 4.x varsayılanı 20 olduğu için limit her kuyrukta açıkça verilmeli; `reject-publish` overflow zorunlu.

### ADR-04 Transactional outbox, polling relay

**Durum:** Kabul · v1, istisnasız tüm yayınlar

**Karar:** Giden mesaj iş verisiyle aynı transaction'da `outbox`'a yazılır; relay 500 ms'de bir `FOR UPDATE SKIP LOCKED` ile okuyup publisher confirm ile yayınlar.

**Neden:** DB yazımı ile yayın atomik olur; broker kapalıyken yükleme API'si çalışır.

**Alternatifler:** Commit sonrası doğrudan publish: commit ile publish arasında çökme mesajı kaybeder, kayıt ara durumda takılır. CDC (Debezium): doğrudur ama Kafka Connect ağırlığı bu ölçek ve bellek için fazla.

**Bedeli:** \~500 ms gecikme ve en az bir kez teslim.

**Uygulama (B-05):** Tur başına tek transaction; kilit confirm'lere kadar tutulur. Kiralama (`claimed_until` kolonu, yayın transaction dışında) bu ölçekte getirisi olmadığı için seçilmedi. Zamanlama `@Scheduled` değil, relay'in kendi `SmartLifecycle` thread'i: kapanışta süren tur bitirilir, dolu partide beklemeden devam edilir.

### ADR-05 Inbox Postgres'te, iş transaction'ının içinde

**Durum:** Kabul · v1

**Karar:** İşlenen `messageId` + consumer, iş verisiyle aynı transaction'da `inbox`'a yazılır.

**Neden:** Transaction geri alınırsa inbox satırı da gider ve tekrar teslimde iş yeniden yapılır; doğru davranış budur.

**Alternatif:** Redis'te işlenmiş ID kümesi. İş transaction'ına giremez; "DB commit oldu, Redis yazılamadı" mesajın iki kez ya da hiç işlenmemesine yol açar.

**Bedeli:** Tablo büyür; 30 günden eski satırlar zamanlanmış işle silinir.

**Uygulama (B-06):** Inbox elle değil dinleyicide uygulanır (§5, "Inbox dinleyicide"); `consumer` kuyruk adıdır. Elle çağrı (unutulabilir) ve her işi tek transaction'a sokmak (LLM/RPA dakikalarca bağlantı tutar) seçilmedi; kısa ve uzun iş için iki handler türü var. Temizlik her serviste kütüphanenin kendi thread'inde çalışır; birden fazla örnek aynı anda silse de zararsızdır.

### ADR-06 Database-per-service, tek Postgres konteyneri

**Durum:** Kabul · v1

**Karar:** Tek konteynerde dört veritabanı, her birine ayrı kullanıcı; bir servisin kullanıcısı başkasının veritabanına bağlanamaz. Servisler arası veri yalnızca mesajla taşınır.

**Alternatifler:** Dört Postgres konteyneri: aynı izolasyon, dört kat bellek. Ortak veritabanı: servisler birbirinin tablosuna bağlanır, sınır kaybolur.

**Bedeli:** Postgres çökerse tüm servisler etkilenir; yerel geliştirme için kabul.

### ADR-07 Redis yalnızca kısa ömürlü koordinasyon

**Durum:** Kabul · v1 (semafor), v2 (kilit, oturum)

**Karar:** Redis hiçbir verinin doğruluk kaynağı değildir; üç kullanımı vardır: `sem:llm` (Redisson `RPermitExpirableSemaphore`, 2 izin, kiralı; Redis yokken servis 1 izinli yerel semafora düşer, B-18), `lock:rpa:{supplierVkn}:{invoiceNo}`, `rpa:portal:session`. `sem:llm` iki servisçe paylaşılır (extraction, compliance); semafor B-46'da ortak `llm-support` modülüne taşındı (`LlmSemaphore`, `LlmRedisson`); bean'i her servis kendi ayarlarından kurar (izin sayısı, kira = LLM zaman aşımı × 2, bekleme, yerel izin).

**Neden:** Redis tamamen silinse fatura kaybolmaz; en kötü ihtimalle iş yavaşlar. Semafor servisler arasıdır, çünkü iki servis aynı GPU'yu paylaşır. Kilit anahtarı `documentId` değil, portalın tanıdığı iş anahtarıdır.

**Bilinçli olarak Redis'te olmayanlar:** inbox (ADR-05), yükleme tekrar kontrolü (unique kısıt yeter), durum sorgu önbelleği (bayat veri riski), eşik ayarları (bellek içi önbellek yeter).

**Bedeli:** Kira tabanlı kilit GC duraklamasında el değiştirebilir; bu yüzden kilit verimlilik korumasıdır, son garanti portalda ön aramadır.

### ADR-08 v1'de RPA için tek aktif consumer

**Durum:** Kabul · v1

**Karar:** `rpa.post-to-portal.q` üzerinde `x-single-active-consumer`, prefetch 1.

**Neden:** Kural konfigürasyondan topolojiye taşınır; ikinci bir rpa-service örneği açılsa bile yedekte bekler.

**Alternatif:** Yalnızca `concurrency = 1`. İkinci örnek açılınca iki bot aynı anda çalışır.

**Bedeli:** RPA verimi tek bot; giriş sırasında çökme ve yeniden teslimde çift kayıt riski v2'ye kadar kalır. Devir anında (B-27'de görüldü) iki örnek kısa süre _farklı_ faturalarda birlikte çalışabilir: aktif örneğin listener'ı durunca Spring önce `basic.cancel` gönderir, sürmekte olan girişi sonra bitirip ack'ler; broker yedeği cancel'da aktif yapar. FR-R8 fatura bazında korunur; kesin tek bot v2'deki kilitle (FR-R7). Aynı faturanın iki örnekte birden girilmemesi için kapanışta listener girişi sonuna kadar bekler (§4.4, kapanış zinciri).

### ADR-09 RAG yalnızca "hangi madde?" sorusunu yanıtlar

**Durum:** Kabul · v2

**Karar:** Sözleşme seçimi SQL, sayı karşılaştırması Java ile yapılır; LLM yalnızca maddeden değeri ve dayandığı alıntıyı çıkarır, alıntı chunk'ta birebir doğrulanır. Vektör arama sözleşmeye filtrelenmiş tam taramadır; HNSW indeksi yok.

**Neden:** Hatalı olabileceği varsayılan LLM karar zincirinin en zayıf halkası olmaktan çıkar; her bulgu madde ve alıntıyla denetlenebilir.

**Alternatifler:** LLM'e "uyumlu mu?" diye sormak: denetlenemez. Sözleşmeyi embedding ile seçmek: başka sözleşmenin benzer maddesi gelebilir. HNSW + sonradan filtre: eşleşen satırları kaçırabilir; bir sözleşmenin onlarca chunk'ı için gereksiz.

**Bedeli:** Yeni kontrol türü kod değişikliği ister.

### ADR-10 Madde sınırında chunking

**Durum:** Kabul · v2

**Karar:** Her madde bir chunk; uzun madde paragraftan bölünür ve her parçaya başlık eklenir; kalıp yoksa \~400 token, 50 token örtüşme.

**Neden:** Onaycıya "Madde 7.2" diye kaynak gösterilebilir (US-06); başlıklı parça bağlamını korur.

**Alternatif:** Sabit boyutlu bölme. Madde sınırlarını keser, kaynak gösterme zayıflar.

### ADR-11 Dosya paylaşımı: içerik adresli, salt okunur volume

**Durum:** Kabul · v1 (A2, B-10'da kapandı)

**Karar:** PDF'ler `/data/documents/{sha256}.pdf` yolunda değişmez saklanır; extraction-service volume'u salt okunur bağlar, mesajdaki `storageUri` ile okur.

**Neden:** Dosya hiç değişmediği için paylaşım veri sahipliği ihlali sayılmaz; MinIO'ya geçişte yalnızca URI şeması değişir.

**Alternatif:** document-service'te `GET /internal/documents/{id}/content`. Sınır daha temiz, ama extraction-service document-service'in ayakta olmasına bağlanır.

### ADR-12 Yerel LLM: Ollama + Spring AI

**Durum:** Kabul · v1

**Karar:** 7-8B sınıfı 4-bit model ve bge-m3 Ollama'da, macOS üzerinde; erişim Spring AI soyutlaması arkasında, model adı konfigürasyonda. Başlangıç sohbet modeli `qwen2.5:7b-instruct` (B-18; yapılandırılmış JSON'da güçlü, Türkçe dahil çok dilli), ölçüm B-29'da. Spring AI 2.0.1 (BOM kök pom'da). B-29: istekler sabit bağlam penceresi (`num_ctx` 8192) ve çıktı sınırı (`num_predict` 4096) ile gönderilir; Ollama'nın makineye göre seçtiği varsayılana bırakılmaz, sınıra dayanan çıktı güvenilmez sayılır.

**Neden:** Veri makineden çıkmaz (NFR-11), ücretli API yok; model değişimi kod değişikliği gerektirmez (NFR-09). bge-m3 çok dillidir (NFR-14).

**Alternatif:** Bulut LLM API'si. Maliyet ve veri gizliliği; ayrıca proje kısıtı dışında.

**Bedeli:** Çıkarım kalitesi sınırlı → kural doğrulama, güven skoru ve insan incelemesi; verim düşük → semafor.

### ADR-13 Güven eşiği kararı document-service'te

**Durum:** Kabul · v1

**Karar:** extraction-service yalnızca skoru ölçer; eşikle karşılaştırmayı document-service yapar.

**Neden:** Eşik iş kuralıdır, FR-A2 ile değişir ve mükerrer kontrolüyle birlikte öncelik kuralına girer.

**Alternatif:** extraction-service'in `NEEDS_REVIEW` kararı vermesi. Karar iki servise bölünür.

### ADR-14 Sözleşme yükleme compliance-service'te

**Durum:** Kabul · v2

**Karar:** Sözleşme API'si doğrudan compliance-service'tedir.

**Neden:** Sözleşme verisinin tek sahibi odur.

**Alternatif:** document-service üzerinden geçirmek. compliance'ın verisini başka servisin API'sine bağlar.

**Bedeli:** İstemci iki taban URL bilir; ölçek büyürse API gateway (kapsam dışı).

### ADR-15 Servisler arası senkron çağrı yok

**Durum:** Kabul · v1

**Karar:** REST yalnızca insanların girdiği kapılarda; LLM, RAG, RPA gibi uzun işler HTTP isteğinin içinde yapılmaz, yükleme 202 döner.

**Neden:** compliance-service çöktüğünde document-service API'si çalışmaya devam eder; 500 ms hedefi (NFR-07) tutar.

**Alternatif:** document → compliance REST çağrısı. Zamansal bağlantı ve zincirleme hata.

### ADR-16 v1 topolojisi = v2 topolojisi

**Durum:** Kabul · v1

**Karar:** Tüm kuyruklar, DLQ'lar, outbox ve inbox ilk günden kurulur; compliance v1'de stub olarak gerçek mesaj akışına katılır.

**Neden:** v2 yeni mesaj eklemez, iskeletin içine mantık ekler; mesaj sözleşmesi değişmez.

**Alternatif:** v1'i sade kurup v2'de topolojiyi genişletmek. Mesaj sözleşmeleri ve testler yeniden yazılır.

### ADR-17 RabbitMQ topolojisi `definitions.json` ile

**Durum:** Önerildi (bu doküman)

**Karar:** Exchange, kuyruk, argüman ve binding'ler RabbitMQ açılışında `definitions.json`'dan yüklenir; servisler topoloji ilan etmez.

**Neden:** Topoloji tek dosyada görünür ve servislerin açılış sırasından bağımsızdır; quorum argümanları bir kez yazılır.

**Alternatif:** Her serviste Spring `Declarables`. Argümanlar servislere dağılır, uyuşmazlıkta kuyruk ilanı hata verir.

**Bedeli:** Testcontainers testlerinde aynı dosya ve `init-user.sh` kullanılmalı (B-07: `test-support` bunu yapar; testler topolojiyi kuramaz, gerçeğini kullanır). Varsayılan guest oluşturulmadığı için uygulama kullanıcısı gerekir; şifresi git'e girmesin diye kullanıcı ve izinler dosyada değil, açılışta `init-user.sh` ile `.env`'den üretilir (password_hash, `rabbitmqctl hash_password` ile). Definitions mevcut nesnelerin üzerine yazmaz: `rabbitdata` volume'u dururken şifre ya da kuyruk argümanı değişikliği uygulanmaz, volume sıfırlanmalıdır.

### ADR-18 Yeniden işletme durum geçişiyle, DLQ'dan taşımayla değil

**Durum:** Kabul · v2

**Karar:** DLQ mesajı `dead_letters` tablosuna park edilip ack edilir; yeniden işletmede document-service `RPA_FAILED → QUEUED_FOR_RPA` geçişini yapar ve yeni `PostToPortal` yayınlar.

**Neden:** Kayıt `RPA_FAILED` iken gelen `RpaCompleted` reddedilir; mesajı doğrudan kuyruğa taşımak sistem ile portalı ayırır.

**Alternatif:** Yönetim arayüzünden mesajı aslı kuyruğa geri taşımak (shovel). Geçersiz geçiş ve yetim olay.

### ADR-19 Manuel ack, açık `basic.reject`

**Durum:** Kabul · v1 (B-04)

**Karar:** Listener container'ları `AcknowledgeMode.MANUAL` ile kurulur (`InvoiceListenerContainers`). `common-messaging`'deki `InvoiceMessageListener` mesajı çözer, `type`'a göre `MessageHandler`'ı çağırır ve sonucu kendisi gönderir: başarıda `basicAck`, zehirli mesajda `basicReject(requeue=false)`, diğer istisnalarda `basicReject(requeue=true)`. Servisler `@RabbitListener` kullanmaz, yalnızca handler yazar.

**Neden:** RabbitMQ 4.3'te teslim sayacını `basic.reject` artırır, `basic.nack` artırmaz (B-03). Spring AMQP 4.1.1'in AUTO modu istisnada `basicNack(multiple=true)` gönderir; hatalı mesaj DLQ'ya hiç düşmez. MANUAL modda container istisnada kendisi nack göndermez (`BlockingQueueConsumer.rollbackOnExceptionIfNecessary`), karar tamamen bizdedir. Merkezi dinleyici inbox (B-06), MDC (B-08) ve DB retry için tek bağlanma noktasıdır.

**Alternatif:** `DirectMessageListenerContainer`: o da rollback'te `basicNack` gönderir, sorunu çözmez. Düzeltmeyi içeren Spring AMQP sürümü: bilinen bir sürüm yok, Boot'un yönettiği sürümü ezmek gerekir.

**Bedeli:** `@RabbitListener` ve Boot'un listener ayarları (`spring.rabbitmq.listener.*`) kullanılmaz; container'lar kuyruk başına programatik kurulur, prefetch ve eşzamanlılık elle verilir. Dinleyici handler istisnasını dışarı sızdırmamalıdır; sızarsa mesaj kanal kapanana dek unacked kalır.

### ADR-20 Ortak bileşenler auto-configuration ile, ortak migration'lar `V0_x` aralığında

**Durum:** Kabul · v1 (B-05)

**Karar:** `common-messaging` bileşenlerini (dönüştürücü, `OutboxWriter`, `OutboxRelay`, `OutboxCleaner` (A7), `Inbox`, `InboxCleaner`, `InvoiceMessageListenerFactory`) Spring Boot auto-configuration ile kurar; servis yalnızca bağımlılığı ekler. Ortak tabloların migration'ları kütüphanede `db/migration/common/V0_x__*.sql` olarak durur (outbox `V0_1`, inbox `V0_2`); servis migration'ları `V1`'den başlar. Varsayılan `classpath:db/migration` özyinelemeli tarandığı için ortak migration'ları zaten kapsar; servis konumları değiştirirse bir `FlywayConfigurationCustomizer` ortak konumu ekler.

**Neden:** Dört serviste tablo ve relay birebir aynı kalır, servis başına tekrar eden konfigürasyon olmaz.

**Alternatif:** Her serviste açık `@Import` ve kendi outbox migration'ı: her şey görünür ama dört kopya zamanla sapar.

**Bedeli:** Ortak migration'lar servis migration'larından önce gelmek zorundadır; ortak tabloda ileride değişiklik gerekirse servis migration'ına yazılır (yoksa Flyway sıra dışı sürümü reddeder). Auto-config örtüktür; hangi bean'in neden kurulduğu koşullardan okunur.

### ADR-21 document-service'te veri erişimi `JdbcClient` ile

**Durum:** Kabul · v1 (B-10)

**Karar:** document-service veritabanına `JdbcClient` ve açık SQL ile erişir; ORM yoktur.

**Neden:** Çekirdek sorgular zaten Postgres'e özgü: `INSERT … ON CONFLICT … RETURNING` (yükleme) ve `UPDATE … WHERE status = ? AND version = ?` (koşullu geçiş). `common-messaging` ile aynı yaklaşım; yeni bağımlılık yok.

**Alternatif:** Spring Data JDBC: bu iki sorgu yine `@Query` ile elle yazılır, kazanç az. JPA: aynı sorgular native SQL ister; persistence context, dirty checking ve kendi optimistic lock'u ayrıca anlatılması gereken bir katman ekler.

**Bedeli:** Satır eşleme elle yazılır.

### ADR-22 API kimlik doğrulaması: HTTP Basic, kullanıcılar yapılandırmada

**Durum:** Kabul · v2 (B-43)

**Karar:** document-service API'si Spring Security ile HTTP Basic kullanır (durumsuz, CSRF kapalı). Kullanıcılar ve rolleri `application.yml`'de, şifreleri `.env`'den gelir ve bellekte BCrypt'lenir. Roller: `EXPERT`, `APPROVER`, `ADMIN`.

**Neden:** Portföy kapsamında rol ayrımını ve "onaycı kendi yüklediğini onaylayamaz" kuralını göstermek için yeterli; yeni servis yok, tek bağımlılık (`spring-boot-starter-security`) Boot'un yönettiği sürümde. Tarayıcı arayüzü ve oturum çerezi olmadığı için CSRF koruması gerekmez.

**Alternatif:** JWT + kimlik sağlayıcı (Keycloak): gerçeğe en yakın, ama compose'a yeni servis, realm yapılandırması ve bellek bütçesine (B-31) yük. Veritabanında kullanıcı tablosu: esnek, ama kullanıcı yönetimi API'si ve ilk yöneticinin oluşturulması kapsamı büyütür. Bağımlılıksız kendi filtremiz: güvenlik kodunu elle yazmak (sabit zamanlı karşılaştırma, 401/403 ayrımı).

**Bedeli:** Basic şifreyi her istekte taşır; gerçek kurulumda TLS arkasında çalışmalıdır. Kullanıcı eklemek ya da şifre değiştirmek yapılandırma değişikliği ve yeniden başlatma ister. document-service, compliance-service ve extraction-service'in (B-47, ham LLM çıktısı; `ADMIN` ve `EXPERT`) dış API'leri korunur (B-45: aynı kullanıcılar ve `.env` şifreleri; sözleşme yükleme `EXPERT`, okuma giriş yapmış herkes); güvenlik yapılandırması iki serviste küçük bir tekrardır. mock-portal kendi oturumuyla çalışır, servisler arası HTTP yoktur (ADR-15). Bulgu (B-46 sonrası NFR-07 ölçümü): BCrypt her istekte şifre doğrular, yükleme p95'i ~75 ms artırdı (16 → 91 ms; sınır 500 ms). Gerekirse doğrulanmış kimlik kısa süre önbelleğe alınabilir.

### Açık kararlar

- [x] **A1 — Ollama'ya hiç ulaşılamaması** (B-19): deneme sayılmaz. Bekleme odası (yeni kuyruk, ADR-16 değişirdi) yerine dinleyici durdurulur: bağlantı hatasında mesaj geri konur (teslim sayacı kesinti başına bir kez artar), `extractInvoiceContainer` durur, Ollama `llm.availability-probe-interval` (30 sn) aralıkla `GET /api/tags` ile yoklanır, yanıt gelince dinleyici yeniden başlar (`OllamaAvailability`). Bu sırada `ollama` health göstergesi `UNKNOWN`'dır, servisin genel durumu `UP` kalır. Yalnızca bozuk çıktı ve zaman aşımı bütçeyi tüketir. Bağlantı zaman aşımı kısadır (2 sn), ulaşılamama hızlı anlaşılır.
- [x] **A2 — Dosya paylaşımı:** salt okunur ortak volume seçildi (ADR-11, B-10). `storageUri = file:///data/documents/{sha256}.pdf`; extraction-service volume'u salt okunur bağlar.
- [x] **A3 — Takılı kayıt dedektörü** v2'de komutu aynı `messageId` ile otomatik yeniden yayınlasın mı? Inbox sayesinde güvenli; önce alarm verisiyle sıklık görülmeli. **Kapandı (B-48): hayır.** Veri: bugüne kadarki bütün sistem testleri (çökme ve mesajlaşma hatası testleri dahil) ve gerçek yığın çalışmalarında (smoke, NFR-07, uyum ölçümleri) takılı kayıt alarmı hiç çalmadı; çökmelerde relay + teslim limiti + DLQ kaydı her zaman ilerletti. Takılı kayıt çoğunlukla bir dış bağımlılığın (Ollama, portal) kapalı olduğunu gösterir; otomatik yeniden yayın kuyruğu şişirir, sorunu çözmez. Yerine `invoice_documents_stuck` göstergesi ve `InvoiceDocumentsStuck` alarmı; müdahale yöneticide. Gerçek kullanımda sık görülürse yeniden açılır.
- [x] **A4 — Geç olay politikası:** `RPA_FAILED` iken gelen `RpaCompleted` şimdilik yalnızca kaydediliyor; doğrudan `POSTED`'a geçiş durum tablosunu değiştirmeyi gerektirir. Ek (B-11): v2'deki `RPA_FAILED → QUEUED_FOR_RPA → RPA_FAILED` döngüsünde önceki turdan kalan geç bir olay sonraki turdaki aynı duruma uygulanabilir; durum koşulu da `version` da bunu ayırt edemez, olayın hangi komuta ait olduğunu taşıması gerekir. **Kapandı (B-38):** `documents.pending_rpa_command_id` bekleyen `PostToPortal`'ın kimliğini tutar (normal akışta ve yeniden işletmede `RpaDispatch` yazar). `PostToPortal` DLQ mesajı bekleyen komut değilse (önceki tur) yalnızca park edilir, yeni turu `RPA_FAILED` yapmaz. `RPA_FAILED` iken gelen `RpaCompleted` `LATE_EVENT` olarak kalır; yönetici yeniden işletince ön arama kaydı bulur (`FOUND_EXISTING` → `POSTED`). Durum tablosu ve mesaj sözleşmesi değişmedi. Alternatifler: `RPA_FAILED → POSTED` geçişi (geç olay yönetici kararı olmadan durumu değiştirirdi), olaya `commandMessageId` eklemek (sözleşme değişikliği, geç `RpaCompleted` zaten doğruyu söylediği için kazanç sınırlı).
- [x] **A5 — Güven skoru formülü** ve eşiğin başlangıç değeri, 10 sentetik faturayla ölçüldükten sonra sabitlenecek. B-09'da `settings`'e yer tutucu `confidence_threshold = 0.80` ve `approval_amount_threshold = 100000.00` yazıldı. B-20'de ilk formül: ağırlıklı ceza (§4.2 tablosu); B-29'da gerçek Ollama çıktılarıyla ağırlıklar ve eşik kalibre edilecek. **Kapandı (B-29):** formül ve ağırlıklar §4.2'deki gibi, eşik 0,80 kalır. Gerçek modelle doğru çıkarılan faturalar 1,00, yanlış çıkarılanlar en fazla 0,45 aldı; eşik ikisini temiz ayırır. Eşiği düşürmek 8/10'a ulaştırırdı ama yanlış veriyi portala sokardı.
- [x] **A6 — Mock portal teknolojisi** (B-24): Spring Boot + Thymeleaf, kayıtlar bellekte, hata bayrağı fatura no deseniyle (§4.5). Alternatifler: Mustache / şablonsuz; H2 / Postgres `portal_db`; genel boolean / çalışırken aç-kapa uç noktası (FR-P1'in "API sunmaz"ı ile çelişirdi).
- [x] **A7 — Outbox temizliği:** yayınlanmış satırlar silinmiyor; relay sorgusu kısmi indeksle hızlı kalır ama tablo büyür. Inbox gibi N gün sonra silinsin mi, hata ayıklama için ne kadar tutulsun? Takılı satır (`attempts` yüksek) alarmı B-15 / B-48'e bırakıldı. **Kapandı (2026-10-03, B-49 sonrası):** `OutboxCleaner` (`common-messaging`, `InboxCleaner`'ın eşi) yayınlanmış ve saklama süresi (`invoice.messaging.outbox.retention`, 7 gün) dolmuş satırları saatte bir, 10.000'lik parçalarla siler; yayınlanmamış satıra dokunmaz, relay yalnız onları kilitlediği için çakışmaz. Bekleme odası aynı kimlikli satırı `republish` ile yeniden yazabildiği için (`published_at = NULL`) koşul dış `DELETE`'te de tekrarlanır: satır alt sorgudan sonra yeniden yazılmışsa Postgres koşulu yeni hâliyle değerlendirir, satır silinmez; önce silinmişse `republish` yeniden ekler. 7 gün: yayın izi hata ayıklama için bir hafta durur, durum geçmişi ve inbox (30 gün) ayrıca tutulur. Migration yok; silme sorgusu indekssiz tarar, bu hacimde sorun değil. Testler: `OutboxIntegrationTest` (yalnız süresi dolmuş yayınlanmış satırlar parça parça silinir, 30 günlük yayınlanmamış satır kalır; bekleme odasından yeniden yazılan satır silinmez ve relay onu yayınlar).

## 8. Sıralı backlog

Sıra bağımlılığa göredir: önce tüm servislerin paylaştığı mesajlaşma iskeleti, sonra akışın sırasıyla servisler. v1 (B-01–B-33) yan uğraş temposunda yaklaşık 3-4 haftalık hedeftir; her kilometre taşı çalışır bir dikey dilimle biter.

### M0 — İskelet ve ortak mesajlaşma (v1)

- [x] **B-01** Monorepo: çok modüllü build (`common-messaging`, dört servis, `mock-portal`); Java ve Spring Boot sürümlerini sabitle.
- [x] **B-02** `docker-compose.yml` + `infra` profili; Postgres init script'i (dört DB, dört kullanıcı, pgvector) (NFR-13).
- [x] **B-03** `definitions.json`: exchange'ler, kuyruklar, DLQ'lar, quorum argümanları, uygulama kullanıcısı, vhost ve izinler (ADR-03, ADR-17). Definitions yüklenince guest kullanıcısı oluşturulmaz; kullanıcı dosyada yoksa servisler bağlanamaz ve hata mesajı nedeni açık söylemez.
- [x] **B-04** `common-messaging`: zarf (`messageId`, `correlationId`, `type`, `x-schema-version`), sekiz mesaj record'u, JSON dönüştürücü, zehirli mesaj için reject-and-don't-requeue.
- [x] **B-05** Outbox tablosu + relay: `SKIP LOCKED`, publisher confirm, `mandatory` (NFR-02).
- [x] **B-06** Inbox tablosu, `exists` / `tryInsert`, 30 günlük temizlik (NFR-01 katman 1).
- [x] **B-07** Testcontainers taban sınıfı: RabbitMQ (definitions yüklü), Postgres, Redis (NFR-12). `test-support` modülü; B-05/B-06 testleri gerçek topolojiye taşındı, teslim limiti kanıt testi eklendi.
- [x] **B-08** JSON log ve `correlationId` MDC aktarımı (NFR-06).

### M1 — document-service (v1)

- [x] **B-09** Migration'lar: `documents`, `invoice_data`, `compliance_results`, `status_transitions`, `dead_letters`, `settings`.
- [x] **B-10** `POST /documents`: akışta SHA-256, `ON CONFLICT`, 202 / 200 `duplicate` (FR-D1, FR-D2, US-02).
- [x] **B-11** Durum makinesi: geçiş tablosu, koşullu `UPDATE`, `version`, geç olay kaydı; birim testler (FR-D3, FR-D4).
- [x] **B-12** Olay dinleyicileri: `ExtractionCompleted/Failed` (öncelik kuralı + eşik), `ComplianceCompleted`, `RpaCompleted`; sonraki komutu outbox'a yaz (FR-D9).
- [x] **B-13** `GET /documents/{id}` ve filtreli, sayfalı liste (FR-D5).
- [x] **B-14** Üç komut DLQ'su için dinleyici: `dead_letters`'a park + durum geçişi; olay DLQ'ları için derinlik uyarısı (FR-D10).
- [x] **B-15** Takılı kayıt dedektörü (v1: log/alarm) ve sahipsiz dosya temizliği.

### M2 — extraction-service (v1)

- [x] **B-16** Sentetik test seti: 10 Türkçe fatura PDF'i + beklenen JSON; en az biri toplamları tutmayan, biri metinsiz.
- [x] **B-17** PDFBox metin çıkarımı; metin yoksa `ExtractionFailed` (FR-E1). Bileşen düzeyinde (`PdfTextExtractor`); dinleyici, `extraction_db` ve `ExtractionFailed` yayını B-18'de işlem hattıyla birlikte bağlanır.
- [x] **B-18** Spring AI + Ollama, şemaya zorlanmış çıktı, prompt v1; Redis `sem:llm` ve yerel semafora düşüş (FR-E2, FR-E5, NFR-08). B-17'den devralınan: `ExtractInvoice` dinleyicisi (uzun iş), `extraction_db` bağlantısı, `NO_TEXT → ExtractionFailed` yayını.
- [x] **B-19** Parse hatası / zaman aşımı için hata mesajını prompt'a ekleyen sınırlı retry; tükenince `ExtractionFailed` (US-05). Açık karar A1'i burada kapat. B-18'den kalan: LLM çağrısına zaman aşımı uygulanmıyor (`llm.timeout` şimdilik yalnızca semafor kirasını belirliyor); B-18'de tek deneme yapılır, çözülemeyen çıktı `LLM_RETRIES_EXHAUSTED` (attempts=1), Ollama'ya ulaşılamaması mesajı geri koyar.
- [x] **B-20** Kural doğrulama, Türkçe sayı ayrıştırma, ilk güven skoru formülü (FR-E3, FR-E4, NFR-14).
- [x] **B-21** `extraction_runs` kaydı ve testler için stub LLM istemcisi (NFR-12).

### M3 — compliance stub (v1)

- [x] **B-22** `CheckCompliance` tüket, inbox + outbox ile `COMPLIANT` sonuçlu `ComplianceCompleted` yayınla (FR-C5). v1'de compliance_db'de servis migration'ı yok, yalnızca outbox ve inbox; `contracts`, `contract_chunks`, `compliance_checks` B-45/B-46'da gelir. Stub kısa iştir (`on`); v2'de LLM çağrısı gelince `onLongRunning`'e geçer.
- [x] **B-23** İlk uçtan uca duman testi: yükleme → `QUEUED_FOR_RPA` (RPA henüz yok). Biçim: `scripts/smoke-e2e.sh` (tam yığın + host'taki Ollama; `mvnw verify`'a girmez). Yığını `--wait` ile kurar, PDF'i yükler, durumu yoklar, sonunda `status_transitions`'ı, PostToPortal'ın outbox'tan yayınlandığını (`published_at`) ve kuyrukta beklediğini gösterir. Aynı PDF tekrar yüklenebilsin diye dosyanın sonuna `%%EOF`'tan sonra benzersiz bir PDF yorumu ekler (hash değişir, içerik değişmez; v2'de içerik bazlı mükerrer şüphesine (B-41) takılır). İlk ölçüm (2026-10-02, invoice-01, `qwen2.5:7b-instruct`): 11-19 sn, beş geçiş beklenen sırada, skor 1,00, `COMPLIANT`. Bulgu: `.env`'de boş `OLLAMA_CHAT_MODEL=` satırı `${…:varsayılan}`'ı devreye sokmuyordu; model `""` oluyor, her fatura teslim limitinden sonra DLQ → `NEEDS_REVIEW`'a düşüyordu. Artık `ExtractionProperties.Llm` boş modeli açılışta reddeder, betik de baştan kontrol eder. B-25'ten sonra betiğin hedefi `POSTED`'dır (kuyruk kontrolü yerine portal kayıt numarası ve `portal_submissions` gösterilir); B-23'ten kuyrukta kalan iki `PostToPortal` B-25'in ilk tam yığın çalışmasında girildi.

### M4 — mock portal ve rpa-service (v1)

- [x] **B-24** mock-portal: login, giriş formu, kayıt no, sayfalı liste ve arama; testler için basit "her zaman hata ver" bayrağı (FR-P1, FR-P2). Açık karar A6'yı burada kapat. Ayrıntılar ve seçici sözleşmesi §4.5'te. Testler gerçek port üzerinde JDK `HttpClient` ile (MockMvc / Playwright yok); şifre her çalıştırmada rastgele.
- [x] **B-25** rpa-service: Playwright login + form + kayıt no okuma, `portal_submissions`, `RpaCompleted` (FR-R1, FR-R2). Uzun iş (`onLongRunning`): değerler portal biçimine çevrilir (tutar yuvarlanmaz) → `portal_submissions` kendi transaction'ında `IN_PROGRESS` (+`attempt_count`) → Playwright ile login, form, `#ref-no` → `SUBMITTED` + inbox + `RpaCompleted` tek transaction'da. Belge zaten `SUBMITTED` ise portala dokunulmaz, kayıt no yeniden bildirilir. Kalıcı hata (çevrilemeyen değer, portalın form doğrulaması) → doğrudan DLQ; geçici (hata sayfası, zaman aşımı, eleman yok, reddedilen login) → teslim limiti → DLQ → `RPA_FAILED`. Tarayıcı mesaj başına açılıp kapanır (Playwright thread-safe değil; ~1-2 sn). Playwright 1.63.0 (Dockerfile imajıyla aynı, imajda tarayıcı indirme kapalı). `V1` yalnızca `portal_submissions`; `rpa_attempts` v2'de. Testler JVM içindeki gerçek mock-portal'a karşı headless Chromium ile (mock-portal boot jar'ı `exec` classifier'ında; portal context'inde DataSource / Rabbit / Flyway auto-config kapalı). İlk tam yığın (2026-10-02, invoice-01): yükleme → `POSTED` 28 sn, RPA adımı ~2 sn.
- [x] **B-26** Portal kimlik bilgileri ortam değişkeninden, loglarda maskeli (NFR-10). Env'den okuma ve `toString` maskelemesi B-25'te (`RpaProperties`, `PortalProperties`; boşsa servis açılmaz). Bulgu: `env_file: .env` her uygulama konteynerine 11 secret'ın hepsini veriyordu (Postgres yöneticisi dahil); kaldırıldı, secret'lar servis başına (§6). Testler: `PlaywrightCredentialLeakTest` (şifre doldurulduktan sonra / doldurulurken / reddedilen girişte istisna mesajı, neden zinciri, yığın izi; Playwright call log'u değeri içermiyor) ve `CredentialLogScanIntegrationTest` (giriş her denemede düşüp DLQ'ya gidene kadar bütün konsol çıktısı). Duman testi sonunda `.env`'deki her `*_PASSWORD` değerini bütün konteyner loglarında arar (değer ekrana ve süreç argümanlarına yazılmaz). Playwright'ın `DEBUG=pw:api` hata ayıklama ayarı doldurulan değerleri loglayabilir; açılmamalı (B-32).
- [x] **B-27** İki rpa-service örneğiyle single active consumer testi (FR-R8). `SingleActiveConsumerIntegrationTest`: iki rpa-service context'i aynı broker, DB ve JVM içi mock-portal'a bağlı; broker'ın bildirdiği durumlar (`single_active` / `waiting`, `InfrastructureContainers.rabbitmqctl`; AMQP consumer sayısı SAC'de beklemedekini göstermiyor). Devirden önce tüm faturaları ilk örnek tek bot olarak işler; aktif listener iş ortasında durunca yedek devralır; hiçbir fatura aynı anda iki kez işlenmez, her fatura portala bir kez girilir. Bulgular: (1) devirde iki örnek kısa süre farklı faturalarda birlikte çalışır — kabul edildi (ADR-08); (2) kapanışta listener sürmekte olan girişi yalnızca 5 sn bekliyordu, aynı faturanın iki örnekte girilmesi mümkündü — girişe toplam süre bütçesi (`submit-timeout`) ve kapanış zinciri eklendi (§4.4, `PlaywrightPortalBudgetTest`).

### M5 — Sağlamlaştırma ve v1 yayını

- [x] **B-28** Entegrasyon testleri: US-01, US-02, US-04, US-05; aynı mesajı iki kez yayınlama; yayın sırasında servisi öldürme; zehirli mesaj; sürekli hata veren portal → DLQ ve arkasındaki fatura işlenir. Düzenek: test-only `system-tests` modülü; gerçek `docker-compose.yml` + `system-tests/compose.system-tests.yml` ayrı proje adıyla (`invoice-system-it`, rastgele host portları, geliştiricinin `.env`'i okunmaz, secret'lar her koşuda rastgele). LLM test JVM'indeki sahte Ollama'dır (B-16'nın basılı değerleri; faturaya özel bozuk çıktı ve gecikme), konteynerler ona `host.docker.internal` ile ulaşır. Çökme gerçek `docker kill`. Varsayılan build'de derlenir ama çalışmaz: `./mvnw -Psystem-tests -pl system-tests -am verify -Dtest='*SystemTest' -Dsurefire.failIfNoSpecifiedTests=false` (imajlar önbellekteyse ~2 dk); yığın sonunda volume'larıyla kaldırılır (`-Dsystem-tests.keep=true` açık bırakır), loglar `system-tests/target/compose.log`. B-16 seti ve sahte Ollama extraction-service'in `test-jar`'ından gelir; extraction'ın boot jar'ı bu yüzden `exec` classifier'ındadır. Testler (9): `EndToEndSystemTest` (10 fatura → 8 `POSTED`, 9. ve 10. `NEEDS_REVIEW` ve portala girilmez; US-02; US-05 3 denemeyle `NEEDS_REVIEW`), `MessagingFaultsSystemTest` (`PostToPortal` ve `RpaCompleted` aynı `messageId` ile yeniden yayın → ikinci giriş ve geç olay yok; zehirli mesaj park edilir, arkasındaki `POSTED`; portalda hep hata veren `FAIL-…` faturası 4 denemeden sonra `RPA_FAILED`, arkasındaki `POSTED`), `CrashRecoverySystemTest` (document-service yayın sırasında, extraction-service LLM çağrısında, rpa-service portal girişi civarında öldürülür; hiçbir fatura takılı kalmaz, outbox'ta yayınlanmamış satır kalmaz). Sistemde hata bulunmadı; düzenekten öğrenilenler: Docker yeniden başlatılan konteynerin rastgele host portunu yeniden atar; park tablosunun `source_queue`'su DLQ değil asıl kuyruktur (`x-first-death-queue`). rpa-service giriş sırasında öldürülünce portalda çift kayıt doğrulanmaz (v1 bilinen kısıtı).
- [x] **B-29** Güven eşiğini 10 sentetik faturayla kalibre et; hedef en az 8/10 `POSTED` (açık karar A5). Başlangıç noktası (M2 sonu duman testi, 2026-10-02, `qwen2.5:7b-instruct`, prompt v1, Ollama 0.35.0): temizlerden 6/8 skor 1,00 (1, 3, 4, 5, 6, 8); 9 → 0,45 ve 10 → `NO_TEXT` doğru. Kalan iki model hatası: 2. faturada açıklamadaki sayılar (`Rulman 6204 ZZ`, `Hidrolik yağ 20 L`) miktar sanılıyor; 7. faturada (48 kalem, 2 sayfa) ~31. kalemden sonra değerler uyduruluyor. İkisini de kurallar yakaladı (0,15 ve 0,45). Aday çözümler: prompt v2 (sütun sırası, açıklamadaki sayıların açıklamaya ait olduğu), uzun tabloyu sayfa sayfa çıkarmak, daha büyük model. Ölçüm komutu `OllamaSmokeTest` Javadoc'unda (`-Dsmoke.ollama=true`). **Sonuç (2026-10-02, Ollama 0.35.0): 7/10 `POSTED`, hedef 8/10 karşılanmadı; eşik değişmedi (A5).** Ölçümler (temiz 8 faturadan skor 1,00 alan): v1 + num_ctx 4096 → 6/8; v1 + 8192 → 7/8; v2 + 8192 → 2. fatura düzeldi ama 5.'de tekrar döngüsü, 6.'da birim fiyat miktar yazıldı; v3 + 8192 → 6/8 (miktar `"150 kg"`); v3 + 8192 + birimli miktar ayrıştırma → 7/8 (yalnız 8. fatura: `"20 50 kg çuval"`). Bulgular ve değişiklikler: (1) **Bağlam taşması:** Ollama `num_ctx`'i makineye göre 4096 seçiyordu; 7. faturada (istem 2393 token) çıktı yazılırken bağlamın başı, yani fatura metni atılıyor (`context shift`, `truncated = 1`), model değer uyduruyordu. `invoice.extraction.llm.num-ctx: 8192` her istekle gönderilir; istem + çıktı bağlamı doldurursa (`usage`) çıktı güvenilmez sayılır. (2) **Çıktı sınırı:** `num_predict` yoktu; döngüye giren model durmuyordu ve Ollama istemci bağlantıyı kapatsa da üretmeye devam etti (tek istek 71 900 tokene ulaştı, tek işlem yuvasını kilitleyip sonraki istekleri zaman aşımına düşürdü). `max-output-tokens: 4096`; `done_reason=length` olan yanıt yarım sayılır. Bu iki durum `LlmOutputLimitException`'dır, yeniden denenmez (aynı istem yine taşar/döner), sonuç `ExtractionFailed(LLM_RETRIES_EXHAUSTED)` ve ayrıntıda neden. (3) **Şemada `maxLength`:** her metin alanının üst sınırı var, model bir alanın içinde döngüye giremez (`InvoiceSchema`). (4) **Prompt sürümleri** `invoice.extraction.llm.prompt-version` ile seçilir (`InvoicePrompt`); varsayılan v3 = v1 + "açıklamadaki sayılar miktar değildir" (anlamsal kural). v2'nin konuma dayalı kuralı ("miktar birim fiyattan hemen önceki sayıdır") ters tepti, karşılaştırma için kodda kalır. (5) **Miktar ayrıştırma** (`TurkishNumbers.parseQuantity`): tek sayı + birim kabul edilir (`"150 kg"` → 150); birden fazla sayı ya da para birimi içeren değer çözülmez, kurallar yakalar. Ölçüm: `OllamaSmokeTest`, `-Dsmoke.prompt`, `-Dsmoke.numCtx`, `-Dsmoke.only=…`; rapor `target/ollama-smoke-report-<prompt>-<numCtx>.md`. Bilinen kısıt (v1): açıklamadaki sayılar bazen miktar alanına karışıyor (8. fatura); kurallar yakalar, fatura `NEEDS_REVIEW`'a düşer. v2 yol haritası adayları: daha büyük model (`qwen2.5:14b-instruct`), uzun tabloda sayfa bazlı çıkarım, ölçüm setinin genişletilmesi (10 faturaya bakarak yapılan ayarlar aşırı uyum riski taşır).
- [x] **B-30** NFR-07 ölçümü: yükleme < 500 ms, tek sayfalık fatura uçtan uca < 2 dk. Ölçüm: `scripts/measure-nfr07.sh` (tam yığın + host'ta Ollama; rapor `target/nfr07/`). Yükleme 1 ısınma + 20 istekle ölçülür, ölçüt p95 (ısınma ölçüte girmez); yükleme için 10. fatura (yalnız resim) kullanılır: yol aynıdır ama LLM işi doğmaz, uçtan uca ölçümün önüne kuyruk yığılmaz. Uçtan uca: tek sayfalık temiz faturalar (01–06; B-39'dan beri 03 hariç, tutar eşiğinin üstünde) teker teker. Sonuç (2026-10-02, `qwen2.5:7b-instruct`, prompt v3, M3 Pro): yükleme p50 7 ms, **p95 16 ms**, maks 17 ms (ısınma 126 ms); uçtan uca **17–26 sn**, hepsi `POSTED`. NFR-07 karşılandı. B-41'den beri betik aynı yığında tekrar çalıştırılırsa faturalar mükerrer şüphesine düşer; betik "mükerrer değil" kararını verip devam eder, satır raporda `*` ile işaretlenir (süre karar ve ön arama dahil). Temiz ölçüm boş yığında. **B-46'dan sonra yeniden (2026-10-03, uyum kontrolü gerçek, API Basic Auth):** betik önce tedarikçilerin uyumlu sözleşmelerini yükler (süreye girmez). Yükleme p50 73 ms, **p95 91 ms** (öncekinden ~75 ms fazla: her istekte BCrypt şifre doğrulaması, B-43); uçtan uca **32–52 sn** (uyum kontrolü ~15–25 sn ekler; faturalar yığında daha önce yüklendiği için mükerrer kararı dahil). NFR-07 karşılanıyor.
- [x] **B-31** Tam yığın bellek ölçümü; konteyner limitlerini compose'a ve bölüm 6'ya yaz (NFR-08). Disk de ölçülmeli (B-30'da görüldü): Docker'ın sanal diski dolunca (`enospc`) RabbitMQ'nun iç veritabanı silindi (kullanıcı, vhost, kuyruklar); broker ayakta kaldığı için yalnız servislerin `ACCESS_REFUSED` almasıyla görüldü. Kurtarma: `docker compose restart rabbitmq` (`init-user.sh` + `definitions.json` her şeyi yeniden kurar, ADR-17); kuyruktaki mesajlar kaybolur, Postgres etkilenmez. **Yapıldı:** ölçüm ve limitler §6 "Bellek bütçesi"nde (tepe: konteynerler ~2,3 GB + Ollama 4,9 GB); limitler compose'da (`mem_limit`, tepe × ~2), JVM imajları `MaxRAMPercentage=65`; RabbitMQ cgroup limitini görmediği için `rabbitmq.conf`'ta `total_memory_available_override_value = 512MB` (alarm 307 MB) ve disk alarmı `disk_free_limit.absolute = 1GB`. NFR-07 limitlerle yeniden ölçüldü: p95 11 ms, uçtan uca 15–22 sn; sistem testleri yeşil.
- [x] **B-32** README: mimari şeması, kurulum, demo adımları, bilinen kısıt, v2 yol haritası; temiz makinede kurulum denemesi. Demo adımları `scripts/smoke-e2e.sh` üzerine kurulur (B-23). Bilinen kısıtlara: Playwright `DEBUG=pw:api` portal şifresini loglayabilir (B-26). Bilinen kısıtlara ayrıca: gerçek modelle 7/10 (B-29); Ollama istemci vazgeçince üretimi durdurmaz, `num_predict` bu yüzden zorunlu. İşletim notu: Docker diski dolunca RabbitMQ metadata kaybı ve kurtarma adımı (B-31). **Yapıldı:** `README.md` İngilizce (GitHub/portföy okuru; `docs/` Türkçe kalır): amaç ve iki garanti, mimari şema ve bileşen tablosu, garantilerin uygulanışı, kurulum, demo, testler, ölçümler, bilinen kısıtlar, v2 yol haritası. Temiz kopya denemesi (2026-10-02): git'in yok saydıkları hariç kopya, ayrı proje adı ve sıfır volume, yalnız README adımları → 8 servis sağlıklı, duman testi 23 sn'de `POSTED`, elle `curl` adımları ve portal girişi çalıştı. Sınır: Docker katman ve Maven önbellekleri paylaşımlı olduğundan ilk derleme süresi ölçülemedi; README indirilecek boyutu yazar (~5 GB).
- [ ] **B-33** v1 etiketi ve CV / GitHub sunumu.(GÜNCELLEME:v2 etiketi sonrası CV ve github sunumu yapılacak. BU ADIM ATLANABİLİR.)

### M6 — v2: "en fazla bir kez" garantisi

- [x] **B-34** Redis RPA kilidi (watchdog) ve `invoice.retry` + `wait-30s` bekleme odası; Redis yoksa fail-closed (FR-R7). **Yapıldı:** `InvoiceLocks` (Redisson `RLock`, kira olmadan alınır, `lockWatchdogTimeout` = `invoice.rpa.lock-lease` 60 sn → 20 sn'de bir yenileme; Redis'e ulaşılamazsa alınamamış sayılır). Kilit portala dokunmadan önce alınır, sonuç yazılınca bırakılır; alınamazsa `completion.defer` + `OutboxWriter.republish` ile aynı kimlikle `invoice.retry` · `rpa.post.wait` (§5). Topoloji: `invoice.retry` (direct), `rpa.post-to-portal.wait-30s` (quorum, `x-message-ttl` 30 000, DLX `invoice.commands` · `rpa.post`, at-least-once). Testler: `InboxIntegrationTest` (defer, yeniden erteleme, çift çağrı), `WaitingRoomIntegrationTest` (gerçek broker'da 30 sn sonra aynı `message_id` ile dönüş), `InvoiceLockIntegrationTest` (kilit başkasındayken portala dokunulmaz, bırakılınca döner ve bir kez girilir; başarıdan sonra kilit bırakılır), `RedisDownFailClosedIntegrationTest`. Sınır: kira bir duraklamada kaybedilirse kilit tek başına yetmez → B-35 ön arama. Çalışan bir yığında yeni topoloji için RabbitMQ yeniden başlatılmalı (definitions yalnız açılışta yüklenir).
- [x] **B-35** Portalda sayfaları dolaşan ön arama, `FOUND_EXISTING`; giriş sırasında konteyneri öldüren test (FR-R3, US-08, NFR-04). **Yapıldı:** ön arama (§4.4) ve `FOUND_EXISTING`; mock-portal'a yavaş yanıt bayrağı (§4.5). Testler: `PreSearchIntegrationTest` (yarım kalmış denemeden kalan kayıt bulunur ve yeniden girilmez; başka VKN'li aynı no eşleşmez; son sonuç sayfasındaki kayıt bulunur), `CrashRecoverySystemTest` (portal kaydedip yanıtı bekletirken rpa-service `docker kill`; yeniden başlayan bot ölen örneğin kilidi düşene kadar bekleme odasında bekler, sonra ön aramada bulur: portalda tek kayıt, `FOUND_EXISTING` — US-08, NFR-04), `SlowResponseTest`. Bot öldürülmese de portal yanıtı adım zaman aşımını aşarsa yeniden teslimde ön arama kaydı bulur. `EndToEndSystemTest` artık `SUBMITTED` ya da `FOUND_EXISTING` kabul eder (aynı yığında aynı fatura önceki bir testte girilmiş olabilir).
- [x] **B-36** Portal hata enjeksiyonu: oturum zaman aşımı, rastgele 500, gecikmeli eleman (FR-P3). **Yapıldı:** §4.5. Testler: `ErrorInjectionTest` (oran 1: her sayfa 500, health 200), `FaultInjectorTest` (aynı tohum aynı dizi; oran 0/1), `SessionIdleTimeoutTest` (boşta düşer, her istek yeniler), `ElementDelayTest`, varsayılanda kapalı. rpa testlerinin JVM içi portalı 400 ms eleman gecikmesiyle çalışır: bütün Playwright testleri görünür olmayı bekler. Bu, gösterme betiğindeki hatayı yakaladı (satır içi stil temizleniyordu, CSS kuralı gizlemeye devam ediyordu; artık öznitelik kaldırılır) — JDK istemcili portal testleri JavaScript çalıştırmadığı için göremezdi. Bot şimdilik yalnız gecikmeli elemanla başa çıkar; oturum düşmesi ve 500 B-37'de.
- [x] **B-37** Oturum paylaşımı ve yeniden login; servis içi 2 · 8 · 30 sn retry; ekran görüntüsü (FR-R4–R6, US-09). **Yapıldı, iki bilinçli sapmayla:** (1) servis içi 2 · 8 · 30 sn yerine bekleme odası (30 sn, aynı kimlik) — thread uyumaz, tek aktif consumer'lı kuyrukta arkadaki faturalar beklemez, B-27'deki kapanış zinciri değişmez; deneme sayısı `portal_submissions.attempt_count`, sınır `invoice.rpa.max-attempts` (4), dolunca doğrudan DLQ. (2) Redis'te oturum paylaşımı yok: her giriş kendi tarayıcısıyla login olur; bir adımda login sayfasına düşülürse bir kez yeniden login ve akış **ön aramadan** baştan (oturum form kaydedildikten sonra düşmüşse ikinci giriş olmaz); oturum cookie'si Redis'te secret yüzeyi oluşturmaz. Geçici: hata sayfası (her sayfa geçişinde hemen fark edilir), düşen oturum, zaman aşımı, eleman yok. Kalıcı: form reddi → DLQ. Beklenmeyen hata eski teslim limiti yolunda. FR-R6: `V2__rpa_attempts` (adım, hata, ekran görüntüsü yolu), PNG `{screenshot-dir}/{documentId}/{deneme}-{adım}.png`; kalıcı hatalar da iz bırakır. Bulgu: bekleme odasından geçen mesajın DLQ'daki ilk ölümü `expired`'dı; `DeadLetterListener` artık son ölüm başlıklarını okur (`deathQueue`, `deathReason`), yoksa `dead_letters.source_queue` bekleme odası olurdu. Testler: `PortalSessionAndScreenshotTest` (cookie silinerek düşürülen oturum → yeniden login, tek giriş; PNG + adım), `PostToPortalFlowIntegrationTest` (geçici hata bekleme odası üzerinden yeniden denenir, sonra DLQ; her deneme kayıtlı; kalıcı hata tek deneme), `DeadLetterListenerTest` (son ölüm).
- [x] **B-38** Yönetici DLQ API'si ve durum geçişiyle yeniden işletme (FR-A1, US-10, ADR-18). Açık karar A4'ü burada kapat. **Yapıldı:** yönetici API'si (§4.1), A4 (bekleyen komut kimliği). rpa-service'te deneme hakkı tur bazında (`V3__submission_rounds`: `command_id`, `round_attempts`): yeni komut kimliği yeni tur başlatır, bekleme odasından dönen aynı komut turu sürdürür; `attempt_count` bütün turların toplamı. Testler: `DeadLetterAdminIntegrationTest` (yeniden işletmenin tam etkisi, 409/404, iki kez yeniden işletme, yok sayma, liste ve detay), `DeadLetterFlowIntegrationTest` (eski tur DLQ mesajı yalnız park edilir; güncel tur `RPA_FAILED`), `PostToPortalFlowIntegrationTest` (hakkını bitirmiş faturaya gelen yeni komut DLQ'ya değil bekleme odasına). Eksik: US-10'u uçtan uca (portal düzelir) sınayan sistem testi yok; mock-portal'ın hata bayrağı açılışta okunur ve yeniden işletilen komut aynı fatura numarasını taşır.

### M7 — v2: insan adımları ve denetim

- [x] **B-39** Ayarlar API'si ve tutar eşiği (FR-A2, FR-D11). **Yapıldı:** onay kararı `ComplianceCompleted` işleyicisinde (§4.1): tutar > eşik veya TRY dışı para birimi → `VALIDATED → PENDING_APPROVAL`, gerekçeli, komutsuz. Ayarlar API'si (§4.1) ve `V3__settings_changes` (değişiklik izi). Sistem testleri: 3. fatura (1.534.908 TL) artık `PENDING_APPROVAL` beklenir; kabul ölçütü "POSTED ya da eşik gereği PENDING_APPROVAL, en az 8/10" olarak okunur; mükerrer yükleme testi 02'yi, çökme testi 03 yerine 07'yi, NFR-07 betiği 01, 02, 04–06'yı kullanır. Testler: `ApprovalDecisionTest` (eşit/üstü, TRY dışı, eksik alan, sıfır eşik), `EventFlowIntegrationTest` (eşik üstü ve USD → `PENDING_APPROVAL`, `PostToPortal` yok), `SettingsAdminIntegrationTest` (GET, kısmi PUT + iz + anında etki, aynı değer iz bırakmaz, geçersiz gövdeler 400 ve yazılmaz).
- [x] **B-40** Alan düzeltme (`If-Match`), onay/ret, gerekçe zorunluluğu (FR-D7, US-13). **Yapıldı:** düzeltme, onay ve ret API'leri (§4.1), `GET /documents/{id}`'de `ETag`, yapısal kontrol (§4.2, `CorrectionValidator`). `CheckCompliance` kurulumu `ComplianceDispatch`'e taşındı (çıkarım sonrası karar ve düzeltme ortak). Aktörler B-43'e kadar sabit `expert` / `approver` idi, sonra kullanıcı adı. Testler: `CorrectionValidatorTest` (tümü geçer, tolerans içi, aritmetik/biçim hataları ayrıntıyla, eksik alan ve bozuk kalem), `DocumentReviewIntegrationTest` (düzeltmenin tam etkisi, ETag 2→4; arama kopyaları güncellenir, LLM skoru kalır; 428/412/400 ve değişiklik yok; 422 + `violations`; 409/404; onayın etkisi ve ikinci onay 409; gerekçe zorunlu, geçmişte gerekçe, sonra 409).
- [x] **B-41** İçerik bazlı mükerrer şüphesi ve karar API'si (FR-D8, US-03). **Yapıldı:** öncelik kuralının ikinci adımı (§4.1, `DuplicateCheck`), danışma kilidi, `V4__duplicate_key_index`, karar API'si ve `duplicateOf` (§4.1); düzeltme sonrası da kontrol. Sistem testleri: her yükleme katalogdan benzersiz `ST-…` fatura no'lu yeni bir PDF'tir (`Invoices.renumbered`, sahte LLM'e tanıtılır); testler birbirinin kaydını mükerrer ya da portalda mevcut görmez, tam set testinde portal kaydı artık hep `SUBMITTED`. Gerçek PDF kullanan betikler (`smoke-e2e.sh`, `measure-nfr07.sh`) tekrar çalıştırmada "mükerrer değil" kararını API'den verip devam eder. Testler: `EventFlowIntegrationTest` (ikinci belge şüpheli, boşluk/harf farkı eşleşir; reddedilen şüphe yaratmaz; düşük güven önce gelir), `DuplicateCheckIntegrationTest` (eşzamanlı ikinci belge kilitte bekler ve birinciyi görür; farklı faturalar beklemez), `DocumentReviewIntegrationTest` (düzeltme sonrası şüphe + `duplicateOf`; "mükerrer değil" → `CheckCompliance`; "mükerrer" gerekçesiz 400, sonra `REJECTED`, sonra 409), `EndToEndSystemTest` (US-03 uçtan uca: şüphe, portala gidilmez, karar sonrası `FOUND_EXISTING` ve aynı portal numarası).
- [x] **B-42** Geçmiş API'si, `status_transitions` üzerinde UPDATE/DELETE yetkisinin geri alınması (FR-D6, NFR-05, US-12). **Yapıldı:** geçmiş API'si (§4.1), `V5__status_transitions_append_only` (yetki + tetikleyici, §4.1 şema notu), superuser ile test temizliği (§6, `test-support`). Testler: `DocumentSchemaTest` (servis kullanıcısının UPDATE/DELETE/TRUNCATE'i `permission denied`; superuser'ınki tetikleyiciye takılır; satır yerinde kalır), `DocumentReviewIntegrationTest` (geçmişin içeriği ve sırası, geçişsiz kayıtta boş liste, bilinmeyen kayıtta 404). Not: V1'in yorumları değiştirilmedi (Flyway checksum); şema bölümündeki açıklama güncellendi.
- [x] **B-43** Rol bazlı yetki; onaycı kendi yüklediğini onaylayamaz (NFR-10). **Yapıldı:** ADR-22, §4.1 "Yetki", `.env.example` ve compose (§6). Aktörler ve `uploaded_by` kullanıcı adı; eski sabit adlar (`expert`, `approver`, `admin`) kullanıcı adlarıyla aynı seçildi, geçmiş tutarlı. V3'ün yorumundaki "B-43'e kadar sabit admin" Flyway checksum'ı yüzünden değiştirilmedi. Testler: `ApiSecurityIntegrationTest` (401, açık health, okuma her rol, uç bazlı 403'ler, tanımsız uç reddi), `DocumentReviewIntegrationTest` (kendi yüklediğini onaylama 403, başkasınınkini onaylar, kendisininkini reddeder; ret durumun rolünü ister), `ApiUsersPropertiesTest` (şifresiz/rolsüz kullanıcıyla açılmaz, maskeleme); mevcut API testleri rolün kullanıcısıyla, rastgele şifrelerle.

### M8 — v2: sözleşme uyumu (RAG)

- [x] **B-44** Sentetik sözleşme PDF'leri: çakışan tarih aralığı ve süresi dolmuş sözleşme dahil. **Yapıldı:** §4.3 "Sentetik sözleşme seti" (8 sözleşme, B-16 faturalarına bağlı; fiyat aşımı, vade farkı, çakışma, süresi dolmuş, çok sayfa/üst-alt bilgi/tire/uzun madde, metinsiz). compliance-service'e `pdfbox` (ana scope, B-45 metin çıkarımı için de) ve extraction test-jar'ı (test, geçişlisiz) eklendi. Test: `SyntheticContractSetTest` (35).
- [x] **B-45** Sözleşme yükleme API'si ve `IngestContract` ile indeksleme: normalizasyon, madde bazlı chunking, bge-m3 (FR-C1, US-11). **Yapıldı:** §4.3 REST API, indeksleme uygulaması ve hata tablosu, şema notu; ADR-22 compliance'ı kapsar. Bağımlılıklar: `spring-boot-starter-security`, `spring-ai-starter-model-ollama` (sohbet modeli şimdilik kapalı, `spring.ai.model.chat: none`). Testler: `ContractChunkerTest` (B-44 PDF'leriyle madde/alt madde chunk'ları, üst/alt bilgi, tire, uzun madde bölmesi, metinsizlik, pencereli bölme, NFC), `IngestFlowIntegrationTest` (READY + 1024 boyut, norm 1; kalem sorgusu doğru alt maddeyi bulur; parçalar ve sayfalar; NO_TEXT; Ollama hatası → DLQ → FAILED; yanlış boyut tek denemede DLQ; tekrar gelen komut etkisiz), `ContractApiIntegrationTest` (202 + komut, aynı dosya 200, FAILED yeniden indeksleme, çakışma uyarısı, 400/415/404, 401/403), `ComplianceSchemaTest`. Testlerde `StubEmbeddingModel` (sözcük torbası hash'i). **Gerçek modelle ölçüm (2026-10-02, Ollama, `bge-m3`, tam yığın):** 8 sözleşme yüklendi; 7'si `READY` (chunk sayıları birim testlerle aynı: 8, 14, 8, 8, 8, 8, 13), metinsiz olan `FAILED`/`NO_TEXT`; her biri 0,8–2,2 sn. Retrieval: kalem başına "{açıklama} birim fiyat" ve "ödeme vadesi…" sorgularında filtreli tam taramanın ilk sonucu 27/27 doğru madde (ilk 4'te 27/27). Set küçük ve sentetik; B-46'da gerçeğe yakın sorgularla yeniden bakılır.
- [x] **B-46** SQL ile sözleşme seçimi, filtreli vektör arama, değer + alıntı çıkarımı, grounding, Java karşılaştırması (FR-C2–C4, US-06, US-07). **Yapıldı:** §4.3 "Uygulama (B-46)" ve gerçek model ölçümü (10/10), §4.1 uyumsuz sonuç → `PENDING_APPROVAL`, `V2__compliance_checks`, `llm-support` modülü (ADR-07), sistem testleri gerçek uyum yolunda (§6). Testler: `ComplianceEvaluatorIntegrationTest` (B-44 senaryo tablosu katalogdan, 10 fatura; fiyat ve vade bulgusu ayrıntısı; seçimde LLM'e gidilmez; halüsinasyon/değer-alıntı uyuşmazlığı/bozuk yanıt → güvenilmez; LLM yokken istisna; faturadan üretilen sözleşmeyle temiz faturalar uyumlu, 48 kalem dahil), `GroundingTest`, `CheckComplianceFlowIntegrationTest` (NO_CONTRACT + `compliance_checks`), `EventFlowIntegrationTest` (uyumsuz/sözleşmesiz/çakışma → onay), `ContractComplianceSystemTest`, `LlmSemaphoreTest` (llm-support'a taşındı), `OllamaComplianceSmokeTest` (isteğe bağlı ölçüm). Betikler (`smoke-e2e.sh`, `measure-nfr07.sh`) faturadan önce tedarikçinin uyumlu sözleşmesini yükler (`scripts/lib-contracts.sh`; commit edilmiş `contracts/compliant/uyumlu-invoice-XX.pdf`, `SyntheticContracts.compliantSet()`, set testi katalogla aynılığını sınar; aralığı kesişen sözleşme uyarısı ekrana yazılır). Hazır sözleşmesi olmayan PDF için smoke uyarır, kayıt `PENDING_APPROVAL`'da durur. Smoke (2026-10-03, gerçek modeller): invoice-01 `COMPLIANT`, 32 sn'de `POSTED`. Bulgu: B-45 doğrulamasından dev veritabanında kalan B-44 sözleşmeleri yeni uyumlu sözleşmeyle aynı tedarikçide çakıştı ve fatura doğru biçimde `CONTRACT_CONFLICT` aldı; bu test sözleşmeleri silindi.
- [x] **B-47** Ham LLM çıktısının model ve prompt sürümüyle tam kaydı (FR-E6). Kayıt B-21'de yapıldı (`extraction_runs.raw_output`); kalan: incelemek için API ve saklama süresi. **Yapıldı:** §4.2 "İnceleme ve saklama", ADR-22 extraction'ı kapsar; compose extraction'a 8083 portunu ve API şifrelerini verir (sistem testlerinde rastgele port). Testler: `ExtractionRunApiIntegrationTest` (sıra, ham çıktı, silinmiş işareti, başka belgenin denemesi görünmez, boş liste; 401/403/400), `RawOutputRetentionIntegrationTest` (yalnız süresi dolanın ham çıktısı silinir, satır kalır, ikinci çalıştırma etkisiz, zaman aşımı satırına dokunulmaz), `ExtractionSchemaTest`.
- [x] **B-48** Micrometer metrikleri: kuyruk derinliği, işlem süresi, retry, DLQ ve takılı kayıt alarmları (NFR-06 v2). Açık karar A3'ü veriyle kapat. **Yapıldı:** §5 "Metrikler ve alarmlar"; A3 kapandı (otomatik yeniden yayın yok). Bağımlılıklar: `micrometer-registry-prometheus` (dört servis), `micrometer-core` (common-messaging, llm-support); yeni servis yok. Gerçek yığında doğrulandı (2026-10-03): smoke faturasının izi her serviste (`ack` sonuçları, relay yayınları, LLM izin/çağrı süreleri, outbox 0), broker DLQ derinlikleri, kimliksiz istek 401. Bulgu: dev veritabanında önceki oturumlardan 1 açık park kaydı göstergeye yansıdı (`invoice_dead_letters_open{kind="DLQ"} 1`). Testler: `ListenerMetricsTest` (beş sonuç, zehirli mesaj `unknown`, DLQ sayımı bir kez, kayıt defteri yoksa no-op), `OutboxIntegrationTest` (birikim göstergeleri ve relay sayacı gerçek DB/broker ile), `LlmSemaphoreTest` (bekleme/çağrı/alınamayan izin), `MetricsEndpointIntegrationTest` (uç yalnız ADMIN; takılı kayıt, açık park, outbox göstergeleri), `infra/prometheus/alerts.test.yml` (promtool, 7 kural senaryosu).
- [x] **B-49** v2 README güncellemesi. **Yapıldı (2026-10-03):** `README.md` v2'ye göre yeniden düzenlendi: mimari tablo (gerçek uyum kontrolü, extraction 8083, `invoice.retry` bekleme odası, Redis kilidi), mutlu yol diyagramı sözleşme uyumu ve eşikle; yan durumlar tablosu (neden düşer, kim ilerletir); garantilerde kilit + bekleme odası + ön arama ve uyumda grounding; yeni "Human steps and administration" bölümü (rol bazlı uç tablosu, metrikler ve alarmlar); testlere `OllamaComplianceSmokeTest`; ölçümler B-30 (B-46 sonrası) ve B-46 değerleriyle (yükleme p95 91 ms, uçtan uca 32–52 sn, uyum 10/10; bellek ölçümünün v1'de yapıldığı belirtildi); "Known limitations" yalnız kısıtlar (çok kalemli uyum süresi, sözleşmesiz fatura onaya, geçmişin koruma sınırı, A7); "Roadmap (v2)" yerine "Next steps" (kalan adaylar + §2 kapsam dışı). `docs/architecture.svg`'de eskimiş etiketler düzeltildi ("v1'de stub", "(v2)"); Ardından aynı gün: A7 kapandı (`OutboxCleaner`), RabbitMQ yönetim arayüzü için isteğe bağlı salt okur kullanıcı (§6, `init-user.sh`), şemaya kullanıcılar → extraction REST oku ve `invoice.retry` bekleme odası eklendi; README buna göre güncellendi. Etiket kullanıcıda.
