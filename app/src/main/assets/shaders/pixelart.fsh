// Pixel art: rounds the corners of blocky pixels (the Scale2x / EPX rules) and then scales the
// result up with crisp, slightly smoothed borders. Made for 2D games with sprites.
//   u_setting.x  softness of the borders in screen pixels (1 = crispest)
//   u_setting.y  how much corner smoothing is applied (0 = none, 1 = full)
//   u_setting.z  how different two colours may be and still count as the same
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

bool same(vec3 a, vec3 b) {
    vec3 d = abs(a - b);
    return max(max(d.r, d.g), d.b) < u_setting.z;
}

// the colour of one pixel of the picture scaled to twice its size
vec3 doubled(vec2 v) {
    vec2 t = floor(v * 0.5);
    vec2 q = v - 2.0 * t;
    vec3 p = T(t);
    vec3 a = T(t + vec2(0.0, -1.0));
    vec3 b = T(t + vec2(1.0, 0.0));
    vec3 c = T(t + vec2(-1.0, 0.0));
    vec3 d = T(t + vec2(0.0, 1.0));
    if (q.y < 0.5) {
        if (q.x < 0.5) {
            if (same(c, a) && !same(c, d) && !same(a, b)) return a;
        } else {
            if (same(a, b) && !same(a, c) && !same(b, d)) return b;
        }
    } else {
        if (q.x < 0.5) {
            if (same(d, c) && !same(d, b) && !same(c, a)) return c;
        } else {
            if (same(b, d) && !same(b, a) && !same(d, c)) return d;
        }
    }
    return p;
}

void main() {
    vec2 scale = texelScale();
    vec2 pos = v_texcoord0 / u_texelDelta - 0.5;  // texel centres are whole numbers
    vec2 i = floor(pos);
    vec3 plain = bilinear(i, sharpFraction(pos - i, scale, u_setting.x));
    vec3 color = plain;
    if (u_setting.y > 0.001) {
        // the same spot on the doubled picture, whose pixel centres are whole numbers too
        vec2 pv = pos * 2.0 + 0.5;
        vec2 iv = floor(pv);
        vec2 fv = sharpFraction(pv - iv, scale * 0.5, u_setting.x);
        vec3 rounded = mix(mix(doubled(iv), doubled(iv + vec2(1.0, 0.0)), fv.x),
                           mix(doubled(iv + vec2(0.0, 1.0)), doubled(iv + vec2(1.0, 1.0)), fv.x), fv.y);
        color = mix(plain, rounded, u_setting.y);
    }
    gl_FragColor = vec4(color, 1.0);
}
