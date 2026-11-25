#version 320 es
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_prev_tex;
uniform float u_time;
uniform float u_dt;
uniform int u_preset;
uniform float u_bass;
uniform float u_mid;
uniform float u_treble;
uniform float u_react;
uniform vec3 u_accent;
uniform vec2 u_resolution;

vec3 palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
    return max(a + b * cos(6.28318 * (c * t + d)), vec3(0.0));
}

void main() {
    float aspect = max(u_resolution.x, 1.0) / max(u_resolution.y, 1.0);
    vec2 p = (v_uv - 0.5) * vec2(aspect, 1.0);
    float r = length(p);
    float ang = (r > 1e-4) ? atan(p.y, p.x) : 0.0;

    float dt_scale = clamp(u_dt * 60.0, 0.25, 2.5);
    float zoom = 1.0 - (0.015 + 0.015 * u_bass * u_react) * dt_scale;
    float rot = (0.008 * (1.0 + u_mid * u_react)) * dt_scale;
    if (u_preset == 1) { zoom = 1.0 - (0.025 + 0.012 * u_bass * u_react) * dt_scale; rot = -0.012 * (1.0 + u_mid * u_react) * dt_scale; }
    if (u_preset == 3) { zoom = 1.0 - (0.008 + 0.008 * u_bass * u_react) * dt_scale; rot = 0.002 * sin(u_time * 0.5) * dt_scale; }

    mat2 m = mat2(cos(rot), -sin(rot), sin(rot), cos(rot));
    vec2 fb_p = m * (p * zoom);
    vec2 fb_uv = clamp(fb_p / vec2(aspect, 1.0) + 0.5 + 0.004 * vec2(sin(p.y * 9.0 + u_time), cos(p.x * 9.0 - u_time)), vec2(0.002), vec2(0.998));

    vec3 prev = clamp(texture(u_prev_tex, fb_uv).rgb, vec3(0.0), vec3(8.0));

    float wave = sin(r * 14.0 - u_time * 2.2 + u_bass * 4.0 * u_react)
               * cos(ang * (3.0 + float(u_preset)) + u_time * 0.8);
    if (u_preset == 2) {
        wave = sin(r * 18.0 - u_time * 3.1) + 0.5 * cos(ang * 5.0 + u_bass * 5.0 * u_react);
    } else if (u_preset == 3) {
        vec2 g = abs(fract(v_uv * 16.0 - 0.5) - 0.5) / max(fwidth(v_uv * 16.0), vec2(0.01));
        wave = 1.0 - min(min(g.x, g.y), 1.0);
    } else if (u_preset == 4) {
        float yw = sin(v_uv.x * 18.0 + u_time * 3.0) * (0.12 + 0.25 * u_bass * u_react);
        wave = smoothstep(0.06, 0.0, abs(p.y - yw));
    } else if (u_preset == 5) {
        wave = 0.5 + 0.5 * sin(p.x * 8.0 + sin(p.y * 6.0 + u_time) + u_mid * 3.0 * u_react);
    }

    vec3 col = palette(wave * 0.35 + u_time * 0.05 + float(u_preset) * 0.17,
                       vec3(0.50), vec3(0.45), vec3(1.0), vec3(0.0, 0.33, 0.67));
    col = mix(col, u_accent, 0.35);

    float decay = pow(0.91, dt_scale);
    float inject = smoothstep(0.15, 0.95, abs(wave)) * (0.18 + 0.45 * (u_bass + u_treble) * 0.5 * u_react) * dt_scale;
    vec3 out_col = clamp(prev * decay + col * inject, vec3(0.0), vec3(4.0));
    fragColor = vec4(out_col, 1.0);
}
