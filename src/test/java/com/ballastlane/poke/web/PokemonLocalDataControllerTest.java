package com.ballastlane.poke.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.config.CatalogProperties;
import com.ballastlane.poke.config.LocalDataConfig;
import com.ballastlane.poke.config.LocalDataProperties;
import com.ballastlane.poke.exception.LocalDataVersionMismatchException;
import com.ballastlane.poke.exception.PokemonNotStoredLocallyException;
import com.ballastlane.poke.localdata.IfMatch;
import com.ballastlane.poke.localdata.LocalDataRequestParser;
import com.ballastlane.poke.localdata.LocalDataService;
import com.ballastlane.poke.localdata.LocalDataUpdate;
import com.ballastlane.poke.service.PokemonCatalogService;
import com.ballastlane.poke.service.PokemonDetailsService;
import com.ballastlane.poke.web.dto.LocalData;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PokemonController.class)
@Import({ LocalDataRequestParser.class, LocalDataConfig.class, WebSecurityTestConfiguration.class })
@WithMockUser(roles = "USER")
class PokemonLocalDataControllerTest {

	private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

	private static final LocalData UPDATED = new LocalData(List.of(), List.of(), List.of("mascot"),
			Instant.parse("2026-10-18T10:00:00Z"), 4);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PokemonDetailsService pokemonDetailsService;

	@MockitoBean
	private PokemonCatalogService pokemonCatalogService;

	@MockitoBean
	private LocalDataService localDataService;

	@TestConfiguration
	@EnableConfigurationProperties({ CatalogProperties.class, LocalDataProperties.class })
	static class PropertiesConfig {

	}

	@Test
	void replacesLocalData() throws Exception {
		given(localDataService.update(eq("pikachu"), any(), isNull())).willReturn(UPDATED);

		mockMvc.perform(put("/api/v1/pokemon/pikachu").contentType(MediaType.APPLICATION_JSON).content("""
				{ "localizedNames": [], "geoMetadata": [], "tags": [ "Mascot" ] }"""))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"4\""))
				.andExpect(jsonPath("$.tags[0]").value("mascot"))
				.andExpect(jsonPath("$.version").value(4));

		ArgumentCaptor<LocalDataUpdate> update = ArgumentCaptor.forClass(LocalDataUpdate.class);
		verify(localDataService).update(eq("pikachu"), update.capture(), isNull());
		assertThat(update.getValue()).isEqualTo(new LocalDataUpdate(List.of(), List.of(), List.of("mascot")));
	}

	@Test
	void patchesLocalDataWithMergePatchOrPlainJson() throws Exception {
		given(localDataService.update(eq("25"), any(), any())).willReturn(UPDATED);

		mockMvc.perform(patch("/api/v1/pokemon/25").contentType(MERGE_PATCH).header("If-Match", "\"3\"")
						.content("{ \"tags\": [ \"mascot\" ] }"))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"4\""));
		mockMvc.perform(patch("/api/v1/pokemon/25").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"tags\": null }"))
				.andExpect(status().isOk());

		ArgumentCaptor<IfMatch> ifMatch = ArgumentCaptor.forClass(IfMatch.class);
		verify(localDataService).update(eq("25"), eq(new LocalDataUpdate(null, null, List.of("mascot"))),
				ifMatch.capture());
		assertThat(ifMatch.getValue().matches(3)).isTrue();
		verify(localDataService).update("25", new LocalDataUpdate(null, null, List.of()), null);
	}

	@Test
	void listsEveryInvalidField() throws Exception {
		mockMvc.perform(put("/api/v1/pokemon/pikachu").contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "raichu", "geoMetadata": [ { "kind": "planet", "value": "kanto" } ], "tags": [] }"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"))
				.andExpect(jsonPath("$.errors.length()").value(3))
				.andExpect(jsonPath("$.errors[0].field").value("name"))
				.andExpect(jsonPath("$.errors[0].message").value("is read only"))
				.andExpect(jsonPath("$.errors[1].field").value("localizedNames"))
				.andExpect(jsonPath("$.errors[2].field").value("geoMetadata[0].kind"));

		verifyNoInteractions(localDataService);
	}

	@Test
	void rejectsMalformedAndMissingBodies() throws Exception {
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content("{ \"tags\": [ "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("body"))
				.andExpect(jsonPath("$.errors[0].message").value("is not valid JSON"));
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content("[]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].message").value("must be a JSON object"));
		mockMvc.perform(put("/api/v1/pokemon/pikachu").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].message").value("is required"));

		verifyNoInteractions(localDataService);
	}

	@Test
	void rejectsInvalidPathAndIfMatch() throws Exception {
		mockMvc.perform(patch("/api/v1/pokemon/pika_chu!").contentType(MERGE_PATCH).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("must be a pokemon name or numeric id"))
				.andExpect(jsonPath("$.errors[0].field").value("nameOrId"));
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).header("If-Match", "3")
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("'If-Match' must be '*' or a list of entity tags such as \"3\""));

		verifyNoInteractions(localDataService);
	}

	@Test
	void returns404WhenThePokemonIsNotStoredLocally() throws Exception {
		given(localDataService.update(eq("raichu"), any(), any()))
				.willThrow(new PokemonNotStoredLocallyException("raichu"));

		mockMvc.perform(patch("/api/v1/pokemon/raichu").contentType(MERGE_PATCH).content("{}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Pokemon not found"))
				.andExpect(jsonPath("$.detail").value(
						"Pokemon 'raichu' is not stored locally; look it up or synchronize it first"));
	}

	@Test
	void returns412ForAStaleVersion() throws Exception {
		given(localDataService.update(eq("pikachu"), any(), any()))
				.willThrow(new LocalDataVersionMismatchException(5));

		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).header("If-Match", "\"3\"")
						.content("{}"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.title").value("Local data has changed"))
				.andExpect(jsonPath("$.currentVersion").value(5));
	}

	@Test
	void rejectsOtherContentTypesMethodsAndLargeBodies() throws Exception {
		mockMvc.perform(put("/api/v1/pokemon/pikachu").contentType(MediaType.TEXT_PLAIN).content("tags"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.title").value("Unsupported media type"));
		mockMvc.perform(put("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content("{}"))
				.andExpect(status().isUnsupportedMediaType());
		mockMvc.perform(delete("/api/v1/pokemon/pikachu"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("PATCH")))
				.andExpect(jsonPath("$.title").value("Method not allowed"));
		String tooLarge = "{ \"tags\": [ \"" + "a".repeat(64 * 1024) + "\" ] }";
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content(tooLarge))
				.andExpect(status().isContentTooLarge())
				.andExpect(jsonPath("$.title").value("Request body too large"));

		verifyNoInteractions(localDataService);
	}

}
