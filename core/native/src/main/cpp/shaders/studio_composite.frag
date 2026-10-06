#version 300 es
// Studio compositor: one layer over the backdrop (spec 2.2, W3C Compositing and Blending Level 1, straight alpha, blend space as
// stored: the layer textures hold the values the document is in, GAMMA by default). Mirrors studio_ref.py and ReferenceCompositor.kt.
precision highp float;
precision highp int;
precision highp sampler2D;
in vec2 vUv;
out vec4 oColor;
uniform sampler2D uBackdrop;   // RGBA16F, straight alpha, output sized (not read when uMode is -2)
uniform sampler2D uLayer;      // RGBA8, straight alpha, layer sized
uniform sampler2D uStroke;     // R16F coverage of the stroke being drawn on this layer (unit 2), layer sized
uniform sampler2D uMask;       // R8 layer mask (unit 3), layer sized, 255 = reveal. Bound only when uMaskMode != 0
uniform int uMaskMode;         // 0 no mask (or a disabled one), 1 mask, 2 inverted mask
uniform sampler2D uSel;        // R8 selection (unit 4), canvas sized. Bound only while a stroke is live and a selection exists
uniform int uSelOn;            // 1 = the live stroke is multiplied by the selection (nearest texel of the layer pixel's document position)
uniform int uStrokeMode;       // 0 none, 1 paint, 2 erase, 3 paint the mask toward uStrokeMaskValue
uniform vec3 uStrokeColor;     // straight colour of the stroke
uniform float uStrokeOpacity;  // the stroke's opacity ceiling
uniform float uStrokeMaskValue; // 0..1, the value a mask stroke paints toward (black hides, white reveals)
uniform ivec2 uLayerSize;
uniform vec4 uRect;            // layer top left x, y and the w, h it covers, all in document pixels
uniform vec3 uView;            // top left x, y of the view in document pixels, zoom (screen pixels per document pixel)
uniform float uOpacity;
uniform int uMode;             // 0 normal, 1 multiply, 2 screen; -1 copy the backdrop (final resolve); -2 clear

// Selection value (0..1) under layer texel c: the canvas pixel its centre falls in. Outside the canvas counts as unselected.
float selectionAt(ivec2 c) {
    if (uSelOn == 0) return 1.0;
    vec2 d = uRect.xy + (vec2(c) + 0.5) * (uRect.zw / vec2(uLayerSize));
    ivec2 s = ivec2(floor(d));
    ivec2 sz = textureSize(uSel, 0);
    if (s.x < 0 || s.y < 0 || s.x >= sz.x || s.y >= sz.y) return 0.0;
    return texelFetch(uSel, s, 0).r;
}

// One mask texel with the live mask stroke applied (what the commit will bake): m + (target - m) * coverage * opacity * selection.
float maskTexel(ivec2 c) {
    float m = texelFetch(uMask, c, 0).r;
    if (uStrokeMode != 3) return m;
    float a = min(texelFetch(uStroke, c, 0).r, 1.0) * uStrokeOpacity * selectionAt(c);
    return m + (uStrokeMaskValue - m) * a;
}

// One layer texel with the live stroke applied (what the commit will bake): paint is 'normal over' of the colour at coverage * opacity * selection, erase scales alpha.
vec4 fetchTexel(ivec2 c) {
    vec4 t = texelFetch(uLayer, c, 0);
    if (uStrokeMode == 0 || uStrokeMode == 3) return t;
    float a = min(texelFetch(uStroke, c, 0).r, 1.0) * uStrokeOpacity * selectionAt(c);
    if (a <= 0.0) return t;
    if (uStrokeMode == 2) return vec4(t.rgb, t.a * (1.0 - a));
    float ar = a + t.a * (1.0 - a);
    return vec4(((1.0 - a) * t.a * t.rgb + a * uStrokeColor) / ar, ar);
}

// Bilinear on alpha weighted straight colour in pixel centres, clamped to the layer edge, alpha 0 outside the rectangle.
vec4 sampleLayer(vec2 d) {
    vec2 l = (d - uRect.xy) / uRect.zw * vec2(uLayerSize);
    if (l.x < 0.0 || l.y < 0.0 || l.x >= float(uLayerSize.x) || l.y >= float(uLayerSize.y)) return vec4(0.0);
    vec2 u = l - 0.5;
    vec2 fl = floor(u);
    vec2 f = u - fl;
    ivec2 i0 = ivec2(fl);
    ivec2 hi = uLayerSize - 1;
    vec3 acc = vec3(0.0);
    float asum = 0.0;
    float msum = 0.0;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            ivec2 c = clamp(i0 + ivec2(i, j), ivec2(0), hi);
            vec4 t = fetchTexel(c);
            float wb = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
            float w = wb * t.a;
            acc += t.rgb * w;
            asum += w;
            if (uMaskMode != 0) msum += wb * maskTexel(c);
        }
    }
    // the layer mask scales the resampled alpha (spec 2.1: alpha' = alpha * opacity * m); the mask is bilinear in the layer's own pixels, so it moves and scales with the layer
    // (weights that add to 0.99999994 would make an all white mask differ from no mask by a rounding step, so a sum within 1e-5 of 1 is taken as 1)
    float am = asum;
    if (uMaskMode != 0) { if (msum > 0.99999) msum = 1.0; am = asum * (uMaskMode == 2 ? 1.0 - msum : msum); }
    return asum <= 0.0 ? vec4(0.0) : vec4(acc / asum, am);
}

vec3 blendFn(int mode, vec3 cb, vec3 cs) {
    if (mode == 1) return cb * cs;
    if (mode == 2) return cb + cs - cb * cs;
    return cs;
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);                  // output pixel; row 0 is the image top (the vertex shader draws with uFlipY = 0)
    if (uMode == -2) { oColor = vec4(0.0); return; }
    vec4 back = texelFetch(uBackdrop, px, 0);
    if (uMode == -1) { oColor = back; return; }
    vec2 d = uView.xy + (vec2(px) + 0.5) / uView.z;      // document position of this output pixel's centre
    vec4 s = sampleLayer(d);
    float a = s.a * uOpacity;
    if (a <= 0.0) { oColor = back; return; }
    float ab = back.a;
    float ar = a + ab * (1.0 - a);
    vec3 cr = ((1.0 - a) * ab * back.rgb + a * ((1.0 - ab) * s.rgb + ab * blendFn(uMode, back.rgb, s.rgb))) / ar;
    oColor = vec4(cr, ar);
}
