package com.archosan.invoice.testsupport;

/**
 * {@code infra/postgres/init/01-databases.sql}'in oluşturduğu veritabanları (ADR-06). Şifre ortam değişkeni adları
 * compose ile aynıdır; testte değerler rastgele üretilir.
 */
public enum ServiceDatabase {

    DOCUMENT("document_db", "document_user", "DOCUMENT_DB_PASSWORD"),
    EXTRACTION("extraction_db", "extraction_user", "EXTRACTION_DB_PASSWORD"),
    COMPLIANCE("compliance_db", "compliance_user", "COMPLIANCE_DB_PASSWORD"),
    RPA("rpa_db", "rpa_user", "RPA_DB_PASSWORD");

    private final String databaseName;
    private final String username;
    private final String passwordVariable;

    ServiceDatabase(String databaseName, String username, String passwordVariable) {
        this.databaseName = databaseName;
        this.username = username;
        this.passwordVariable = passwordVariable;
    }

    public String databaseName() {
        return databaseName;
    }

    public String username() {
        return username;
    }

    String passwordVariable() {
        return passwordVariable;
    }
}
