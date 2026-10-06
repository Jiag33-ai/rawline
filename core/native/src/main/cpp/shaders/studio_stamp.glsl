#version 300 es
// Brush stamps into the stroke buffer: one instanced quad per stamp (up to 64 per draw), in layer pixel coordinates (row 0 = layer top).
uniform vec4 uStamps[64];   // x, y, radius, unused
uniform vec2 uSize;         // layer size in pixels
flat out vec3 vC;
void main() {
    vec4 s = uStamps[gl_InstanceID];
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0 - 1.0;
    vec2 p = s.xy + corner * (s.z + 1.0);
    vC = s.xyz;
    gl_Position = vec4(p / uSize * 2.0 - 1.0, 0.0, 1.0);
}
