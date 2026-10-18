package com.ballastlane.pokemon.web.dto;

/**
 * A base stat, e.g. {@code hp} = 35.
 */
public record Stat(String name, int value) {
}
