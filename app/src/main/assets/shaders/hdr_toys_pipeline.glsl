//!DESC LumaXR hdr-toys Modular Tone & Gamut Pipeline (BT.2446a / BT.2390 / ST.2094 / Astra)
//!HOOK MAIN
//!BIND HOOKED
//!PARAM u_tm_mode
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 5
0
//!PARAM u_src_peak
//!TYPE float
//!MINIMUM 100.0
//!MAXIMUM 10000.0
1000.0
//!PARAM u_dst_peak
//!TYPE float
//!MINIMUM 100.0
//!MAXIMUM 600.0
240.0
//!PARAM u_astra_strength
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.35

// u_tm_mode: 0=off/passthrough, 1=bt2446a, 2=bt2390, 3=bt1886, 4=st2094-40/50, 5=astra

const vec3 L_2020 = vec3(0.2627, 0.6780, 0.0593);

float bt2390_eetf(float e, float lw, float lmax) {
    float ks = 1.5 * (lmax / max(lw, 1e-4)) - 0.5;
    ks = clamp(ks, 0.1, 0.95);
    if (e < ks) return e;
    float t = (e - ks) / max(1.0 - ks, 1e-4);
    float t2 = t * t;
    float t3 = t2 * t;
    float p = (2.0 * t3 - 3.0 * t2 + 1.0) * ks
            + (t3 - 2.0 * t2 + t) * (1.0 - ks)
            + (-2.0 * t3 + 3.0 * t2) * (lmax / max(lw, 1e-4));
    return p;
}

float bt2446a_tm(float y_rel, float src_peak, float dst_peak) {
    float phdr = 1.0 + 32.0 * pow(src_peak / 10000.0, 1.0 / 2.4);
    float psdr = 1.0 + 32.0 * pow(dst_peak / 10000.0, 1.0 / 2.4);
    float yp = pow(max(y_rel, 0.0), 1.0 / 2.4);
    float y_log = log(1.0 + (phdr - 1.0) * yp) / max(log(phdr), 1e-4);
    float yc;
    if (y_log <= 0.7399) {
        yc = y_log * 1.0770;
    } else if (y_log < 0.9909) {
        yc = (-1.1510 * y_log + 2.7811) * y_log - 0.6302;
    } else {
        yc = y_log * 0.5000 + 0.5000;
    }
    float y_sdr = (pow(psdr, yc) - 1.0) / max(psdr - 1.0, 1e-4);
    return pow(clamp(y_sdr, 0.0, 1.0), 2.4);
}

vec4 hook() {
    vec4 color = HOOKED_texOff(0.0);
    if (u_tm_mode == 0) {
        return color;
    }
    vec3 rgb = max(color.rgb, vec3(0.0));
    float y = dot(rgb, L_2020);
    if (y <= 1e-6) return color;

    float y_out = y;
    if (u_tm_mode == 1) {
        y_out = bt2446a_tm(y, u_src_peak, u_dst_peak);
    } else if (u_tm_mode == 2) {
        y_out = bt2390_eetf(y, u_src_peak, u_dst_peak);
    } else if (u_tm_mode == 3) {
        y_out = pow(clamp(y, 0.0, 1.0), 2.4 / 2.2);
    } else if (u_tm_mode == 4) {
        // ST 2094-40 / 50 dynamic knee compression
        float ratio = u_dst_peak / max(u_src_peak, 1.0);
        float knee = clamp(0.65 * ratio + 0.25, 0.35, 0.88);
        y_out = y <= knee ? y : knee + (1.0 - knee) * tanh((y - knee) / max(1.0 - knee, 1e-4));
    } else if (u_tm_mode == 5) {
        // Astra perceptual highlight saturation preservation
        float base = bt2446a_tm(y, u_src_peak, u_dst_peak);
        y_out = mix(y, base, 0.85);
        vec3 chroma = rgb - vec3(y);
        rgb = vec3(y) + chroma * (1.0 - u_astra_strength * smoothstep(0.6, 1.0, y));
    }

    float scale = y_out / max(y, 1e-6);
    return vec4(clamp(rgb * scale, 0.0, 1.0), color.a);
}
