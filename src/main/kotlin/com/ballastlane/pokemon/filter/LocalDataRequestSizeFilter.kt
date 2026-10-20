package com.ballastlane.pokemon.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletException
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.util.unit.DataSize
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Caps the body of local data updates (PUT and PATCH on `/api/v1/pokemon/{nameOrId}`).
 *
 * A declared `Content-Length` over the limit is rejected straight away with 413. A body without one (chunked) is
 * counted while it is read, and reading fails with [RequestBodyTooLargeException] once it goes over the limit;
 * [com.ballastlane.pokemon.handler.GlobalExceptionHandler] turns that into 413 as well.
 */
class LocalDataRequestSizeFilter(maxRequestSize: DataSize) : OncePerRequestFilter() {

    private val maxBytes: Long = maxRequestSize.toBytes()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val method = request.method
        val update = HttpMethod.PUT.matches(method) || HttpMethod.PATCH.matches(method)
        return !update || !request.requestURI.startsWith(PATH_PREFIX)
    }

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentLengthLong > maxBytes) {
            writeTooLarge(response)
            return
        }
        chain.doFilter(SizeLimitedRequest(request, maxBytes), response)
    }

    private fun writeTooLarge(response: HttpServletResponse) {
        response.status = HttpStatus.CONTENT_TOO_LARGE.value()
        response.contentType = "application/problem+json"
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"type":"about:blank","title":"Request body too large","status":413,""" +
                """"detail":"${tooLargeDetail(maxBytes)}"}""",
        )
    }

    /**
     * Reading a body past the limit.
     */
    class RequestBodyTooLargeException internal constructor(val maxBytes: Long) : IOException(tooLargeDetail(maxBytes))

    private class SizeLimitedRequest(request: HttpServletRequest, private val maxBytes: Long) :
        HttpServletRequestWrapper(request) {

        private var inputStream: ServletInputStream? = null

        @Throws(IOException::class)
        override fun getInputStream(): ServletInputStream =
            inputStream ?: CountingInputStream(super.getInputStream(), maxBytes).also { inputStream = it }
    }

    private class CountingInputStream(
        private val delegate: ServletInputStream,
        private val maxBytes: Long,
    ) : ServletInputStream() {

        private var count = 0L

        @Throws(IOException::class)
        override fun read(): Int {
            val value = delegate.read()
            if (value != -1) {
                count(1)
            }
            return value
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) {
                count(read)
            }
            return read
        }

        private fun count(bytes: Int) {
            count += bytes
            if (count > maxBytes) {
                throw RequestBodyTooLargeException(maxBytes)
            }
        }

        override fun isFinished(): Boolean = delegate.isFinished

        override fun isReady(): Boolean = delegate.isReady

        override fun setReadListener(listener: ReadListener) {
            delegate.setReadListener(listener)
        }
    }

    internal companion object {

        private const val PATH_PREFIX = "/api/v1/pokemon/"

        fun tooLargeDetail(maxBytes: Long): String = "The request body must not be larger than $maxBytes bytes"
    }
}
