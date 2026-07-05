package com.archosan.invoice.messaging;

/**
 * Exchange adları. Yalnızca sabitlerdir; topoloji {@code infra/rabbitmq/definitions.json}'da tanımlanır (ADR-17).
 */
public final class Exchanges {

    public static final String COMMANDS = "invoice.commands";
    public static final String EVENTS = "invoice.events";
    /** Bekleme odaları (v2, B-34): TTL dolunca mesaj DLX ile asıl kuyruğuna döner. */
    public static final String RETRY = "invoice.retry";

    private Exchanges() {
    }
}
