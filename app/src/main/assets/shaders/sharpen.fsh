// Sharpen: an unsharp mask worked out in screen pixels, so it counters the softness that
// scaling a small game picture up to the screen adds.
//   u_setting.x  strength (0 = off)
//   u_setting.y  radius in screen pixels
//   u_setting.z  how far a bright or dark edge may overshoot its neighbours (0 = never, 1 = free)
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;  // the steps between samples are far smaller than a mediump texture coordinate
#else
precision mediump float;
#endif
uniform sampler2D sampler0;
uniform vec2 u_pixelDelta;  // one screen pixel, as a fraction of the screen
uniform vec2 u_uvPerClip;   // texture coordinates per unit of screen position (-1..1)
uniform vec4 u_setting;
varying vec2 v_texcoord0;

void main() {
    vec2 d = u_uvPerClip * (2.0 * u_pixelDelta * u_setting.y);
    vec3 c = texture2D(sampler0, v_texcoord0).rgb;
    vec3 n = texture2D(sampler0, v_texcoord0 + vec2(0.0, -d.y)).rgb;
    vec3 s = texture2D(sampler0, v_texcoord0 + vec2(0.0, d.y)).rgb;
    vec3 e = texture2D(sampler0, v_texcoord0 + vec2(d.x, 0.0)).rgb;
    vec3 w = texture2D(sampler0, v_texcoord0 + vec2(-d.x, 0.0)).rgb;
    vec3 blur = (n + s + e + w) * 0.25;
    vec3 sharp = c + (c - blur) * u_setting.x;
    vec3 lo = min(min(min(n, s), min(e, w)), c);
    vec3 hi = max(max(max(n, s), max(e, w)), c);
    vec3 limited = clamp(sharp, lo, hi);
    gl_FragColor = vec4(mix(limited, clamp(sharp, 0.0, 1.0), u_setting.z), 1.0);
}
