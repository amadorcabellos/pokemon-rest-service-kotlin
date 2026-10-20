package com.ballastlane.pokemon.filter

import com.ballastlane.pokemon.filter.LocalDataRequestSizeFilter.RequestBodyTooLargeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.util.StreamUtils
import org.springframework.util.unit.DataSize

class LocalDataRequestSizeFilterTest {

    private val filter = LocalDataRequestSizeFilter(DataSize.ofBytes(10))

    @Test
    fun failsWhileReadingABodyWithoutContentLengthThatIsTooLarge() {
        val request = ChunkedRequest("PATCH", "/api/v1/pokemon/pikachu")
        request.setContent("{ \"tags\": [] }".toByteArray())

        shouldThrow<RequestBodyTooLargeException> {
            filter.doFilter(request, MockHttpServletResponse()) { req, _ -> StreamUtils.copyToByteArray(req.inputStream) }
        }
    }

    @Test
    fun letsSmallBodiesAndOtherRequestsThrough() {
        val small = ChunkedRequest("PUT", "/api/v1/pokemon/pikachu")
        small.setContent("{}".toByteArray())
        var read: ByteArray? = null
        filter.doFilter(small, MockHttpServletResponse()) { req, _ -> read = StreamUtils.copyToByteArray(req.inputStream) }
        read.shouldNotBeNull().size shouldBe 2

        val sync = MockHttpServletRequest("POST", "/api/v1/admin/sync/runs")
        sync.setContent("x".repeat(100).toByteArray())
        val response = MockHttpServletResponse()
        filter.doFilter(sync, response) { req, _ -> StreamUtils.copyToByteArray(req.inputStream) }
        response.status shouldBe 200
    }

    /** A request whose length is unknown up front, like a chunked one. */
    private class ChunkedRequest(method: String, uri: String) : MockHttpServletRequest(method, uri) {

        override fun getContentLengthLong(): Long = -1
    }
}
