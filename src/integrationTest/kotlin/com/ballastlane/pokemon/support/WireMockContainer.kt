package com.ballastlane.pokemon.support

import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile

/**
 * WireMock container that stands in for PokeAPI, loading stubs from `src/integrationTest/resources/wiremock`.
 */
class WireMockContainer : GenericContainer<WireMockContainer>(DockerImageName.parse("wiremock/wiremock:3.13.1")) {

    private val httpClient = HttpClient.newHttpClient()

    init {
        withExposedPorts(PORT)
        withCopyFileToContainer(MountableFile.forClasspathResource("wiremock"), "/home/wiremock")
        waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200))
    }

    val baseUrl: String
        get() = "http://$host:${getMappedPort(PORT)}"

    /**
     * Number of requests WireMock received for the given URL path.
     */
    fun countRequests(urlPath: String): Int {
        val response = send(
            HttpRequest.newBuilder(URI.create("$baseUrl/__admin/requests/count"))
                .POST(HttpRequest.BodyPublishers.ofString("""{"method": "GET", "urlPath": "$urlPath"}"""))
                .header("Content-Type", "application/json")
                .build(),
        )
        val count = COUNT.find(response) ?: throw IllegalStateException("Unexpected WireMock response: $response")
        return count.groupValues[1].toInt()
    }

    /**
     * Registers a stub mapping at runtime (WireMock mapping JSON) and returns its id, for [removeStub].
     */
    fun addStub(mappingJson: String): String {
        val response = send(
            HttpRequest.newBuilder(URI.create("$baseUrl/__admin/mappings"))
                .POST(HttpRequest.BodyPublishers.ofString(mappingJson))
                .header("Content-Type", "application/json")
                .build(),
        )
        val id = ID.find(response) ?: throw IllegalStateException("Unexpected WireMock response: $response")
        return id.groupValues[1]
    }

    fun removeStub(stubId: String) {
        send(HttpRequest.newBuilder(URI.create("$baseUrl/__admin/mappings/$stubId")).DELETE().build())
    }

    fun resetRequests() {
        send(HttpRequest.newBuilder(URI.create("$baseUrl/__admin/requests")).DELETE().build())
    }

    private fun send(request: HttpRequest): String = try {
        httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()
    } catch (ex: IOException) {
        throw UncheckedIOException(ex)
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException(ex)
    }

    private companion object {

        const val PORT = 8080

        val COUNT = Regex("\"count\"\\s*:\\s*(\\d+)")

        val ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
    }
}
