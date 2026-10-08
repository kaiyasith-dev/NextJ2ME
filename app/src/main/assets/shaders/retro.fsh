// Retro screen: scan lines and a pixel grid between the game's pixels, with colour tweaks.
// The lines only show when the picture is scaled up a few times (they fade out below that).
//   u_setting.x  scan lines (0 = none, 1 = strong)
//   u_setting.y  pixel grid (0 = none, 1 = strong)
//   u_setting.z  colour saturation (1 = unchanged)
//   u_setting.w  gamma (1 = unchanged, higher = brighter mid tones)
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

void main() {
    // screen pixels per texel
    vec2 scale = u_texelDelta / max(u_uvPerClip * (2.0 * u_pixelDelta), vec2(1e-6));
    vec2 pos = v_texcoord0 / u_texelDelta - 0.5;  // texel centres are whole numbers
    vec2 i = floor(pos);
    vec2 f = pos - i;
    // texels stay crisp, only their borders are smoothed over one screen pixel
    vec2 k = max(scale, vec2(1.0));
    vec2 s = clamp((f - 0.5) * k + 0.5, 0.0, 1.0);
    vec3 color = mix(mix(T(i), T(i + vec2(1.0, 0.0)), s.x),
                     mix(T(i + vec2(0.0, 1.0)), T(i + vec2(1.0, 1.0)), s.x), s.y);

    // the position inside the texel this screen pixel is on: 0 at its centre, 1 at its border
    vec2 inTexel = abs(fract(v_texcoord0 / u_texelDelta) - 0.5) * 2.0;
    float fade = clamp((min(scale.x, scale.y) - 1.5) / 2.5, 0.0, 1.0);
    float scan = u_setting.x * inTexel.y * inTexel.y;
    float edge = max(smoothstep(0.7, 1.0, inTexel.x), smoothstep(0.7, 1.0, inTexel.y));
    float mask = (1.0 - scan) * (1.0 - u_setting.y * edge);
    color *= mix(1.0, mask, fade);

    float l = dot(color, vec3(0.299, 0.587, 0.114));
    color = clamp(mix(vec3(l), color, u_setting.z), 0.0, 1.0);
    color = pow(color, vec3(1.0 / max(u_setting.w, 0.1)));
    gl_FragColor = vec4(color, 1.0);
}
