package com.ballastlane.poke.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the body of local data updates (PUT and PATCH on {@code /api/v1/pokemon/*}).
 * <p>
 * A declared {@code Content-Length} over the limit is rejected straight away with 413. A body without one (chunked)
 * is counted while it is read, and reading fails with {@link RequestBodyTooLargeException} once it goes over the
 * limit; {@link GlobalExceptionHandler} turns that into 413 as well.
 */
public class LocalDataRequestSizeFilter extends OncePerRequestFilter {

	private static final String PATH_PREFIX = "/api/v1/pokemon/";

	private final long maxBytes;

	public LocalDataRequestSizeFilter(DataSize maxRequestSize) {
		this.maxBytes = maxRequestSize.toBytes();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String method = request.getMethod();
		boolean update = HttpMethod.PUT.matches(method) || HttpMethod.PATCH.matches(method);
		return !update || !request.getRequestURI().startsWith(PATH_PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			writeTooLarge(response);
			return;
		}
		chain.doFilter(new SizeLimitedRequest(request, maxBytes), response);
	}

	private void writeTooLarge(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
		response.setContentType("application/problem+json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("""
				{"type":"about:blank","title":"Request body too large","status":413,\
				"detail":"%s"}""".formatted(tooLargeDetail(maxBytes)));
	}

	static String tooLargeDetail(long maxBytes) {
		return "The request body must not be larger than %d bytes".formatted(maxBytes);
	}

	/**
	 * Reading a body past the limit.
	 */
	public static class RequestBodyTooLargeException extends IOException {

		private final long maxBytes;

		RequestBodyTooLargeException(long maxBytes) {
			super(tooLargeDetail(maxBytes));
			this.maxBytes = maxBytes;
		}

		public long getMaxBytes() {
			return maxBytes;
		}

	}

	private static final class SizeLimitedRequest extends HttpServletRequestWrapper {

		private final long maxBytes;

		private ServletInputStream inputStream;

		SizeLimitedRequest(HttpServletRequest request, long maxBytes) {
			super(request);
			this.maxBytes = maxBytes;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (inputStream == null) {
				inputStream = new CountingInputStream(super.getInputStream(), maxBytes);
			}
			return inputStream;
		}

	}

	private static final class CountingInputStream extends ServletInputStream {

		private final ServletInputStream delegate;

		private final long maxBytes;

		private long count;

		CountingInputStream(ServletInputStream delegate, long maxBytes) {
			this.delegate = delegate;
			this.maxBytes = maxBytes;
		}

		@Override
		public int read() throws IOException {
			int value = delegate.read();
			if (value != -1) {
				count(1);
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int read = delegate.read(buffer, offset, length);
			if (read > 0) {
				count(read);
			}
			return read;
		}

		private void count(int bytes) throws IOException {
			count += bytes;
			if (count > maxBytes) {
				throw new RequestBodyTooLargeException(maxBytes);
			}
		}

		@Override
		public boolean isFinished() {
			return delegate.isFinished();
		}

		@Override
		public boolean isReady() {
			return delegate.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			delegate.setReadListener(listener);
		}

	}

}
