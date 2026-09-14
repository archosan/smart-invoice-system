-- Database-per-service (ADR-06): dört veritabanı, her birine yalnızca kendi kullanıcısı bağlanır.
-- docker-entrypoint bu dosyayı ilk açılışta (boş pgdata) superuser olarak psql ile çalıştırır.
-- Şifreler koda yazılmaz (NFR-10); psql \getenv ile konteyner ortamından okunur.

\set ON_ERROR_STOP on

\getenv document_password   DOCUMENT_DB_PASSWORD
\getenv extraction_password EXTRACTION_DB_PASSWORD
\getenv compliance_password COMPLIANCE_DB_PASSWORD
\getenv rpa_password        RPA_DB_PASSWORD

CREATE ROLE document_user   LOGIN PASSWORD :'document_password';
CREATE ROLE extraction_user LOGIN PASSWORD :'extraction_password';
CREATE ROLE compliance_user LOGIN PASSWORD :'compliance_password';
CREATE ROLE rpa_user        LOGIN PASSWORD :'rpa_password';

-- Sahiplik sayesinde her kullanıcı kendi veritabanının public şemasında tablo oluşturabilir (Flyway).
CREATE DATABASE document_db   OWNER document_user;
CREATE DATABASE extraction_db OWNER extraction_user;
CREATE DATABASE compliance_db OWNER compliance_user;
CREATE DATABASE rpa_db        OWNER rpa_user;

REVOKE CONNECT ON DATABASE document_db   FROM PUBLIC;
REVOKE CONNECT ON DATABASE extraction_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE compliance_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE rpa_db        FROM PUBLIC;

GRANT CONNECT ON DATABASE document_db   TO document_user;
GRANT CONNECT ON DATABASE extraction_db TO extraction_user;
GRANT CONNECT ON DATABASE compliance_db TO compliance_user;
GRANT CONNECT ON DATABASE rpa_db        TO rpa_user;

-- pgvector superuser ister; servis kullanıcısı eklentiyi kendisi kuramaz.
\connect compliance_db
CREATE EXTENSION IF NOT EXISTS vector;
