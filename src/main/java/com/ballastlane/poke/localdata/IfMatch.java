package com.ballastlane.poke.localdata;

import com.ballastlane.poke.exception.InvalidIfMatchException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed {@code If-Match} header (RFC 9110): {@code *}, or a comma-separated list of entity tags.
 * <p>
 * Matching uses strong comparison, so a weak tag such as {@code W/"3"} never matches.
 */
public final class IfMatch {

	private static final Pattern ENTITY_TAG = Pattern.compile("\\s*(W/)?\"([\\x21\\x23-\\x7E]*)\"\\s*(,|$)");

	private final boolean any;

	private final List<String> strongTags;

	private IfMatch(boolean any, List<String> strongTags) {
		this.any = any;
		this.strongTags = strongTags;
	}

	/**
	 * @throws InvalidIfMatchException if the header is not valid
	 */
	public static IfMatch parse(String header) {
		if (header.isBlank()) {
			throw new InvalidIfMatchException();
		}
		if (header.strip().equals("*")) {
			return new IfMatch(true, List.of());
		}
		List<String> strongTags = new ArrayList<>();
		Matcher matcher = ENTITY_TAG.matcher(header);
		int position = 0;
		while (position < header.length()) {
			if (!matcher.find(position) || matcher.start() != position || matcher.end() == position) {
				throw new InvalidIfMatchException();
			}
			if (matcher.group(1) == null) {
				strongTags.add(matcher.group(2));
			}
			position = matcher.end();
		}
		return new IfMatch(false, List.copyOf(strongTags));
	}

	/**
	 * The entity tag of a version of the proprietary data, as sent in the {@code ETag} header.
	 */
	public static String entityTag(long version) {
		return "\"" + version + "\"";
	}

	public boolean matches(long version) {
		return any || strongTags.contains(Long.toString(version));
	}

}
