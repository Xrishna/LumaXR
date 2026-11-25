#version 320 es
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform vec2 u_half_pixel;
uniform float u_offset;

void main() {
    vec2 off = u_half_pixel * u_offset;
    vec4 sum = vec4(0.0);
    sum += texture(u_tex, v_uv + vec2(-off.x * 2.0, 0.0));
    sum += texture(u_tex, v_uv + vec2(off.x * 2.0, 0.0));
    sum += texture(u_tex, v_uv + vec2(0.0, -off.y * 2.0));
    sum += texture(u_tex, v_uv + vec2(0.0, off.y * 2.0));

    sum += texture(u_tex, v_uv + vec2(-off.x, off.y)) * 2.0;
    sum += texture(u_tex, v_uv + vec2(off.x, off.y)) * 2.0;
    sum += texture(u_tex, v_uv + vec2(-off.x, -off.y)) * 2.0;
    sum += texture(u_tex, v_uv + vec2(off.x, -off.y)) * 2.0;

    fragColor = clamp(sum / 12.0, vec4(0.0), vec4(16.0));
}
