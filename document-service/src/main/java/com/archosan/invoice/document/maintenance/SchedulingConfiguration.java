package com.archosan.invoice.document.maintenance;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Cron'la çalışan işler için (sahipsiz dosya temizliği, gece); periyodik işler {@code BackgroundLoop} kullanır. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {
}
