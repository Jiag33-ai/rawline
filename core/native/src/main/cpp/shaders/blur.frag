#version 300 es
precision highp float;
in vec2 vUv;
out vec4 oColor;
uniform sampler2D uTex;
uniform vec2 uStep;   // texel step along the blur axis, in uv
void main() {
    // 13 tap gaussian (sigma ~ 2.5 steps)
    float w[7] = float[7](0.159, 0.147, 0.117, 0.080, 0.047, 0.024, 0.010);
    vec4 acc = texture(uTex, vUv) * w[0];
    float sum = w[0];
    for (int i = 1; i < 7; i++) {
        acc += texture(uTex, vUv + uStep * float(i)) * w[i];
        acc += texture(uTex, vUv - uStep * float(i)) * w[i];
        sum += 2.0 * w[i];
    }
    oColor = acc / sum;
}
