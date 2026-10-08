// FXAA: smooths the jagged edges of 3D graphics by blurring along edges only. It works on the
// picture as it is on screen, so it is best with screen filtering switched on.
//   u_setting.x  the longest blur along an edge, in screen pixels
//   u_setting.y  how much of the smoothing is used (0 = none, 1 = full)
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

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    // texture coordinates for one screen pixel
    vec2 px = max(u_uvPerClip * (2.0 * u_pixelDelta), vec2(1e-6));
    vec3 rgbM = texture2D(sampler0, v_texcoord0).rgb;
    vec3 rgbNW = texture2D(sampler0, v_texcoord0 + vec2(-1.0, -1.0) * px).rgb;
    vec3 rgbNE = texture2D(sampler0, v_texcoord0 + vec2(1.0, -1.0) * px).rgb;
    vec3 rgbSW = texture2D(sampler0, v_texcoord0 + vec2(-1.0, 1.0) * px).rgb;
    vec3 rgbSE = texture2D(sampler0, v_texcoord0 + vec2(1.0, 1.0) * px).rgb;
    float lumaNW = luma(rgbNW);
    float lumaNE = luma(rgbNE);
    float lumaSW = luma(rgbSW);
    float lumaSE = luma(rgbSE);
    float lumaM = luma(rgbM);
    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 dir = vec2(-((lumaNW + lumaNE) - (lumaSW + lumaSE)),
                    (lumaNW + lumaSW) - (lumaNE + lumaSE));
    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 / 8.0), 1.0 / 128.0);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    float span = max(u_setting.x, 1.0);
    dir = clamp(dir * rcpDirMin, vec2(-span), vec2(span)) * px;

    vec3 rgbA = 0.5 * (texture2D(sampler0, v_texcoord0 + dir * (1.0 / 3.0 - 0.5)).rgb
                     + texture2D(sampler0, v_texcoord0 + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 rgbB = rgbA * 0.5 + 0.25 * (texture2D(sampler0, v_texcoord0 - dir * 0.5).rgb
                                   + texture2D(sampler0, v_texcoord0 + dir * 0.5).rgb);
    float lumaB = luma(rgbB);
    vec3 smoothed = (lumaB < lumaMin || lumaB > lumaMax) ? rgbA : rgbB;
    gl_FragColor = vec4(mix(rgbM, smoothed, clamp(u_setting.y, 0.0, 1.0)), 1.0);
}
