package com.archosan.invoice.extraction.llm;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * LLM çağrısından gelen istisnayı sınıflandırır. Spring AI ve Spring'in HTTP katmanı asıl nedeni sarmalar
 * ({@code ResourceAccessException}, {@code RetryException}); bu yüzden neden zinciri taranır. Bağlantı kurulamaması
 * zaman aşımından önce denetlenir: bağlantı zaman aşımı da "ulaşılamıyor" demektir.
 *
 * <p>Boot burada Reactor Netty istemcisini seçer (WebFlux, Spring AI ile gelir). Netty'nin bağlantı istisnaları
 * {@link ConnectException}'dan türer, ama okuma zaman aşımı {@code io.netty.handler.timeout.ReadTimeoutException}'dır
 * ve JDK'nın zaman aşımı tiplerinden türemez (B-19'da sahte Ollama sunucusuyla bulundu). Netty'ye derleme bağımlılığı
 * olmasın diye zaman aşımları sınıf adının {@code TimeoutException} ile bitmesinden de tanınır.
 */
final class LlmFailures {

    enum Kind {
        UNREACHABLE,
        TIMEOUT,
        OTHER
    }

    private LlmFailures() {
    }

    static Kind classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException || t instanceof HttpConnectTimeoutException) {
                return Kind.UNREACHABLE;
            }
        }
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException
                    || t instanceof TimeoutException || t.getClass().getSimpleName().endsWith("TimeoutException")) {
                return Kind.TIMEOUT;
            }
        }
        return Kind.OTHER;
    }
}
