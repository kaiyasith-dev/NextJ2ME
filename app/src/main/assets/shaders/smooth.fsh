// Smooth upscale: crisp texel edges with only the borders smoothed (sharp bilinear), optionally
// blended with Catmull-Rom bicubic scaling for a sharper, smoother picture.
//   u_setting.x  softness of texel borders in screen pixels (1 = crispest)
//   u_setting.y  how much bicubic scaling is mixed in (0 = none, 1 = only bicubic)
//   u_setting.z  how far bicubic scaling may overshoot (0 = never, 1 = free)
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;  // the steps between samples are far smaller than a mediump texture coordinate
#else
precision mediump float;
#endif
uniform sampler2D sampler0;
uniform vec2 u_texelDelta;  // one texel of the game picture, in texture coordinates
uniform vec2 u_pixelDelta;  // one screen pixel, as a fraction of the screen
uniform vec2 u_uvPerClip;   // texture coordinates per unit of screen position (-1..1)
uniform vec4 u_setting;
varying vec2 v_texcoord0;

// the colour of the texel with this whole-number index (always its exact centre, so the result
// does not depend on the texture filter that is set)
vec3 T(vec2 i) {
    return texture2D(sampler0, (i + 0.5) * u_texelDelta).rgb;
}

// screen pixels per texel
vec2 texelScale() {
    return u_texelDelta / max(u_uvPerClip * (2.0 * u_pixelDelta), vec2(1e-6));
}

// Position inside a texel (0..1) -> how much of the next texel to mix in. The change happens
// over `softness` screen pixels at the border between two texels, so texels stay crisp and only
// their borders are smoothed. A softness at or above the scale is a plain bilinear blend.
vec2 sharpFraction(vec2 f, vec2 scale, float softness) {
    vec2 k = max(scale / max(softness, 1.0), vec2(1.0));
    return clamp((f - 0.5) * k + 0.5, 0.0, 1.0);
}

vec3 bilinear(vec2 i, vec2 f) {
    return mix(mix(T(i), T(i + vec2(1.0, 0.0)), f.x),
               mix(T(i + vec2(0.0, 1.0)), T(i + vec2(1.0, 1.0)), f.x), f.y);
}

vec4 cubicWeights(float t) {
    float t2 = t * t;
    float t3 = t2 * t;
    return vec4(-0.5 * t3 + t2 - 0.5 * t,
                1.5 * t3 - 2.5 * t2 + 1.0,
                -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                0.5 * t3 - 0.5 * t2);
}

vec3 cubicRow(vec2 i, float dy, vec4 w) {
    return T(i + vec2(-1.0, dy)) * w.x + T(i + vec2(0.0, dy)) * w.y
         + T(i + vec2(1.0, dy)) * w.z + T(i + vec2(2.0, dy)) * w.w;
}

void main() {
    vec2 pos = v_texcoord0 / u_texelDelta - 0.5;  // texel centres are whole numbers
    vec2 i = floor(pos);
    vec2 f = pos - i;
    vec3 sharp = bilinear(i, sharpFraction(f, texelScale(), u_setting.x));
    vec3 color = sharp;
    if (u_setting.y > 0.001) {
        vec4 wx = cubicWeights(f.x);
        vec4 wy = cubicWeights(f.y);
        vec3 cubic = cubicRow(i, -1.0, wx) * wy.x + cubicRow(i, 0.0, wx) * wy.y
                   + cubicRow(i, 1.0, wx) * wy.z + cubicRow(i, 2.0, wx) * wy.w;
        vec3 a = T(i);
        vec3 b = T(i + vec2(1.0, 0.0));
        vec3 c = T(i + vec2(0.0, 1.0));
        vec3 e = T(i + vec2(1.0, 1.0));
        vec3 lo = min(min(a, b), min(c, e));
        vec3 hi = max(max(a, b), max(c, e));
        cubic = mix(clamp(cubic, lo, hi), clamp(cubic, 0.0, 1.0), u_setting.z);
        color = mix(sharp, cubic, u_setting.y);
    }
    gl_FragColor = vec4(color, 1.0);
}
