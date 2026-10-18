package com.ballastlane.poke.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ballastlane.poke.web.LocalDataRequestSizeFilter.RequestBodyTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;
import org.springframework.util.unit.DataSize;

class LocalDataRequestSizeFilterTest {

	private final LocalDataRequestSizeFilter filter = new LocalDataRequestSizeFilter(DataSize.ofBytes(10));

	@Test
	void failsWhileReadingABodyWithoutContentLengthThatIsTooLarge() {
		MockHttpServletRequest request = new ChunkedRequest("PATCH", "/api/v1/pokemon/pikachu");
		request.setContent("{ \"tags\": [] }".getBytes());

		assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
				(req, res) -> StreamUtils.copyToByteArray(req.getInputStream())))
				.isInstanceOf(RequestBodyTooLargeException.class);
	}

	@Test
	void letsSmallBodiesAndOtherRequestsThrough() throws Exception {
		MockHttpServletRequest small = new ChunkedRequest("PUT", "/api/v1/pokemon/pikachu");
		small.setContent("{}".getBytes());
		byte[][] read = new byte[1][];
		filter.doFilter(small, new MockHttpServletResponse(),
				(req, res) -> read[0] = StreamUtils.copyToByteArray(req.getInputStream()));
		assertThat(read[0]).hasSize(2);

		MockHttpServletRequest sync = new MockHttpServletRequest("POST", "/api/v1/admin/sync/runs");
		sync.setContent("x".repeat(100).getBytes());
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(sync, response, (req, res) -> StreamUtils.copyToByteArray(req.getInputStream()));
		assertThat(response.getStatus()).isEqualTo(200);
	}

	/** A request whose length is unknown up front, like a chunked one. */
	private static final class ChunkedRequest extends MockHttpServletRequest {

		ChunkedRequest(String method, String uri) {
			super(method, uri);
		}

		@Override
		public long getContentLengthLong() {
			return -1;
		}

	}

}
