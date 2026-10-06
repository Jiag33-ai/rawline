#version 300 es
// Builds the low resolution analysis image (linear luminance, dark channel) used by local tone ops.
precision highp float;
in vec2 vUv;
out vec4 oColor;
uniform sampler2D uSrc;
uniform float uLod;
uniform sampler2D uOverlay;   // heal and remove patches, premultiplied, source space (the same layer main.frag lays over the pixel)
uniform float uOverlayOn;
uniform float uSrcGain;
//@include geometry.glsl
const vec3 Y = vec3(0.28807, 0.71184, 0.0000857);
void main() {
    vec3 g = srcUv(vUv);
    vec3 c = textureLod(uSrc, clamp(g.xy, 0.0, 1.0), uLod).rgb * uSrcGain;
    if (uOverlayOn > 0.5) { vec4 o = texture(uOverlay, clamp(g.xy, 0.0, 1.0)); c = c * (1.0 - o.a) + o.rgb; }   // local tone is measured on the picture with its repairs
    c = max(c, 0.0);
    oColor = vec4(dot(c, Y), min(c.r, min(c.g, c.b)), 0.0, 1.0);
}
