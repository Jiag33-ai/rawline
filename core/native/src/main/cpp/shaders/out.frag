#version 300 es
// Detail (noise reduction, sharpening), effects (vignette, grain) and the output transform.
precision highp float;
in vec2 vUv;
out vec4 oColor;

uniform sampler2D uE;        // adjusted linear image, with a margin of uMargin pixels on every side
uniform sampler2D uBase;     // base tone curve, 256 wide: sRGB encoded in -> display value out
uniform ivec2 uMargin;
uniform vec2 uPx;            // size of the region being written
uniform vec4 uDetail;        // sharpen amount (0..150), radius px, detail (0..100), masking (0..100)
uniform vec2 uNr;            // luminance, colour (0..100)
uniform vec4 uFx;            // vignette amount (-100..100), midpoint, roundness, feather
uniform vec4 uFx2;           // grain amount, size, roughness, seed
uniform vec4 uView;          // visible region in image-normalised coords (for vignette / grain)
uniform float uAspect;
uniform float uPxScale;      // output pixels per reference pixel (keeps radii consistent between preview and export)
uniform mat3 uToSrgb;        // working space (ProPhoto, D50) to linear sRGB
uniform float uOutLinear;    // 1 = target is a float buffer (16 bit export): same encoding, no 8 bit rounding
uniform float uChecker;      // 1 = draw a mid grey where the image is empty

const vec3 Y = vec3(0.28807, 0.71184, 0.0000857);

vec4 fetchE(ivec2 o) {
    ivec2 base = ivec2(vUv * uPx) + uMargin + o;
    return texelFetch(uE, base, 0);
}

float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float srgbOetf(float x) { return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055; }
vec3 srgbOetf3(vec3 c) { return vec3(srgbOetf(c.r), srgbOetf(c.g), srgbOetf(c.b)); }

void main() {
    vec4 e0 = fetchE(ivec2(0));
    vec3 c = e0.rgb;
    float inside = e0.a;

    float nrL = uNr.x * 0.01, nrC = uNr.y * 0.01;
    float sharp = uDetail.x * 0.01;
    if (nrL > 0.0 || nrC > 0.0 || sharp > 0.0) {
        float sc = max(uPxScale, 0.5);
        int st = int(clamp(floor(uDetail.y * sc + 0.5), 1.0, 3.0));
        // Neighbourhood in a perceptual-ish domain
        float yc = pow(max(dot(c, Y), 0.0), 1.0 / 2.4);
        vec3 acc = vec3(0.0);
        float wsum = 0.0;
        float ysum = 0.0, ywsum = 0.0;
        float grad = 0.0;
        for (int j = -2; j <= 2; j++) {
            for (int i = -2; i <= 2; i++) {
                vec3 n = fetchE(ivec2(i, j) * st).rgb;
                float yn = pow(max(dot(n, Y), 0.0), 1.0 / 2.4);
                float d = abs(yn - yc);
                float w = exp(-d * d / (0.0008 + nrL * 0.01)) * exp(-float(i * i + j * j) * 0.25);
                acc += n * w; wsum += w;
                ysum += yn * exp(-float(i * i + j * j) * 0.2); ywsum += exp(-float(i * i + j * j) * 0.2);
                grad += d;
            }
        }
        vec3 den = acc / wsum;
        c = mix(c, den, clamp(nrL, 0.0, 1.0));
        if (nrC > 0.0) {
            // Chroma smoothing: keep luminance, take the chroma of the neighbourhood average.
            float yl = dot(c, Y);
            vec3 avg = vec3(0.0);
            for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) avg += fetchE(ivec2(i, j) * st).rgb;
            avg /= 25.0;
            float ya = max(dot(avg, Y), 1.0e-5);
            vec3 chroma = avg / ya;
            c = mix(c, chroma * yl, clamp(nrC, 0.0, 1.0));
        }
        if (sharp > 0.0) {
            float yb = ysum / ywsum;
            float ycur = pow(max(dot(c, Y), 0.0), 1.0 / 2.4);
            float edge = smoothstep(0.0, 0.08, grad / 25.0);
            float mask = mix(1.0, edge, uDetail.w * 0.01);
            float amount = sharp * 1.5 * mask * (0.5 + uDetail.z * 0.01);
            float ynew = max(ycur + (ycur - yb) * amount, 0.0);
            float lin = pow(ynew, 2.4);
            c *= lin / max(dot(c, Y), 1.0e-5);
        }
    }

    // Vignette (post crop) and grain
    vec2 p = uView.xy + vUv * uView.zw;
    if (abs(uFx.x) > 0.001) {
        vec2 d = (p - 0.5) * 2.0;
        d.x *= mix(1.0, uAspect, uFx.z * 0.01 + 0.5);
        float r = length(d) / 1.41421;
        float mid = uFx.y * 0.01;
        float f = smoothstep(mid, mid + max(uFx.w * 0.01, 0.02) + 0.2, r);
        c *= exp2(uFx.x * 0.01 * 2.0 * f);
    }
    if (uFx2.x > 0.001) {
        vec2 gpx = vUv * uPx + (uView.xy / uView.zw) * uPx;   // pixel position in the whole image, so tiles share one grain field
        vec2 gp = floor(gpx / max(uFx2.y * 0.04 * uPxScale, 1.0)) + uFx2.w;
        float n = hash12(gp) - 0.5;
        float rough = mix(0.6, 1.4, uFx2.z * 0.01);
        float yl = pow(max(dot(c, Y), 0.0), 1.0 / 2.4);
        c *= exp2(n * uFx2.x * 0.01 * 0.6 * rough * (1.0 - abs(yl * 2.0 - 1.0) * 0.5));
    }

    vec3 lin = max(uToSrgb * c, 0.0);
    vec3 v = clamp(srgbOetf3(lin), 0.0, 1.0);
    // Base tone curve (calibrated against the camera JPEG look); identity is plain sRGB.
    v = vec3(texture(uBase, vec2((v.r * 255.0 + 0.5) / 256.0, 0.5)).r,
             texture(uBase, vec2((v.g * 255.0 + 0.5) / 256.0, 0.5)).r,
             texture(uBase, vec2((v.b * 255.0 + 0.5) / 256.0, 0.5)).r);
    if (inside < 0.5 && uChecker > 0.5) v = vec3(0.16);
    oColor = vec4(v, 1.0);
}
