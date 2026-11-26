//!DESC LumaXR hdr-toys Modular Tone & Gamut Pipeline (BT.2446a / BT.2390 / ST.2094 / Astra)
//!HOOK LINEAR
//!BIND HOOKED
//!PARAM u_tm_mode
//!TYPE DYNAMIC int
//!MINIMUM 0
//!MAXIMUM 5
0
//!PARAM u_src_peak
//!TYPE DYNAMIC float
//!MINIMUM 100.0
//!MAXIMUM 10000.0
1000.0
//!PARAM u_dst_peak
//!TYPE DYNAMIC float
//!MINIMUM 100.0
//!MAXIMUM 600.0
240.0
//!PARAM u_astra_strength
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.35

const vec3 L_2020 = vec3(0.2627, 0.6780, 0.0593);

float lin_to_pq(float x) {
    float c = pow(max(x, 0.0), 0.1593017578);
    return pow((0.8359375 + 18.8515625 * c) / (1.0 + 18.6875 * c), 78.84375);
}

float pq_to_lin(float e) {
    float p = pow(clamp(e, 0.0, 1.0), 1.0 / 78.84375);
    float num = max(p - 0.8359375, 0.0);
    float den = max(18.8515625 - 18.6875 * p, 1e-5);
    return pow(num / den, 1.0 / 0.1593017578);
}

float bt2390_eetf(float y_rel, float lw, float lmax) {
    if (lmax >= lw) return y_rel;
    float e_src = lin_to_pq(clamp(y_rel * (lw / 10000.0), 0.0, 1.0));
    float pq_max = lin_to_pq(lmax / 10000.0);
    float pq_src = lin_to_pq(lw / 10000.0);
    float e = clamp(e_src / max(pq_src, 1e-4), 0.0, 1.0);
    float max_lum = clamp(pq_max / max(pq_src, 1e-4), 0.05, 1.0);
    float ks = clamp(1.5 * max_lum - 0.5, 0.1, 0.92);
    float e_out;
    if (e < ks) {
        e_out = e;
    } else {
        float t = (e - ks) / max(1.0 - ks, 1e-4);
        float t2 = t * t;
        float t3 = t2 * t;
        e_out = (2.0 * t3 - 3.0 * t2 + 1.0) * ks
              + (t3 - 2.0 * t2 + t) * (1.0 - ks)
              + (-2.0 * t3 + 3.0 * t2) * max_lum;
    }
    return pq_to_lin(e_out * pq_src) * (10000.0 / max(lw, 1.0));
}

float bt2446a_tm(float y_rel, float src_peak, float dst_peak) {
    if (dst_peak >= src_peak) return y_rel;
    float phdr = 1.0 + 32.0 * pow(src_peak / 10000.0, 1.0 / 2.4);
    float psdr = 1.0 + 32.0 * pow(dst_peak / 10000.0, 1.0 / 2.4);
    float yp = pow(clamp(y_rel, 0.0, 1.0), 1.0 / 2.4);
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
    if (u_tm_mode == 0) return color;

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
        float ratio = clamp(u_dst_peak / max(u_src_peak, 1.0), 0.1, 1.0);
        float knee = clamp(0.65 * ratio + 0.25, 0.35, 0.88);
        y_out = y <= knee ? y : knee + (ratio - knee) * tanh((y - knee) / max(1.0 - knee, 1e-4));
    } else if (u_tm_mode == 5) {
        y_out = mix(y, bt2446a_tm(y, u_src_peak, u_dst_peak), 0.85);
        vec3 chroma = rgb - vec3(y);
        rgb = vec3(y) + chroma * (1.0 - u_astra_strength * smoothstep(0.6, 1.0, y));
    }

    vec3 out_rgb = rgb * (y_out / max(y, 1e-6));
    float peak_c = max(out_rgb.r, max(out_rgb.g, out_rgb.b));
    if (peak_c > 1.0) out_rgb /= peak_c;
    return vec4(max(out_rgb, vec3(0.0)), color.a);
}
