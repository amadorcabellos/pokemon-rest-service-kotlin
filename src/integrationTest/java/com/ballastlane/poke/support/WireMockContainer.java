package com.ballastlane.poke.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * WireMock container that stands in for PokeAPI, loading stubs from {@code src/integrationTest/resources/wiremock}.
 */
public class WireMockContainer extends GenericContainer<WireMockContainer> {

	private static final int PORT = 8080;

	private static final Pattern COUNT = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

	private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

	private final HttpClient httpClient = HttpClient.newHttpClient();

	public WireMockContainer() {
		super(DockerImageName.parse("wiremock/wiremock:3.13.1"));
		withExposedPorts(PORT);
		withCopyFileToContainer(MountableFile.forClasspathResource("wiremock"), "/home/wiremock");
		waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));
	}

	public String getBaseUrl() {
		return "http://%s:%d".formatted(getHost(), getMappedPort(PORT));
	}

	/**
	 * Number of requests WireMock received for the given URL path.
	 */
	public int countRequests(String urlPath) {
		String body = """
				{"method": "GET", "urlPath": "%s"}""".formatted(urlPath);
		String response = send(HttpRequest.newBuilder(URI.create(getBaseUrl() + "/__admin/requests/count"))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.header("Content-Type", "application/json")
				.build());
		Matcher matcher = COUNT.matcher(response);
		if (!matcher.find()) {
			throw new IllegalStateException("Unexpected WireMock response: " + response);
		}
		return Integer.parseInt(matcher.group(1));
	}

	/**
	 * Registers a stub mapping at runtime (WireMock mapping JSON) and returns its id, for {@link #removeStub}.
	 */
	public String addStub(String mappingJson) {
		String response = send(HttpRequest.newBuilder(URI.create(getBaseUrl() + "/__admin/mappings"))
				.POST(HttpRequest.BodyPublishers.ofString(mappingJson))
				.header("Content-Type", "application/json")
				.build());
		Matcher matcher = ID.matcher(response);
		if (!matcher.find()) {
			throw new IllegalStateException("Unexpected WireMock response: " + response);
		}
		return matcher.group(1);
	}

	public void removeStub(String stubId) {
		send(HttpRequest.newBuilder(URI.create(getBaseUrl() + "/__admin/mappings/" + stubId)).DELETE().build());
	}

	public void resetRequests() {
		send(HttpRequest.newBuilder(URI.create(getBaseUrl() + "/__admin/requests")).DELETE().build());
	}

	private String send(HttpRequest request) {
		try {
			return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
