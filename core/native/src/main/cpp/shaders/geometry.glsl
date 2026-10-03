// Shared geometry: maps output-normalised coordinates to source texture coordinates.
uniform vec2 uSrcSize;   // source pixels
uniform vec4 uCrop;      // x, y, w, h in the oriented base image, normalised
uniform vec4 uGeo;       // straighten angle (rad), flipH, flipV, rot90 (0..3, clockwise)
uniform vec4 uGeo2;      // keystone vertical, keystone horizontal, distortion, manual vignette
uniform vec4 uLensDist;  // lens profile distortion polynomial p0..p3
uniform vec2 uLensDist2; // p4, enabled

vec2 baseDims() { return (uGeo.w > 0.5 && uGeo.w < 1.5) || uGeo.w > 2.5 ? uSrcSize.yx : uSrcSize; }

// Returns source uv; z = 1 when inside the image.
vec3 srcUv(vec2 p) {
    vec2 dims = baseDims();
    vec2 c = uCrop.xy + p * uCrop.zw;
    vec2 q = (c - 0.5) * dims;
    vec2 n = q / dims;
    q.x *= 1.0 + uGeo2.x * n.y;
    q.y *= 1.0 + uGeo2.y * n.x;
    float s = sin(uGeo.x), co = cos(uGeo.x);
    q = vec2(co * q.x - s * q.y, s * q.x + co * q.y);
    if (uLensDist2.y > 0.5) {
        // Lens profile (lensfun ptlens/poly3/poly5 as a polynomial); radius is half of the shorter side
        float rn = length(q) / (0.5 * min(dims.x, dims.y));
        float f = uLensDist.x + rn * (uLensDist.y + rn * (uLensDist.z + rn * (uLensDist.w + rn * uLensDist2.x)));
        q *= f;
    }
    float r2 = dot(q, q) / (dims.y * dims.y * 0.25 + dims.x * dims.x * 0.25);
    q *= 1.0 + uGeo2.z * r2;
    vec2 b = q / dims + 0.5;
    if (uGeo.y > 0.5) b.x = 1.0 - b.x;
    if (uGeo.z > 0.5) b.y = 1.0 - b.y;
    vec2 u;
    int r = int(uGeo.w + 0.5);
    if (r == 1) u = vec2(b.y, 1.0 - b.x);
    else if (r == 2) u = vec2(1.0 - b.x, 1.0 - b.y);
    else if (r == 3) u = vec2(1.0 - b.y, b.x);
    else u = b;
    float inside = step(0.0, u.x) * step(u.x, 1.0) * step(0.0, u.y) * step(u.y, 1.0);
    return vec3(u, inside);
}
