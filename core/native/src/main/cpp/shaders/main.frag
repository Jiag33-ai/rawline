#version 300 es
// Global and local adjustments in linear ProPhoto. Output stays linear (RGBA16F) for the detail pass.
precision highp float;
precision highp int;
precision highp sampler2DArray;
in vec2 vUv;
out vec4 oColor;

uniform sampler2D uSrc;
uniform sampler2D uBs;       // blurred luminance, small radius (x = Y, y = dark channel)
uniform sampler2D uBl;       // blurred luminance, large radius
uniform sampler2D uBd;       // blurred dark channel, widest radius
uniform sampler2D uBlocks;   // adjustment blocks, 20 texels wide, one row per block
uniform sampler2D uMasks;    // mask definitions, 32 texels wide, one row per mask
uniform sampler2D uCurves;   // 256 wide, rows = block * 4 + channel (master, r, g, b)
uniform sampler2DArray uLayers;  // bitmap mask layers (brush, AI)
uniform sampler2D uOverlay;  // remove/heal overlay, premultiplied, source space
uniform float uOverlayOn;
uniform vec4 uView;          // visible region of the output image, normalised: x, y, w, h
uniform vec2 uOutPx;         // pixels in the render target
uniform float uLod;
uniform int uNumMasks;
uniform int uShowMask;
uniform float uAspect;       // output image width / height
uniform vec3 uTcaR;          // lens chromatic aberration scale polynomials (v, c, b)
uniform vec3 uTcaB;
uniform vec3 uLensVig;       // lens vignetting k1 k2 k3
uniform vec3 uLensFlags;     // tca on, vignetting on

//@include geometry.glsl

const vec3 Y = vec3(0.28807, 0.71184, 0.0000857);

vec4 B(int block, int k) { return texelFetch(uBlocks, ivec2(k, block), 0); }

float luma(vec3 c) { return dot(c, Y); }

vec3 toGamma(vec3 c) { return pow(max(c, 0.0), vec3(1.0 / 2.2)); }
vec3 toLinear(vec3 c) { return pow(max(c, 0.0), vec3(2.2)); }

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

// Band centres (hue 0..1): red, orange, yellow, green, aqua, blue, purple, magenta
const float BAND[8] = float[8](0.0, 0.0833, 0.1667, 0.3333, 0.5, 0.6667, 0.7778, 0.8889);
float bandWeight(float h, int i) {
    float d = abs(h - BAND[i]);
    d = min(d, 1.0 - d);
    float width = 0.0833;
    return smoothstep(width * 1.6, 0.0, d);
}

float curveLookup(int block, int ch, float x) {
    float u = clamp(x, 0.0, 1.0) * 255.0;
    float v = (float(block * 4 + ch) + 0.5) / float(textureSize(uCurves, 0).y);
    return texture(uCurves, vec2((u + 0.5) / 256.0, v)).r;
}

vec3 grade(vec3 g, int block) {
    // g is gamma encoded. Three-way colour grading.
    vec4 sh = B(block, 10), mi = B(block, 11), hi = B(block, 12), gl = B(block, 13), bl = B(block, 14);
    float l = dot(g, vec3(0.2126, 0.7152, 0.0722));
    float blend = bl.x;          // 0..1 (default 0.5)
    float bal = bl.y;            // -1..1
    float lo = 0.33 + bal * 0.2, hiE = 0.66 + bal * 0.2;
    float spread = mix(0.12, 0.45, blend);
    float ws = 1.0 - smoothstep(lo - spread, lo + spread, l);
    float wh = smoothstep(hiE - spread, hiE + spread, l);
    float wm = clamp(1.0 - ws - wh, 0.0, 1.0);
    vec3 tint = vec3(0.0);
    vec3 ts = hsv2rgb(vec3(sh.x, 1.0, 1.0)) - 0.5;
    vec3 tm = hsv2rgb(vec3(mi.x, 1.0, 1.0)) - 0.5;
    vec3 th = hsv2rgb(vec3(hi.x, 1.0, 1.0)) - 0.5;
    vec3 tg = hsv2rgb(vec3(gl.x, 1.0, 1.0)) - 0.5;
    tint += ts * sh.y * ws + tm * mi.y * wm + th * hi.y * wh + tg * gl.y;
    float lum = sh.z * ws + mi.z * wm + hi.z * wh + gl.z;
    return max(g + tint * 0.35 + lum * 0.25, 0.0);
}

// Applies one adjustment block. 'bs','bl','bd' are the local analysis samples at this pixel.
vec3 adjust(vec3 c, int block, float bs, float bl, float bd) {
    vec4 b0 = B(block, 0), b1 = B(block, 1), b2 = B(block, 2), b3 = B(block, 3);
    float exposure = b0.x, contrast = b0.y, highlights = b0.z, shadows = b0.w;
    float whites = b1.x, blacks = b1.y, temp = b1.z, tint = b1.w;
    float vibrance = b2.x, saturation = b2.y, texture = b2.z, clarity = b2.w;
    float dehaze = b3.x;

    // White balance in the working space (approximation of a camera space WB shift).
    c *= vec3(exp2(temp * 0.012), exp2(-tint * 0.006), exp2(-temp * 0.012));
    float gain = exp2(exposure);
    c *= gain;
    float Y0 = max(luma(c), 1.0e-5);

    // Local tone: highlights and shadows use the blurred luminance so there is no haloing.
    float base = max(bl * gain, 1.0e-5);
    float baseG = pow(base, 1.0 / 2.4);
    float sh = shadows * 0.01, hl = highlights * 0.01;
    float wSh = 1.0 - smoothstep(0.05, 0.55, baseG);
    float wHi = smoothstep(0.45, 0.95, baseG);
    float tone = exp2(sh * 1.6 * wSh + hl * 1.6 * wHi);
    c *= tone;

    // Texture (fine) and clarity (broad) local contrast from the blurred layers.
    float Ym = max(luma(c), 1.0e-5);
    float fine = log2(Ym / max(bs * gain * tone, 1.0e-5));
    float broad = log2(Ym / max(bl * gain * tone, 1.0e-5));
    float mid = 1.0 - abs(pow(Ym, 1.0 / 2.4) * 2.0 - 1.0);
    c *= exp2(clamp(fine, -2.0, 2.0) * texture * 0.01 * 0.8 + clamp(broad, -2.0, 2.0) * clarity * 0.01 * 0.8 * mid);

    // Dehaze via dark channel prior.
    if (abs(dehaze) > 0.001) {
        float A = max(0.9 * gain * tone, 0.2);
        float dm = clamp(bd * gain * tone / A, 0.0, 0.95);
        float d = dehaze * 0.01;
        if (d > 0.0) {
            float t = max(1.0 - d * 0.95 * dm, 0.15);
            c = (c - A * (1.0 - t)) / t;
        } else {
            float t = 1.0 - d * 0.5;
            c = c * t + A * (1.0 - t) * dm * -d;
        }
    }

    // Blacks and whites move the end points.
    float bp = -blacks * 0.0006;
    float wp = 1.0 - whites * 0.0035;
    c = (c - bp) / max(wp - bp, 0.05);

    // Contrast: S curve in gamma space around mid grey.
    float mx = max(1.0, max(c.r, max(c.g, c.b)));
    vec3 g = toGamma(max(c, 0.0) / mx);
    float k = contrast * 0.01;
    vec3 s = g * g * (3.0 - 2.0 * g);
    g = k >= 0.0 ? mix(g, s, k) : mix(g, vec3(0.5) + (g - 0.5) * (1.0 + k * 0.8), -k);

    // Saturation, vibrance and the HSL colour mixer.
    vec3 hsv = rgb2hsv(clamp(g, 0.0, 1.0));
    float hueShift = 0.0, satMul = 0.0, lumMul = 0.0;
    for (int i = 0; i < 8; i++) {
        float w = bandWeight(hsv.x, i) * smoothstep(0.02, 0.2, hsv.y);
        vec4 hv = B(block, 4 + (i >> 2)), sv = B(block, 6 + (i >> 2)), lv = B(block, 8 + (i >> 2));
        int j = i & 3;
        hueShift += w * hv[j];
        satMul += w * sv[j];
        lumMul += w * lv[j];
    }
    hsv.x = fract(hsv.x + hueShift * 0.0006 + 1.0);
    float sat = 1.0 + saturation * 0.01;
    float vib = vibrance * 0.01 * (1.0 - hsv.y);
    hsv.y = clamp(hsv.y * (sat + vib) * (1.0 + satMul * 0.01), 0.0, 1.0);
    hsv.z = clamp(hsv.z * (1.0 + lumMul * 0.005), 0.0, 1.0);
    g = hsv2rgb(hsv);

    g = grade(g, block);

    // Tone curves: master then per channel.
    vec4 flags = B(block, 15);
    if (flags.x > 0.5 || flags.y > 0.5 || flags.z > 0.5 || flags.w > 0.5) {
        if (flags.x > 0.5) g = vec3(curveLookup(block, 0, g.r), curveLookup(block, 0, g.g), curveLookup(block, 0, g.b));
        if (flags.y > 0.5) g.r = curveLookup(block, 1, g.r);
        if (flags.z > 0.5) g.g = curveLookup(block, 2, g.g);
        if (flags.w > 0.5) g.b = curveLookup(block, 3, g.b);
    }
    return toLinear(g) * mx;
}

float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }

// Mask evaluation. Each mask: texel 0 = header, then 3 texels per component.
// p is the crop-independent full-frame position, asp the aspect of the oriented base image.
float maskAlpha(int m, vec2 p, float asp, vec3 c) {
    vec4 h = texelFetch(uMasks, ivec2(0, m), 0);   // x = components, y = amount, z = invert, w = feather
    int n = int(h.x + 0.5);
    float acc = 0.0;
    for (int i = 0; i < 6; i++) {
        if (i >= n) break;
        vec4 a = texelFetch(uMasks, ivec2(1 + i * 3, m), 0);      // type, op, invert, layer
        vec4 q = texelFetch(uMasks, ivec2(2 + i * 3, m), 0);
        vec4 r = texelFetch(uMasks, ivec2(3 + i * 3, m), 0);
        int type = int(a.x + 0.5);
        float v = 0.0;
        vec2 pa = vec2(p.x * asp, p.y);
        if (type == 1) {            // linear gradient: q.xy start, q.zw end (normalised), r.x feather unused
            vec2 s = vec2(q.x * asp, q.y), e = vec2(q.z * asp, q.w);
            vec2 d = e - s;
            float t = dot(pa - s, d) / max(dot(d, d), 1.0e-6);
            v = clamp(t, 0.0, 1.0);
        } else if (type == 2) {     // radial: q.xy centre, q.zw radii (x in height units), r.x angle, r.y feather 0..1
            vec2 d = pa - vec2(q.x * asp, q.y);
            float ca = cos(r.x), sa = sin(r.x);
            d = vec2(ca * d.x + sa * d.y, -sa * d.x + ca * d.y);
            float e = length(d / max(q.zw, vec2(1.0e-4)));
            float f = clamp(r.y, 0.01, 1.0);
            v = 1.0 - smoothstep(1.0 - f, 1.0, e);
        } else if (type == 3) {     // bitmap layer
            v = texture(uLayers, vec3(p, a.w)).r;
        } else if (type == 4) {     // colour range: q.rgb target (gamma), q.a range, r.x softness
            vec3 g = toGamma(c);
            float d = distance(g, q.rgb);
            v = 1.0 - smoothstep(q.a * (1.0 - r.x), q.a + 1.0e-4, d);
        } else if (type == 5) {     // luminance range: q.x lo, q.y hi, q.z falloff
            float l = pow(max(luma(c), 0.0), 1.0 / 2.2);
            v = smoothstep(q.x - q.z, q.x, l) * (1.0 - smoothstep(q.y, q.y + q.z, l));
        }
        if (a.z > 0.5) v = 1.0 - v;
        int op = int(a.y + 0.5);
        if (op == 0) acc = acc + v - acc * v;
        else if (op == 1) acc = acc * (1.0 - v);
        else acc = acc * v;
    }
    if (h.z > 0.5) acc = 1.0 - acc;
    return clamp(acc * h.y, 0.0, 1.0);
}

void main() {
    vec2 p = uView.xy + vUv * uView.zw;
    vec3 g = srcUv(p);
    vec2 guv = clamp(g.xy, 0.0, 1.0);
    vec4 s = textureLod(uSrc, guv, uLod);
    vec3 c = s.rgb;
    // Lens profile: lateral chromatic aberration (red and blue sampled at their own radius) and vignetting
    vec2 sd = (guv - 0.5) * uSrcSize;
    if (uLensFlags.x > 0.5) {
        float rn = length(sd) / (0.5 * min(uSrcSize.x, uSrcSize.y));
        float sr = uTcaR.x + rn * (uTcaR.y + rn * uTcaR.z);
        float sb = uTcaB.x + rn * (uTcaB.y + rn * uTcaB.z);
        c.r = textureLod(uSrc, clamp(0.5 + (guv - 0.5) * sr, 0.0, 1.0), uLod).r;
        c.b = textureLod(uSrc, clamp(0.5 + (guv - 0.5) * sb, 0.0, 1.0), uLod).b;
    }
    if (uLensFlags.y > 0.5) {
        float rv = length(sd) / (0.5 * length(uSrcSize));
        float r2v = rv * rv;
        float corr = 1.0 + uLensVig.x * r2v + uLensVig.y * r2v * r2v + uLensVig.z * r2v * r2v * r2v;
        c /= max(corr, 0.12);
    }
    if (uOverlayOn > 0.5) {
        vec4 o = texture(uOverlay, clamp(g.xy, 0.0, 1.0));
        c = c * (1.0 - o.a) + o.rgb;
    }
    // Manual vignetting correction
    vec2 d = (p - 0.5) * vec2(uAspect, 1.0);
    c *= 1.0 / max(1.0 - uGeo2.w * dot(d, d) * 2.0, 0.2);

    vec2 lu = clamp(g.xy, 0.0, 1.0);
    vec2 ap = vec2(p);
    // Analysis layers are stored in output-image space.
    float bs = texture(uBs, p).x, bl = texture(uBl, p).x, bd = texture(uBd, p).y;
    c = adjust(c, 0, bs, bl, bd);
    vec2 pf = uCrop.xy + p * uCrop.zw;
    vec2 bdim = baseDims();
    float asp = bdim.x / bdim.y;
    for (int m = 0; m < 8; m++) {
        if (m >= uNumMasks) break;
        float a = maskAlpha(m, pf, asp, c);
        if (a > 0.001) {
            vec3 adj = adjust(c, 1 + m, bs, bl, bd);
            c = mix(c, adj, a);
        }
        if (m == uShowMask) c = mix(c, vec3(0.9, 0.05, 0.05) * max(luma(c), 0.02) * 4.0 + vec3(0.25, 0.0, 0.0), a * 0.55);
    }
    oColor = vec4(c, g.z);
}
