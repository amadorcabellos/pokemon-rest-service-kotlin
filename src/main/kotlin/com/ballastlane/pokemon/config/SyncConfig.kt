package com.ballastlane.pokemon.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables scheduling for the optional scheduled full sync (`app.sync.schedule.enabled`).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SyncConfig
