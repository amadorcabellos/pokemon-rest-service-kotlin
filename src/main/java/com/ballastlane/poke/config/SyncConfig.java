package com.ballastlane.poke.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduling for the optional scheduled full sync ({@code app.sync.schedule.enabled}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SyncConfig {

}
