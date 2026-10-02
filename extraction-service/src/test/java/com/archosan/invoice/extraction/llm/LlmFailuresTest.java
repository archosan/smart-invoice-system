package com.archosan.invoice.extraction.llm;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class LlmFailuresTest {

    @Test
    void connectionProblemsAreUnreachableEvenWhenWrapped() {
        assertThat(classify(wrap(new ConnectException("Connection refused")))).isEqualTo(LlmFailures.Kind.UNREACHABLE);
        assertThat(classify(wrap(new UnknownHostException("ollama")))).isEqualTo(LlmFailures.Kind.UNREACHABLE);
        assertThat(classify(wrap(new HttpConnectTimeoutException("connect timed out"))))
                .isEqualTo(LlmFailures.Kind.UNREACHABLE);
    }

    @Test
    void nettyReadTimeoutIsTimeoutNotOther() {
        // Reactor Netty istemcisinin gerçek okuma zaman aşımı; JDK zaman aşımı tiplerinden türemez.
        RuntimeException wrapped = new RuntimeException("retry exhausted", new ResourceAccessException("I/O error",
                new IOException(io.netty.handler.timeout.ReadTimeoutException.INSTANCE)));

        assertThat(classify(wrapped)).isEqualTo(LlmFailures.Kind.TIMEOUT);
    }

    @Test
    void nettyConnectionRefusedIsUnreachable() {
        assertThat(classify(wrap(new io.netty.channel.ConnectTimeoutException("connection timed out"))))
                .isEqualTo(LlmFailures.Kind.UNREACHABLE);
    }

    @Test
    void readTimeoutsAreTimeouts() {
        assertThat(classify(wrap(new HttpTimeoutException("request timed out")))).isEqualTo(LlmFailures.Kind.TIMEOUT);
        assertThat(classify(wrap(new SocketTimeoutException("Read timed out")))).isEqualTo(LlmFailures.Kind.TIMEOUT);
    }

    @Test
    void otherFailuresAreOther() {
        assertThat(classify(new IllegalStateException("500 Internal Server Error"))).isEqualTo(LlmFailures.Kind.OTHER);
        assertThat(classify(wrap(new IOException("broken pipe")))).isEqualTo(LlmFailures.Kind.OTHER);
    }

    /** Spring AI'ın RetryTemplate'i ve Spring'in HTTP katmanı gibi iki kat sarmalar. */
    private static RuntimeException wrap(IOException cause) {
        return new RuntimeException("retry exhausted", new ResourceAccessException("I/O error", cause));
    }

    private static LlmFailures.Kind classify(Throwable t) {
        return LlmFailures.classify(t);
    }
}
