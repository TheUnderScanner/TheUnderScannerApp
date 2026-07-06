package com.underscanner.theunderscannerapp

/**
 * Point-cloud coloring mode. The [ordinal] is the value passed straight to the shader's
 * `u_ColorMode` uniform, so the order here must match the shader's branches:
 * 0 uniform, 1 intensity, 2 height, 3 distance, 4 tag.
 */
enum class ColorMode { UNIFORM, INTENSITY, HEIGHT, DISTANCE, TAG }

/**
 * Livox tag-based noise filter level. The [ordinal] is the value passed to the shader's
 * `u_NoiseFilter` uniform: 0 off, 1 conservative (drop near-certain noise), 2 aggressive
 * (also drop probable rain/fog/mist). Harmless when every tag byte is 0.
 */
enum class NoiseFilter { OFF, CONSERVATIVE, AGGRESSIVE }
