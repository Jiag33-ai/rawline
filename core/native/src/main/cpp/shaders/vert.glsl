#version 300 es
out vec2 vUv;
uniform float uFlipY;   // 1 when drawing to the screen (row 0 is the bottom there)
void main() {
    // Fullscreen triangle. vUv.y is image-down: texture row 0 is the image top everywhere.
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = vec2(p.x, mix(p.y, 1.0 - p.y, uFlipY));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
