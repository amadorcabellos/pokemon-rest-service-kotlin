package com.ballastlane.poke.config;

import com.ballastlane.poke.web.LocalDataRequestSizeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LocalDataConfig {

	@Bean
	LocalDataRequestSizeFilter localDataRequestSizeFilter(LocalDataProperties properties) {
		return new LocalDataRequestSizeFilter(properties.maxRequestSize());
	}

}
