package com.ballastlane.pokemon.client;

/**
 * Helpers for PokeAPI resource URLs.
 */
public final class PokeApiUrls {

	private PokeApiUrls() {
	}

	/**
	 * Resource id taken from the last path segment of a PokeAPI URL (e.g. {@code .../pokemon-species/25/} → 25).
	 */
	public static int idFrom(String url) {
		String path = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		return Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
	}

}
