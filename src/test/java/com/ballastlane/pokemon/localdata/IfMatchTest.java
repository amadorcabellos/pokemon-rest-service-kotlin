package com.ballastlane.pokemon.localdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ballastlane.pokemon.exception.InvalidIfMatchException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class IfMatchTest {

	@Test
	void matchesStrongTagsOnly() {
		assertThat(IfMatch.parse("\"3\"").matches(3)).isTrue();
		assertThat(IfMatch.parse("\"3\"").matches(4)).isFalse();
		assertThat(IfMatch.parse(" \"1\" , \"4\" ").matches(4)).isTrue();
		assertThat(IfMatch.parse("W/\"3\"").matches(3)).isFalse();
		assertThat(IfMatch.parse("*").matches(42)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "  ", "3", "\"3", "\"3\" \"4\"", "W/3", "\"3\";x" })
	void rejectsMalformedHeaders(String header) {
		assertThatThrownBy(() -> IfMatch.parse(header)).isInstanceOf(InvalidIfMatchException.class);
	}

	@Test
	void formatsEntityTags() {
		assertThat(IfMatch.entityTag(7)).isEqualTo("\"7\"");
	}

}
