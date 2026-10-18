package com.ballastlane.poke.web;

import static org.mockito.Mockito.mock;

import com.ballastlane.poke.config.AuthProperties;
import com.ballastlane.poke.config.SecurityConfig;
import com.ballastlane.poke.repository.AuthSessionRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The real security rules for {@code @WebMvcTest} slices. The session lookup is a mock: slices sign requests in
 * with {@code @WithMockUser} or {@code jwt()}, which skip it.
 */
@TestConfiguration
@Import(SecurityConfig.class)
@EnableConfigurationProperties(AuthProperties.class)
public class WebSecurityTestConfiguration {

	@Bean
	AuthSessionRepository authSessionRepository() {
		return mock(AuthSessionRepository.class);
	}

}
