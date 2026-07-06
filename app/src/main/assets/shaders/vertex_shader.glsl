// Point-cloud vertex shader.
//
// Position comes in as 3 floats; the 4 extra per-point bytes (reflectivity, tag, line,
// reserved) arrive as a normalized vec4 (each channel is byte/255 in [0,1]).
//
// All coloring is computed here (points are 1 fragment-ish, so per-vertex color is fine).
// The noise filter can't discard in the vertex stage, so we flag it via v_Discard and the
// fragment shader does the actual discard.

uniform mat4 u_MVPMatrix;
attribute vec4 a_Position;   // xyz (w defaults to 1.0 when the array supplies 3 comps)
attribute vec4 a_Attribs;    // r=reflectivity/255, g=tag/255, b=line/255, a=reserved

uniform int  u_ColorMode;    // 0 uniform, 1 intensity, 2 height(z), 3 distance, 4 tag
uniform vec4 u_Color;        // base color for uniform mode
uniform vec3 u_Sensor;       // sensor position (distance mode)
uniform vec2 u_HeightRange;  // (minZ, maxZ) for height mode
uniform float u_DistScale;   // normalization scale for distance mode
uniform vec2 u_ReflBounds;   // (low, high) in [0,1] for intensity mode
uniform int  u_NoiseFilter;  // 0 off, 1 conservative, 2 aggressive

varying vec4 v_Color;
varying float v_Discard;     // >0.5 => fragment discards

// Google "Turbo" colormap, Anton Mikhailov's polynomial approximation.
vec3 turbo(float x) {
    x = clamp(x, 0.0, 1.0);
    vec4 kRedVec4   = vec4(0.13572138, 4.61539260, -42.66032258, 132.13108234);
    vec4 kGreenVec4 = vec4(0.09140261, 2.19418839, 4.84296658, -14.18503333);
    vec4 kBlueVec4  = vec4(0.10667330, 12.64194608, -60.58204836, 110.36276771);
    vec2 kRedVec2   = vec2(-152.94239396, 59.28637943);
    vec2 kGreenVec2 = vec2(4.27729857, 2.82956604);
    vec2 kBlueVec2  = vec2(-89.90310912, 27.34824973);
    vec4 v4 = vec4(1.0, x, x * x, x * x * x);
    vec2 v2 = vec2(v4.z, v4.w) * x * x; // (x^4, x^5)
    return vec3(
        dot(v4, kRedVec4)   + dot(v2, kRedVec2),
        dot(v4, kGreenVec4) + dot(v2, kGreenVec2),
        dot(v4, kBlueVec4)  + dot(v2, kBlueVec2)
    );
}

void main() {
    gl_Position = u_MVPMatrix * a_Position;
    gl_PointSize = 6.0;

    // Decode the tag byte back to an integer 0..255, then split its bit groups.
    float tagF = floor(a_Attribs.g * 255.0 + 0.5);
    float spatialNoise  = mod(tagF, 4.0);              // bits 1-0
    float intensityNoise = mod(floor(tagF / 4.0), 4.0); // bits 3-2

    // --- Color by the active mode ---
    vec3 rgb;
    if (u_ColorMode == 1) {
        // Intensity: clamp reflectivity into [low, high] then map through turbo.
        float lo = u_ReflBounds.x;
        float hi = u_ReflBounds.y;
        float t = clamp((a_Attribs.r - lo) / max(hi - lo, 0.001), 0.0, 1.0);
        rgb = turbo(t);
    } else if (u_ColorMode == 2) {
        // Height: colormap on world Z.
        float t = clamp((a_Position.z - u_HeightRange.x) /
                        max(u_HeightRange.y - u_HeightRange.x, 0.001), 0.0, 1.0);
        rgb = turbo(t);
    } else if (u_ColorMode == 3) {
        // Distance to the sensor (world frame).
        float d = length(a_Position.xyz - u_Sensor);
        rgb = turbo(clamp(d / max(u_DistScale, 0.001), 0.0, 1.0));
    } else if (u_ColorMode == 4) {
        // Tag: categorical by worst noise indicator (1 = high, 2 = medium, 3 = low).
        if (spatialNoise == 1.0 || intensityNoise == 1.0) {
            rgb = vec3(1.0, 0.2, 0.2);      // near-certain noise
        } else if (spatialNoise == 2.0 || intensityNoise == 2.0) {
            rgb = vec3(1.0, 0.6, 0.1);      // probable (rain/fog)
        } else if (spatialNoise == 3.0 || intensityNoise == 3.0) {
            rgb = vec3(1.0, 0.9, 0.3);      // low confidence (likely real)
        } else {
            rgb = vec3(0.4, 0.9, 0.55);     // normal
        }
    } else {
        // Uniform (default): use the base color as-is.
        rgb = u_Color.rgb;
    }
    v_Color = vec4(rgb, 1.0);

    // --- Noise filter (applies in every color mode) ---
    v_Discard = 0.0;
    if (u_NoiseFilter >= 1) {
        if (spatialNoise == 1.0 || intensityNoise == 1.0) v_Discard = 1.0;
    }
    if (u_NoiseFilter >= 2) {
        if (spatialNoise == 2.0 || intensityNoise == 2.0) v_Discard = 1.0;
    }
}
