#version 300 es
// Coverage of one round stamp (spec 4.3) accumulated with 'over' by the blend state (ONE, ONE_MINUS_SRC_ALPHA): a = a + s (1 - a).
precision highp float;
flat in vec3 vC;            // centre x, y and radius in layer pixels
uniform float uHardness;
uniform float uFlow;
out vec4 oColor;
void main() {
    float r = distance(gl_FragCoord.xy, vC.xy);   // pixel centre against the stamp centre, as the CPU reference does
    float R = vC.z;
    float inner = uHardness * R;
    float c = 0.0;
    if (r < R) {
        if (r <= inner) c = 1.0;
        else { float u = 1.0 - (r - inner) / (R - inner); c = u * u * (3.0 - 2.0 * u); }
    }
    float s = uFlow * c;
    oColor = vec4(s, 0.0, 0.0, s);
}
