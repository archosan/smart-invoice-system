# Akıllı Fatura İşleme ve Sözleşme Uyum Sistemi — Gereksinimler ve Kapsam

## Amaç ve bağlam

Sistem, tedarikçi faturalarını PDF'ten okur, kurallarla ve ilgili tedarikçi sözleşmesiyle karşılaştırır, onaylanan faturayı API'si olmayan eski muhasebe portalına RPA ile girer. Hedef, kapsamı dar ama hata senaryoları derin bir backend sistemidir: her fatura portala en fazla bir kez girilir (v1'deki kısıt Kapsam bölümünde), her adım izlenebilir ve yarıda kalan iş kaldığı yerden sürer.

| Aktör                        | Tür         | Sistemdeki rolü                                                                         |
| ---------------------------- | ----------- | --------------------------------------------------------------------------------------- |
| Muhasebe uzmanı (AP uzmanı)  | İnsan       | Fatura ve sözleşme yükler, durum takip eder, düşük güvenli kayıtları düzeltir           |
| Onaycı                       | İnsan       | Uyumsuz veya eşik üstü faturaları onaylar ya da reddeder                                |
| Sistem yöneticisi            | İnsan       | DLQ'daki mesajları inceler, yeniden işletir, tedarikçi ve eşik tanımlarını yönetir      |
| Eski muhasebe portalı (mock) | Dış sistem  | API'si yok; login, oturum zaman aşımı, sayfalama ve ara sıra hata veren formu var       |
| Yerel LLM (Ollama)           | Dış bileşen | Metni JSON şemasına eşler, sözleşme maddelerini yorumlar; hatalı olabileceği varsayılır |

Servisler: document-service, extraction-service, compliance-service, rpa-service ve mock portal. Teknoloji: Spring Boot, RabbitMQ, Redis, PostgreSQL + pgvector, PDFBox, Spring AI + Ollama, Playwright (Java).

## Kullanıcı senaryoları

Bir mutlu yol ve dokuz hata senaryosu var; projenin derinliği hata senaryolarından gelir. Her senaryo ileride bir entegrasyon testine dönüşmelidir.

### US-01 Mutlu yol: fatura yükle, portala girilsin

Muhasebe uzmanı olarak bir fatura PDF'i yüklemek istiyorum ki elle veri girmeden portala işlensin.

1. Uzman PDF'i yükler; sistem hemen bir `documentId` ve `RECEIVED` durumu döner.
2. extraction-service metni PDFBox ile çıkarır, LLM'e şemaya zorlanmış JSON ürettirir, kurallarla doğrular.
3. compliance-service faturayı tedarikçinin sözleşme maddeleriyle karşılaştırır ve uyumlu bulur.
4. rpa-service portala login olur, faturayı girer, portalın verdiği kayıt numarasını saklar.
5. Uzman durumu `POSTED` ve portal kayıt numarasıyla görür.

### Hata ve istisna senaryoları

| ID    | Senaryo                                                  | Tetikleyici                                                                                          | Beklenen sistem davranışı                                                                                                                      |
| ----- | -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| US-02 | Aynı PDF ikinci kez yüklenir                             | Dosya hash'i daha önce görülmüş                                                                      | Yeni kayıt açılmaz; mevcut `documentId` ve durumu döner (HTTP 200, `duplicate: true`)                                                          |
| US-03 | Farklı dosya, aynı fatura                                | Hash farklı, ama tedarikçi VKN + fatura no aynı                                                      | Kayıt `DUPLICATE_SUSPECTED` olur, portala girilmez, uzmana gösterilir                                                                          |
| US-04 | LLM yanlış alan çıkarır                                  | Kalem toplamı + KDV ≠ genel toplam, VKN formatı geçersiz vb.                                         | Güven skoru eşiğin altına düşer, kayıt `NEEDS_REVIEW` olur; uzman alanları düzeltip onaylar                                                    |
| US-05 | LLM şemaya uymayan çıktı verir veya yanıt vermez         | Parse hatası, zaman aşımı                                                                            | Sınırlı sayıda yeniden dener; tükenirse `NEEDS_REVIEW`                                                                                         |
| US-06 | Sözleşmeyle uyumsuzluk                                   | Birim fiyat sözleşmeden yüksek, vade farklı, sözleşme süresi dolmuş                                  | Kayıt `PENDING_APPROVAL` olur; onaycı ilgili sözleşme maddesini ve farkı görür, onaylar veya reddeder                                          |
| US-07 | Fatura tarihinde geçerli sözleşme yok ya da birden fazla | VKN + fatura tarihiyle eşleşen sözleşme bulunamaz veya tarih aralıkları çakışan iki sözleşme eşleşir | Uyum kontrolü atlanır, kayıt insan onayına gider                                                                                               |
| US-08 | RPA botu giriş sırasında çöker                           | Form yarıda, servis yeniden başlar                                                                   | Bot önce portalda fatura no ile arama yapar; kayıt varsa kayıt numarasını alır ve `POSTED` yapar, yoksa yeniden girer. Asla çift kayıt oluşmaz |
| US-09 | Portal geçici hata verir                                 | Oturum zaman aşımı, 500 hatası, eleman bulunamadı                                                    | Artan bekleme süreli retry; oturum düşerse yeniden login; tükenirse mesaj DLQ'ya gider, kayıt `RPA_FAILED`                                     |
| US-10 | Yönetici DLQ'yu işler                                    | Portal düzeldi                                                                                       | Yönetici DLQ'daki mesajı inceler ve tekrar kuyruğa alır; US-08'deki kontrol sayesinde yeniden işleme güvenlidir                                |

### Destekleyici senaryolar

- US-11: Uzman olarak tedarikçi sözleşmesi PDF'ini başlangıç ve bitiş tarihiyle yüklemek istiyorum ki sonraki faturalar bu sözleşmeyle karşılaştırılsın.
- US-12: Uzman olarak bir faturanın tüm geçmişini (kim, ne zaman, hangi durumdan hangisine) görmek istiyorum ki denetimde açıklayabileyim.
- US-13: Onaycı olarak bekleyen faturaları listelemek ve ret gerekçesi yazmak istiyorum.

## Fonksiyonel gereksinimler

Gereksinimler servis bazında numaralandı; Öncelik sütunu MVP (v1) ile ikinci iterasyonu (v2) ayırır.

### document-service

| ID     | Gereksinim                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | Öncelik |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| FR-D1  | Sistem PDF fatura yüklemeyi kabul eder, dosyayı saklar ve `documentId` döner; işleme asenkron başlar                                                                                                                                                                                                                                                                                                                                                                                  | v1      |
| FR-D2  | Sistem dosyanın SHA-256 hash'ini hesaplar; aynı hash tekrar gelirse yeni kayıt açmaz, mevcut kaydı döner                                                                                                                                                                                                                                                                                                                                                                              | v1      |
| FR-D3  | Sistem her faturanın durumunu bir durum makinesiyle yönetir: `RECEIVED → EXTRACTED → VALIDATED → COMPLIANCE_CHECKED → QUEUED_FOR_RPA → POSTED`; yan dallar `NEEDS_REVIEW`, `PENDING_APPROVAL`, `REJECTED`, `DUPLICATE_SUSPECTED`, `RPA_FAILED`                                                                                                                                                                                                                                        | v1      |
| FR-D4  | Sistem yalnızca aşağıdaki durum geçiş tablosundaki geçişlere izin verir, diğerlerini reddeder (örn. `POSTED`'dan geri dönüş yok)                                                                                                                                                                                                                                                                                                                                                      | v1      |
| FR-D5  | Sistem durum sorgulama ve listeleme API'si sunar (durum, tedarikçi, tarih filtresi, sayfalama)                                                                                                                                                                                                                                                                                                                                                                                        | v1      |
| FR-D6  | Sistem her durum geçişini aktör, zaman ve gerekçeyle değişmez audit log'a yazar                                                                                                                                                                                                                                                                                                                                                                                                       | v2      |
| FR-D7  | Sistem `NEEDS_REVIEW` kayıtlarında alan düzeltme, `PENDING_APPROVAL` kayıtlarında onay/ret (gerekçe zorunlu) API'si sunar                                                                                                                                                                                                                                                                                                                                                             | v2      |
| FR-D8  | Sistem tedarikçi VKN + fatura no eşleşmesiyle içerik bazlı mükerrer şüphesini işaretler                                                                                                                                                                                                                                                                                                                                                                                               | v2      |
| FR-D9  | document-service durumun tek sahibidir: diğer servislerin olaylarını tüketir, geçişi uygular, mükerrer kontrolü ve öncelik kuralı dahil kararı verir, sonraki adımın mesajını yayınlar                                                                                                                                                                                                                                                                                                | v1      |
| FR-D10 | document-service DLQ'ları dinler ve ilgili kaydı durum geçiş tablosuna göre günceller (ExtractInvoice → NEEDS_REVIEW, CheckCompliance → PENDING_APPROVAL, PostToPortal → RPA_FAILED). document-service'in kendi tükettiği olayların (ExtractionCompleted vb.) DLQ'su kaydı güncelleyemez; bu DLQ'lar için derinlik metriği ve uyarı tanımlanır. v1'de DLQ'daki mesajlar yalnızca incelenir, yeniden kuyruğa alınmaz; yeniden işleme FR-A1 ile v2'de, document-service üzerinden gelir | v1      |
| FR-D11 | document-service genel toplamı FR-A2 ile konfigüre edilen tutar eşiğiyle karşılaştırır; eşik üstü fatura, sözleşmeyle uyumlu olsa bile PENDING_APPROVAL olur                                                                                                                                                                                                                                                                                                                          | v2      |

#### Durum geçiş tablosu

Durumun tek sahibi document-service'tir: diğer servisler yalnızca olay yayınlar, her geçişi document-service uygular. "Olayı üreten" sütunu geçişi tetikleyen olayın kaynağıdır. Yan dallar ana akışa tek noktadan döner; `POSTED` ve `REJECTED` son durumlardır.

**Öncelik kuralı:** `ExtractionCompleted` alındığında document-service sırayla `NEEDS_REVIEW` > `DUPLICATE_SUSPECTED` > `VALIDATED` değerlendirir. Güven skoru düşükse VKN ve fatura no da güvenilmez olabileceği için mükerrer kontrolü yapılmaz; uzman düzeltince kayıt `EXTRACTED`'a döner ve kontrol o zaman çalışır.

| Kaynak                | Hedef                 | Tetikleyen olay                                                                           | Olayı üreten             | Sürüm |
| --------------------- | --------------------- | ----------------------------------------------------------------------------------------- | ------------------------ | ----- |
| —                     | `RECEIVED`            | PDF yüklendi, hash yeni                                                                   | Uzman (API)              | v1    |
| `RECEIVED`            | `EXTRACTED`           | `ExtractionCompleted`: alanlar, güven skoru, kural sonuçları                              | extraction-service       | v1    |
| `RECEIVED`            | `NEEDS_REVIEW`        | `ExtractionFailed` (metin yok, LLM denemeleri tükendi) veya `ExtractInvoice` DLQ'ya düştü | extraction-service / DLQ | v1    |
| `EXTRACTED`           | `NEEDS_REVIEW`        | Güven skoru < eşik (öncelik 1)                                                            | document-service         | v1    |
| `EXTRACTED`           | `DUPLICATE_SUSPECTED` | VKN + fatura no başka kayıtla eşleşti (öncelik 2)                                         | document-service         | v2    |
| `EXTRACTED`           | `VALIDATED`           | Güven skoru ≥ eşik, mükerrer şüphesi yok                                                  | document-service         | v1    |
| `NEEDS_REVIEW`        | `EXTRACTED`           | Uzman alanları düzeltti; karar yeniden verilir                                            | Uzman (API)              | v2    |
| `NEEDS_REVIEW`        | `REJECTED`            | Uzman reddetti (gerekçe zorunlu)                                                          | Uzman (API)              | v2    |
| `DUPLICATE_SUSPECTED` | `VALIDATED`           | Uzman mükerrer olmadığını onayladı                                                        | Uzman (API)              | v2    |
| `DUPLICATE_SUSPECTED` | `REJECTED`            | Uzman mükerrer olduğunu onayladı                                                          | Uzman (API)              | v2    |
| `VALIDATED`           | `COMPLIANCE_CHECKED`  | `ComplianceCompleted`: uyumlu ve tutar ≤ eşik (v1'de stub hep "uyumlu" döner)             | compliance-service       | v1    |
| `VALIDATED`           | `PENDING_APPROVAL`    | `ComplianceCompleted`: uyumsuz, geçerli sözleşme yok/çakışıyor veya tutar > eşik (FR-D11) | compliance-service       | v2    |
| `VALIDATED`           | `PENDING_APPROVAL`    | `CheckCompliance` DLQ'ya düştü; onaycı uyum kontrolünün yapılamadığını görür              | DLQ                      | v1    |
| `PENDING_APPROVAL`    | `COMPLIANCE_CHECKED`  | Onaycı onayladı                                                                           | Onaycı (API)             | v2    |
| `PENDING_APPROVAL`    | `REJECTED`            | Onaycı reddetti (gerekçe zorunlu)                                                         | Onaycı (API)             | v2    |
| `COMPLIANCE_CHECKED`  | `QUEUED_FOR_RPA`      | Durum ve `PostToPortal` komutu aynı transaction'da outbox'a yazıldı                       | document-service         | v1    |
| `QUEUED_FOR_RPA`      | `POSTED`              | `RpaCompleted`: portal kayıt numarası (ön aramada bulunan kayıt dahil)                    | rpa-service              | v1    |
| `QUEUED_FOR_RPA`      | `RPA_FAILED`          | `PostToPortal` teslim limitini aştı, DLQ'da                                               | DLQ                      | v1    |
| `RPA_FAILED`          | `QUEUED_FOR_RPA`      | Yönetici FR-A1 ile yeniden işletti; document-service yeni `PostToPortal` yayınlar         | Yönetici (API)           | v2    |

DLQ'dan mesajı doğrudan kuyruğa geri taşımak geçerli bir geçiş değildir: kayıt `RPA_FAILED` iken gelen `RpaCompleted` reddedilir ve sistem ile portal ayrışır. Bu yüzden yeniden işleme yalnızca FR-A1 üzerinden yapılır.

#### Mesaj sözleşmeleri

Mesaj adları burada sabittir; diğer bölümler bu adları kullanır. Her mesaj `messageId` ve `documentId` (= `correlationId`) taşır.

| Ad                    | Tür   | Üreten → Tüketen      | Ana içerik                                      | Sürüm |
| --------------------- | ----- | --------------------- | ----------------------------------------------- | ----- |
| `ExtractInvoice`      | Komut | document → extraction | Dosya konumu                                    | v1    |
| `ExtractionCompleted` | Olay  | extraction → document | Alanlar, güven skoru, kural sonuçları           | v1    |
| `ExtractionFailed`    | Olay  | extraction → document | Hata nedeni (metin yok, LLM denemeleri tükendi) | v1    |
| `CheckCompliance`     | Komut | document → compliance | VKN, fatura tarihi, kalemler, tutarlar          | v1    |
| `ComplianceCompleted` | Olay  | compliance → document | Uyum sonucu, bulgular, dayandığı maddeler       | v1    |
| `PostToPortal`        | Komut | document → rpa        | Portal formu alanları                           | v1    |
| `RpaCompleted`        | Olay  | rpa → document        | Portal kayıt numarası, ön aramada bulundu mu    | v1    |

### extraction-service

| ID    | Gereksinim                                                                                                                                                                                      | Öncelik |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| FR-E1 | Servis PDF'ten metni PDFBox ile deterministik olarak çıkarır; metin çıkmazsa (taranmış PDF) `ExtractionFailed` olayı yayınlar                                                                   | v1      |
| FR-E2 | Servis metni LLM'e sabit bir JSON şemasına eşletir: tedarikçi adı, VKN, fatura no, tarih, vade, kalemler (açıklama, miktar, birim fiyat, KDV oranı), ara toplam, KDV, genel toplam, para birimi | v1      |
| FR-E3 | Servis çıktıyı kurallarla doğrular: kalem toplamı = ara toplam, ara toplam + KDV = genel toplam (tolerans dahilinde), VKN 10 hane, tarih ≤ vade, zorunlu alanlar dolu                           | v1      |
| FR-E4 | Servis kural sonuçlarından bir güven skoru hesaplar ve `ExtractionCompleted` olayına yazar; eşikle karşılaştırmayı document-service yapar (eşik FR-A2 ile orada okunur)                         | v1      |
| FR-E5 | LLM erişimi Spring AI soyutlaması arkasındadır; model adı konfigürasyonla değişir                                                                                                               | v1      |
| FR-E6 | Servis ham LLM çıktısını, kullanılan model ve prompt sürümüyle birlikte saklar                                                                                                                  | v2      |

### compliance-service

| ID    | Gereksinim                                                                                                                                                                                                                        | Öncelik |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| FR-C1 | Servis tedarikçi sözleşmesi PDF'ini kabul eder, madde bazında parçalar, embedding'leri pgvector'a tedarikçi VKN'si, sözleşme ID'si ve geçerlilik tarih aralığıyla yazar; bir tedarikçinin birden fazla sözleşmesi saklanabilir    | v2      |
| FR-C2 | Servis önce fatura tarihinde geçerli sözleşmeyi seçer (VKN + tarih aralığı filtresi), sonra yalnızca o sözleşmeden ilgili maddeleri vektör benzerliğiyle getirir                                                                  | v2      |
| FR-C3 | Servis birim fiyat, ödeme vadesi ve sözleşme geçerlilik tarihi kontrollerini yapar; her bulgu için dayandığı madde metnini döner                                                                                                  | v2      |
| FR-C4 | Servis sonucu `ComplianceCompleted` olayına yazar: uyum sonucu (uyumlu, uyumsuz, geçerli sözleşme yok, sözleşme çakışması) ve her bulgunun dayandığı madde. `PENDING_APPROVAL` kararını document-service verir                    | v2      |
| FR-C5 | v1'de servis ve kuyruğu stub olarak çalışır: `CheckCompliance` komutunu tüketir, her fatura için "uyumlu" sonuçlu `ComplianceCompleted` olayı yayınlar. v2'de yalnızca kontrol mantığı eklenir, mesaj sözleşmesi ve akış değişmez | v1      |

### rpa-service

| ID    | Gereksinim                                                                                                                                                                                                    | Öncelik |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| FR-R1 | Servis kuyruktan gelen onaylı faturayı Playwright ile portala login olup girer                                                                                                                                | v1      |
| FR-R2 | Servis girişten sonra portalın verdiği kayıt numarasını okur ve document-service'e bildirir                                                                                                                   | v1      |
| FR-R3 | Servis her girişten önce portalda fatura no ile arama yapar (sayfalamayı dolaşarak); kayıt varsa yeniden girmez                                                                                               | v2      |
| FR-R4 | Servis oturum zaman aşımını algılayıp yeniden login olur                                                                                                                                                      | v2      |
| FR-R5 | Servis geçici hatalarda sınırlı sayıda, artan beklemeli yeniden dener; tükenince mesajı DLQ'ya bırakır                                                                                                        | v2      |
| FR-R6 | Servis başarısız her denemede ekran görüntüsü ve hata adımını saklar                                                                                                                                          | v2      |
| FR-R7 | Servis girişten önce fatura başına Redis dağıtık kilidi alır (TTL'li, iş sürerken yenilenir); kilit alınamazsa mesajı gecikmeli olarak yeniden kuyruğa koyar. Ön arama (FR-R3) kilit alındıktan sonra yapılır | v2      |
| FR-R8 | v1'de RPA kuyruğu tek consumer ile tüketilir (concurrency 1, prefetch 1); böylece aynı fatura eşzamanlı iki kez işlenemez                                                                                     | v1      |

### Mock eski portal

| ID    | Gereksinim                                                                                                      | Öncelik |
| ----- | --------------------------------------------------------------------------------------------------------------- | ------- |
| FR-P1 | Portal login, fatura giriş formu ve sayfalı fatura listesi/arama sayfası sunar; API sunmaz                      | v1      |
| FR-P2 | Portal başarılı girişte bir kayıt numarası gösterir                                                             | v1      |
| FR-P3 | Portal konfigüre edilebilir hata enjeksiyonu yapar: oturum zaman aşımı, rastgele 500, gecikmeli yüklenen eleman | v2      |

### Yönetim

| ID    | Gereksinim                                                                               | Öncelik |
| ----- | ---------------------------------------------------------------------------------------- | ------- |
| FR-A1 | Yönetici DLQ'daki mesajları listeler ve tekrar kuyruğa alır                              | v2      |
| FR-A2 | Yönetici güven skoru eşiğini ve onay gerektiren tutar eşiğini konfigürasyonla değiştirir | v2      |

## Fonksiyonel olmayan gereksinimler

En kritik iki garanti: bir fatura portala en fazla bir kez girilir ve hiçbir mesaj sessizce kaybolmaz. Sayısal hedefler tek geliştirici ve M3 Pro 18 GB için seçildi, gerçek ölçümden sonra güncellenmeli.

| ID     | Kategori            | Gereksinim                                                                                                                                                                                                                                                                                                                                                                                     | Nasıl doğrulanır                                                                                                          | Öncelik                                            |
| ------ | ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| NFR-01 | Idempotency         | Aynı fatura portala en fazla bir kez girilir. Üç katman: (1) tüketiciler işlenmiş mesaj ID'sini tutar, aynı mesaj iki kez gelirse sonuç değişmez; (2) RPA girişten önce fatura başına Redis dağıtık kilidi alır (TTL en uzun RPA süresinden uzun, iş sürerken yenilenir), böylece iki worker aynı faturayı aynı anda işleyemez; (3) kilit alındıktan sonra portalda ön arama yapılır (FR-R3)   | Aynı mesajı iki kez yayınlayan test; aynı faturayı iki worker'a eşzamanlı veren test; giriş sırasında RPA'yı öldüren test | v1: (1) + tek RPA consumer; v2: (2) ve (3)         |
| NFR-02 | Güvenilirlik        | Mesajlar kalıcı (durable) kuyruklarda, manuel ack ile tüketilir; DB yazımı ile mesaj yayını arasında outbox deseni kullanılır: servis durum değişikliğini ve giden mesajı aynı transaction'da yazar, zamanlanmış bir publisher outbox tablosunu tarayıp yayınlar (en az bir kez teslim; tekrarları NFR-01'in birinci katmanı karşılar). Bu kural tüm servislerin tüm yayınları için geçerlidir | Yayın anında servisi öldüren test; mesaj kaybolmamalı                                                                     | v1                                                 |
| NFR-03 | Hata toleransı      | Her kuyrukta dead-letter exchange ve teslim limiti vardır (quorum queue + x-delivery-limit, örn. 3); limiti aşan mesaj requeue edilmeden DLQ'ya düşer, böylece tek bir bozuk mesaj kuyruğu kilitleyemez (head-of-line blocking). Geçici hatalar artan beklemeli retry ile ele alınır; DLQ'ya düşen her mesaj görünür ve tekrar işlenebilir                                                     | Mock portal hata enjeksiyonuyla test                                                                                      | v1: DLX + teslim limiti; v2: artan beklemeli retry |
| NFR-04 | Kurtarılabilirlik   | Herhangi bir servis yeniden başladığında yarıda kalan işler durum makinesinden kaldığı yerden devam eder                                                                                                                                                                                                                                                                                       | RPA girişi sırasında konteyneri öldüren test (US-08)                                                                      | v2                                                 |
| NFR-05 | Denetlenebilirlik   | Her durum geçişi, LLM çıktısı ve onay kararı izlenebilir; audit kayıtları güncellenmez, silinmez                                                                                                                                                                                                                                                                                               | Bir faturanın geçmişini tek sorguyla yeniden kurmak                                                                       | v2                                                 |
| NFR-06 | Gözlemlenebilirlik  | Yapısal (JSON) log, tüm servislerde taşınan `correlationId` (= `documentId`), Actuator + Micrometer metrikleri (kuyruk derinliği, işlem süresi, retry ve DLQ sayısı)                                                                                                                                                                                                                           | Tek bir faturanın loglarını tüm servislerde ID ile bulmak                                                                 | v1: log + correlationId; v2: metrikler             |
| NFR-07 | Performans          | Yükleme API'si 500 ms içinde yanıt verir (işleme asenkron); tek sayfalık faturada yükleme → `POSTED` süresi yerel makinede 2 dakikanın altında                                                                                                                                                                                                                                                 | Basit yük testi ve metrik                                                                                                 | v1                                                 |
| NFR-08 | Kaynak kısıtı       | Tüm yığın (Docker'daki altyapı + servisler + macOS üzerinde Ollama) 18 GB'ta çalışır; LLM 7-8B sınıfı, 4-bit quantize; LLM'e eşzamanlı istek sayısı 1-2 ile sınırlı                                                                                                                                                                                                                            | Tam yığın açıkken bellek ölçümü                                                                                           | v1                                                 |
| NFR-09 | Değiştirilebilirlik | LLM, embedding modeli ve portal seçicileri konfigürasyonda; model değişimi kod değişikliği gerektirmez                                                                                                                                                                                                                                                                                         | Modeli değiştirip testleri koşturmak                                                                                      | v1                                                 |
| NFR-10 | Güvenlik            | Portal kimlik bilgileri ortam değişkeninden/secret'tan okunur, koda ve loga yazılmaz; API rol bazlı yetkilendirilir (uzman, onaycı, yönetici); onaycı kendi yüklediği faturayı onaylayamaz                                                                                                                                                                                                     | Log taraması, yetki testleri                                                                                              | v1: secret yönetimi; v2: rol bazlı yetki           |
| NFR-11 | Veri gizliliği      | Fatura ve sözleşme verisi makineden çıkmaz; LLM yerelde çalışır                                                                                                                                                                                                                                                                                                                                | Mimari gereği                                                                                                             | v1                                                 |
| NFR-12 | Test edilebilirlik  | Testcontainers ile RabbitMQ/Postgres/Redis entegrasyon testleri; LLM testlerde sahte (stub) istemciyle değiştirilebilir                                                                                                                                                                                                                                                                        | CI'da testlerin geçmesi                                                                                                   | v1                                                 |
| NFR-13 | Kurulum             | Tek komutla (`docker compose up`) ayağa kalkar; README kurulum, mimari ve demo adımlarını anlatır                                                                                                                                                                                                                                                                                              | Temiz makinede kurulum denemesi                                                                                           | v1                                                 |
| NFR-14 | Dil                 | Uygulama Türkçe faturaları (Türkçe karakterler, TL, virgüllü ondalık) doğru işler; embedding modeli çok dilli (örn. bge-m3)                                                                                                                                                                                                                                                                    | Türkçe örnek fatura setiyle test                                                                                          | v1                                                 |

## Kapsam

v1 uçtan uca mutlu yoldur ve CV'ye eklenecek ilk sürümdür (yan uğraş temposunda yaklaşık 3-4 hafta); v2 güvenilirlik ve uyum kontrolünü getirir. Kapsam dışı maddeler README'de "sonraki adımlar" olarak durur.

### v1 — Uçtan uca mutlu yol

- Kapsamdaki senaryolar: US-01, US-02, US-04 (kural doğrulama ve güven skoru; düzeltme ekranı olmadan, sadece `NEEDS_REVIEW` durumu), US-05.
- Gereksinimler: FR-D1–D5, FR-D9–D10, FR-E1–E5, FR-C5, FR-R1–R2, FR-R8, FR-P1–P2 ve NFR tablosunda v1 işaretli maddeler.
- Altyapı: docker compose (RabbitMQ, Redis, Postgres), macOS üzerinde Ollama, temel README ve mimari şeması.

**Bilinen kısıt (v1):** Portalda ön arama (FR-R3) v2'de geldiği için RPA giriş sırasında çökerse ve mesaj yeniden işlenirse çift kayıt oluşabilir. v1'de tek RPA consumer çalıştığından eşzamanlı çift giriş riski yoktur; "en fazla bir kez" garantisi v2'de kilit + ön arama ile tamamlanır.

### v2 — Güvenilirlik ve uyum

- Kapsamdaki senaryolar: US-03, US-06 – US-13.
- Gereksinimler: FR-D6–D8, FR-E6, FR-C1–C4, FR-D11, FR-R3–R7, FR-P3, FR-A1–A2.
- NFR tablosunda v2 işaretli maddeler; özellikle NFR-01'in kilit ve ön arama katmanları, NFR-03'ün retry kısmı ile NFR-04 – NFR-05 bu iterasyonun asıl konusu.

### Kapsam dışı

- Taranmış PDF için OCR (metin çıkmazsa kayıt insan incelemesine gider).
- e-Fatura/UBL XML entegrasyonu ve GİB bağlantısı.
- Raporlama, dashboard ve ön yüz (API + Swagger ve basit durum sayfası yeterli).
- Çok kiracılı yapı, SSO/LDAP, gerçek muhasebe sistemi entegrasyonu.
- Bulut dağıtımı ve Kubernetes.
- Çoklu para birimi dönüşümü (fatura para birimi saklanır, kur çevrimi yapılmaz).

### Varsayımlar

- Faturalar metin tabanlı PDF, tek fatura = tek dosya, çoğunlukla Türkçe ve TL.
- Tedarikçi, fatura üzerindeki VKN ile tanımlanır; bir tedarikçinin geçerlilik tarihleri farklı birden fazla sözleşmesi olabilir ve fatura tarihinde geçerli olan sözleşme esas alınır.
- Portal fatura no ile aranabilir; RPA'nın çift kaydı önlemesi bu aramaya dayanır.
- Test verisi sentetik fatura ve sözleşme PDF'lerinden oluşur; gerçek şirket verisi kullanılmaz.

### v1 kabul kriterleri

- [ ] 10 sentetik faturanın en az 8'i insan müdahalesi olmadan `POSTED` olur.
- [ ] Aynı PDF'in ikinci yüklemesi yeni kayıt ve portal girişi oluşturmaz.
- [ ] Toplamları tutmayan bir fatura `NEEDS_REVIEW`'a düşer ve portala girilmez.
- [ ] `docker compose up` + Ollama ile temiz makinede sistem ayağa kalkar.
- [ ] README'de mimari şeması, demo adımları ve v2 yol haritası var.
- [ ] Portalda sürekli hata veren bir fatura teslim limitinden sonra DLQ'ya düşer ve arkasındaki faturaların işlenmesini engellemez.
- [ ] Mesaj yayını sırasında öldürülen bir servis yeniden başladığında hiçbir fatura bir ara durumda takılı kalmaz.
