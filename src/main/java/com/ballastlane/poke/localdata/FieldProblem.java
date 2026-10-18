package com.ballastlane.poke.localdata;

/**
 * One invalid part of a request.
 *
 * @param field where the problem is, e.g. {@code tags[2]} or {@code geoMetadata[0].kind}; {@code body} for the
 * request body as a whole
 * @param message what is wrong with it; never echoes the submitted value
 */
public record FieldProblem(String field, String message) {
}
