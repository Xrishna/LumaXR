//!DESC LumaXR Sony BRAVIA XR-Style Physics-Synchronized LCD Master
//!HOOK OUTPUT
//!BIND HOOKED
//!PARAM u_inv_gain
//!TYPE float
//!MINIMUM 1.0
//!MAXIMUM 3.0
1.0
//!PARAM u_local_contrast
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.28
//!PARAM u_bilateral_radius
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 4
4
//!PARAM u_letterbox_top
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.45
0.0
//!PARAM u_letterbox_bottom
//!TYPE float
//!MINIMUM 0.55
//!MAXIMUM 1.0
1.0
//!PARAM u_split_wipe
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0
//!PARAM u_bypass_original
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0
//!PARAM u_bt1886_toe
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.02
0.0008

// Rec.709 / sRGB luminance weights (D65)
const vec3 LUMA_709 = vec3(0.2126, 0.7152, 0.0722);

vec3 srgb_to_linear(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow(max((c + 0.055) / 1.055, vec3(0.0)), vec3(2.4));
    return mix(hi, lo, lessThanEqual(c, vec3(0.04045)));
}

vec3 linear_to_srgb(vec3 c) {
    c = max(c, vec3(0.0));
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return clamp(mix(hi, lo, lessThanEqual(c, vec3(0.0031308))), 0.0, 1.0);
}

// C1-continuous soft-shoulder roll-off above linear luma 0.85
// Ensures f(0.85) = 0.85, f'(0.85) = 1.0, and smooth asymptotic compression toward 1.0
float soft_shoulder_c1(float x) {
    const float knee = 0.85;
    if (x <= knee) {
        return max(x, 0.0);
    }
    float t = x - knee;
    float head = 1.0 - knee; // 0.15
    // Rational C1 curve: knee + head * (t / (t + head)) -> derivative at t=0 is 1.0!
    return knee + head * (t / (t + head));
}

// Edge-preserving separable dual-scale local contrast on linear Y only
float edge_preserving_local_contrast(vec2 uv, float y_center) {
    if (u_local_contrast <= 0.001 || u_bilateral_radius <= 0) {
        return y_center;
    }
    int r = min(u_bilateral_radius, 4);
    float sigma_r_fine = 0.08;
    float sigma_r_coarse = 0.18;

    float sum_fine = 0.0;
    float w_fine = 0.0;
    float sum_coarse = 0.0;
    float w_coarse = 0.0;

    // Cross-separable tap pattern (up to 9 taps per axis -> <=17 samples total for 120 Hz Adreno 710 budget)
    for (int i = -4; i <= 4; i++) {
        if (abs(i) > r) continue;
        float fi = float(i);
        vec2 off_h = vec2(fi * HOOKED_pt.x * 2.0, 0.0);
        vec2 off_v = vec2(0.0, fi * HOOKED_pt.y * 2.0);
        vec2 off_c = vec2(fi * HOOKED_pt.x * 6.0, fi * HOOKED_pt.y * 6.0);

        float yh = dot(srgb_to_linear(HOOKED_tex(uv + off_h).rgb), LUMA_709);
        float yv = dot(srgb_to_linear(HOOKED_tex(uv + off_v).rgb), LUMA_709);
        float yc = dot(srgb_to_linear(HOOKED_tex(uv + off_c).rgb), LUMA_709);

        float wh = exp(-0.5 * pow((yh - y_center) / sigma_r_fine, 2.0) - 0.12 * fi * fi);
        float wv = exp(-0.5 * pow((yv - y_center) / sigma_r_fine, 2.0) - 0.12 * fi * fi);
        float wc = exp(-0.5 * pow((yc - y_center) / sigma_r_coarse, 2.0) - 0.08 * fi * fi);

        sum_fine += yh * wh + yv * wv;
        w_fine += wh + wv;
        sum_coarse += yc * wc;
        w_coarse += wc;
    }

    float base_fine = sum_fine / max(w_fine, 1e-5);
    float base_coarse = sum_coarse / max(w_coarse, 1e-5);
    float detail_fine = y_center - base_fine;
    float detail_mid = base_fine - base_coarse;

    // Halo suppression: attenuate enhancement near extreme contrast edges & near black
    float shadow_guard = smoothstep(0.008, 0.06, y_center);
    float edge_clamp = 1.0 / (1.0 + 18.0 * abs(detail_fine));
    float boosted = y_center + u_local_contrast * shadow_guard * edge_clamp * (0.55 * detail_fine + 0.35 * detail_mid);
    return max(boosted, 0.0);
}

vec4 hook() {
    vec4 orig = HOOKED_texOff(0.0);
    if (u_bypass_original > 0.5) {
        return orig;
    }

    vec2 uv = HOOKED_pos;
    // Live A/B Split Wipe divider line + right-side original comparison
    float wipe_dist = abs(uv.x - u_split_wipe);
    if (u_split_wipe < 0.999 && uv.x > u_split_wipe) {
        if (wipe_dist < HOOKED_pt.x * 2.0) {
            return vec4(0.36, 0.84, 1.0, 1.0); // Crisp cyan wipe line
        }
        return orig;
    }
    if (u_split_wipe < 0.999 && wipe_dist < HOOKED_pt.x * 2.0) {
        return vec4(0.36, 0.84, 1.0, 1.0);
    }

    // Cinema letterbox matte black clamp (prevents inverse gain from lifting noisy bars)
    if (uv.y < u_letterbox_top || uv.y > u_letterbox_bottom) {
        return vec4(0.0, 0.0, 0.0, orig.a);
    }

    vec3 lin = srgb_to_linear(orig.rgb);
    float y = dot(lin, LUMA_709);

    if (y <= 1e-5) {
        return vec4(0.0, 0.0, 0.0, orig.a);
    }

    // BT.1886 shadow toe suppression so inverse backlight gain doesn't reveal macroblock noise
    float toe = max(y - u_bt1886_toe, 0.0) / max(1.0 - u_bt1886_toe, 1e-4);
    float y_lc = edge_preserving_local_contrast(uv, toe);

    // Inverse backlight gain G(t) = 1.0 / D_actual(t), with C1 soft-shoulder > 0.85
    float y_gained = y_lc * u_inv_gain;
    float y_final = soft_shoulder_c1(y_gained);

    // Strictly preserve D65 chromaticity ratios (scale RGB uniformly by luminance ratio)
    float ratio = y_final / max(y, 1e-5);
    vec3 out_lin = lin * ratio;

    return vec4(linear_to_srgb(out_lin), orig.a);
}
