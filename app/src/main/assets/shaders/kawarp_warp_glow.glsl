#version 320 es
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform float u_time;
uniform float u_frame_index;
uniform float u_warp_speed;
uniform float u_warp_scale;
uniform float u_saturation;
uniform float u_dimmer;
uniform vec3 u_dark_tint;
uniform float u_dark_tint_intensity;

// Audio FFT energy bands [0..1+]
uniform float u_bass;
uniform float u_mid;
uniform float u_treble;
uniform float u_beat_reactivity;

// Glow Controls
// 0=Off, 1=SoftBloom, 2=NeonCore, 3=ChromaticPrism, 4=CrispRim, 5=AdaptiveArtwork
uniform int u_glow_type;
uniform float u_glow_intensity; // 0.0 .. 2.0
uniform float u_glow_radius;    // 0.0 .. 3.0
uniform vec3 u_accent_color;
uniform vec2 u_resolution;

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float value_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash22(i + vec2(0.0, 0.0)).x;
    float b = hash22(i + vec2(1.0, 0.0)).x;
    float c = hash22(i + vec2(0.0, 1.0)).x;
    float d = hash22(i + vec2(1.0, 1.0)).x;
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    for (int i = 0; i < 4; i++) {
        v += amp * value_noise(p);
        p = rot * p * 2.02;
        amp *= 0.5;
    }
    return v;
}

vec3 adjust_saturation(vec3 col, float sat) {
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    return max(mix(vec3(luma), col, sat), vec3(0.0));
}

vec3 linear_to_srgb(vec3 c) {
    c = max(c, vec3(0.0));
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return clamp(mix(hi, lo, lessThanEqual(c, vec3(0.0031308))), 0.0, 1.0);
}

// Interleaved gradient noise + temporal golden ratio offset for TPDF 8-bit sRGB dithering
float tpdf_dither(vec2 frag_coord, float frame_idx) {
    float n1 = fract(52.9829189 * fract(dot(frag_coord + frame_idx * 0.61803398875, vec2(0.06711056, 0.00583715))));
    float n2 = fract(52.9829189 * fract(dot(frag_coord + (frame_idx + 17.0) * 0.61803398875, vec2(0.06711056, 0.00583715))));
    return (n1 + n2 - 1.0) / 255.0;
}

void main() {
    float react = u_beat_reactivity;
    float t = u_time * u_warp_speed + u_bass * 0.35 * react;
    float warp_amp = u_warp_scale * (0.22 + 0.18 * u_bass * react + 0.08 * u_mid * react);

    // Justin Zhang Diffuse-inspired double domain warping
    vec2 p = (v_uv - 0.5) * 2.2;
    vec2 q = vec2(fbm(p + vec2(0.0, t * 0.11)), fbm(p + vec2(5.2, -t * 0.09)));
    vec2 r = vec2(fbm(p + 2.4 * q + vec2(1.7, 9.2) + t * 0.14),
                  fbm(p + 2.4 * q + vec2(8.3, 2.8) - t * 0.12));

    vec2 warped_uv = clamp(v_uv + (r - 0.5) * warp_amp, vec2(0.01), vec2(0.99));
    vec3 base = texture(u_tex, warped_uv).rgb;

    // Optional Glow Pass (SoftBloom, NeonCore, ChromaticPrism, CrispRim, AdaptiveArtwork)
    if (u_glow_type > 0 && u_glow_intensity > 0.001) {
        vec2 texel = (1.0 / max(u_resolution, vec2(64.0))) * (1.0 + u_glow_radius * 6.0);
        vec3 bloom = vec3(0.0);
        if (u_glow_type == 3) {
            // Chromatic Prism dispersion
            bloom.r = texture(u_tex, clamp(warped_uv + texel * 1.4, 0.01, 0.99)).r;
            bloom.g = texture(u_tex, warped_uv).g;
            bloom.b = texture(u_tex, clamp(warped_uv - texel * 1.4, 0.01, 0.99)).b;
        } else {
            bloom += texture(u_tex, clamp(warped_uv + vec2(texel.x, 0.0), 0.01, 0.99)).rgb;
            bloom += texture(u_tex, clamp(warped_uv - vec2(texel.x, 0.0), 0.01, 0.99)).rgb;
            bloom += texture(u_tex, clamp(warped_uv + vec2(0.0, texel.y), 0.01, 0.99)).rgb;
            bloom += texture(u_tex, clamp(warped_uv - vec2(0.0, texel.y), 0.01, 0.99)).rgb;
            bloom *= 0.25;
        }
        float pulse = 1.0 + (u_bass * 0.45 + u_treble * 0.25) * react;
        vec3 glow_tint = (u_glow_type == 5) ? u_accent_color : bloom;
        float core_curve = (u_glow_type == 2) ? pow(max(dot(bloom, vec3(0.333)), 0.0), 1.6) * 2.2 : 1.0;
        float rim = (u_glow_type == 4) ? length(bloom - base) * 3.0 : 1.0;
        base += glow_tint * (0.28 * u_glow_intensity * pulse * core_curve * rim);
    }

    base = adjust_saturation(base, u_saturation);

    // Dark-area tinting (protects IPS LCD contrast and keeps lyrics crisp)
    float luma = dot(base, vec3(0.2126, 0.7152, 0.0722));
    float dark_mask = 1.0 - smoothstep(0.05, 0.45, luma);
    base = mix(base, u_dark_tint, dark_mask * u_dark_tint_intensity);
    base *= u_dimmer;

    vec3 srgb = linear_to_srgb(base);
    srgb = clamp(srgb + tpdf_dither(gl_FragCoord.xy, u_frame_index), 0.0, 1.0);
    fragColor = vec4(srgb, 1.0);
}
