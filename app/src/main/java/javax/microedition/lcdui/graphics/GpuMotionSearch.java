/*
 * Copyright 2026 ksdevla
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.lcdui.graphics;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_DITHER;
import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_COMPILE_STATUS;
import static android.opengl.GLES20.GL_FLOAT;
import static android.opengl.GLES20.GL_FRAGMENT_SHADER;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_FRAMEBUFFER_COMPLETE;
import static android.opengl.GLES20.GL_LINK_STATUS;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_NO_ERROR;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_TEXTURE0;
import static android.opengl.GLES20.GL_TEXTURE1;
import static android.opengl.GLES20.GL_TEXTURE2;
import static android.opengl.GLES20.GL_TEXTURE3;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.GL_TEXTURE_MAG_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_MIN_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_S;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_T;
import static android.opengl.GLES20.GL_TRIANGLE_STRIP;
import static android.opengl.GLES20.GL_UNSIGNED_BYTE;
import static android.opengl.GLES20.GL_VERTEX_SHADER;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glAttachShader;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glCheckFramebufferStatus;
import static android.opengl.GLES20.glCompileShader;
import static android.opengl.GLES20.glCreateProgram;
import static android.opengl.GLES20.glCreateShader;
import static android.opengl.GLES20.glDeleteFramebuffers;
import static android.opengl.GLES20.glDeleteProgram;
import static android.opengl.GLES20.glDeleteShader;
import static android.opengl.GLES20.glDeleteTextures;
import static android.opengl.GLES20.glDisable;
import static android.opengl.GLES20.glDisableVertexAttribArray;
import static android.opengl.GLES20.glDrawArrays;
import static android.opengl.GLES20.glEnable;
import static android.opengl.GLES20.glEnableVertexAttribArray;
import static android.opengl.GLES20.glFramebufferTexture2D;
import static android.opengl.GLES20.glGenFramebuffers;
import static android.opengl.GLES20.glGenTextures;
import static android.opengl.GLES20.glGetAttribLocation;
import static android.opengl.GLES20.glGetError;
import static android.opengl.GLES20.glGetProgramInfoLog;
import static android.opengl.GLES20.glGetProgramiv;
import static android.opengl.GLES20.glGetShaderInfoLog;
import static android.opengl.GLES20.glGetShaderiv;
import static android.opengl.GLES20.glGetUniformLocation;
import static android.opengl.GLES20.glIsEnabled;
import static android.opengl.GLES20.glLinkProgram;
import static android.opengl.GLES20.glReadPixels;
import static android.opengl.GLES20.glShaderSource;
import static android.opengl.GLES20.glTexImage2D;
import static android.opengl.GLES20.glTexParameteri;
import static android.opengl.GLES20.glUniform1f;
import static android.opengl.GLES20.glUniform1i;
import static android.opengl.GLES20.glUniform2f;
import static android.opengl.GLES20.glUseProgram;
import static android.opengl.GLES20.glVertexAttribPointer;
import static android.opengl.GLES20.glViewport;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The motion search of frame generation done by the GPU, with OpenGL ES 2.0 shaders: the same
 * steps as {@link FineMotionEstimator} (a coarse search on quarter-size frames, then a refinement of
 * 8x8 blocks of the full-size frames to a quarter pixel, then a 3x3 median), each block worked out
 * by one fragment. The result stays on the GPU, in a texture the interpolation shader reads like
 * the CPU's motion texture: the motion plus 128 in the red and alpha channels, in quarter pixels.
 * Only the mean match error (one pixel) comes back, to tell a scene cut.
 * <p>
 * All methods must be called on the GL thread.
 */
final class GpuMotionSearch {
	private static final String TAG = GpuMotionSearch.class.getName();
	/** Side of a refined block, in game pixels; vectors are in quarter pixels. */
	static final int BLOCK = 8;
	static final int UNITS_PER_PIXEL = 4;
	/** Mean difference per pixel (0..255) above which two frames are treated as a scene cut. */
	private static final float SCENE_CUT_ERROR = MotionEstimator.SCENE_CUT_ERROR;

	private static final String PRECISION =
			"#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
					+ "precision highp float;\n"
					+ "#else\n"
					+ "precision mediump float;\n"
					+ "#endif\n";

	private static final String VERTEX =
			"attribute vec4 a_position;\n"
					+ "attribute vec2 a_uv;\n"
					+ "varying vec2 v_uv;\n"
					+ "void main() {\n"
					+ "    gl_Position = a_position;\n"
					+ "    v_uv = a_uv;\n"
					+ "}\n";

	/** A frame at a quarter of its size in grey: every 4x4 pixels averaged. */
	private static final String QUARTER = PRECISION
			+ "uniform sampler2D u_src;\n"
			+ "uniform vec2 u_srcSize;\n"
			+ "uniform vec2 u_size;\n"
			+ "varying vec2 v_uv;\n"
			+ "void main() {\n"
			+ "    vec2 base = floor(v_uv * u_size) * 4.0;\n"
			+ "    float sum = 0.0;\n"
			+ "    for (int y = 0; y < 4; y++) {\n"
			+ "        for (int x = 0; x < 4; x++) {\n"
			+ "            vec3 c = texture2D(u_src, (base + vec2(float(x), float(y)) + 0.5) / u_srcSize).rgb;\n"
			+ "            sum += dot(c, vec3(0.299, 0.587, 0.114));\n"
			+ "        }\n"
			+ "    }\n"
			+ "    float g = sum / 16.0;\n"
			+ "    gl_FragColor = vec4(g, g, g, 1.0);\n"
			+ "}\n";

	/**
	 * Coarse search on the quarter-size frames, one 4x4 block per fragment, up to 8 pixels each way
	 * (32 game pixels): like {@link MotionEstimator}, a move must beat staying put by 10% and costs
	 * a little per pixel moved. Output: motion + 128 in r and a (quarter-size pixels), the mean
	 * error per pixel in g.
	 */
	private static final String COARSE = PRECISION
			+ "uniform sampler2D u_prev;\n"
			+ "uniform sampler2D u_curr;\n"
			+ "uniform vec2 u_size;\n"
			+ "uniform vec2 u_grid;\n"
			+ "varying vec2 v_uv;\n"
			+ "float px(sampler2D t, vec2 p) {\n"
			+ "    return texture2D(t, (p + 0.5) / u_size).r;\n"
			+ "}\n"
			+ "float sad(vec2 o, vec2 n, vec2 d) {\n"
			+ "    float s = 0.0;\n"
			+ "    for (int y = 0; y < 4; y++) {\n"
			+ "        for (int x = 0; x < 4; x++) {\n"
			+ "            vec2 p = o + vec2(float(x), float(y));\n"
			+ "            if (p.x < u_size.x && p.y < u_size.y) {\n"
			+ "                s += abs(px(u_curr, p) - px(u_prev, p + d));\n"
			+ "            }\n"
			+ "        }\n"
			+ "    }\n"
			+ "    return s;\n"
			+ "}\n"
			+ "void main() {\n"
			+ "    vec2 cell = floor(v_uv * u_grid);\n"
			+ "    vec2 o = cell * 4.0;\n"
			+ "    vec2 e = min(o + 4.0, u_size);\n"
			+ "    vec2 n = e - o;\n"
			+ "    float zero = sad(o, n, vec2(0.0));\n"
			+ "    float best = zero;\n"
			+ "    float bestCost = zero;\n"
			+ "    vec2 bestD = vec2(0.0);\n"
			+ "    if (zero > 0.0) {\n"
			+ "        float limit = zero * 0.9;\n"
			+ "        for (int dy = -8; dy <= 8; dy++) {\n"
			+ "            for (int dx = -8; dx <= 8; dx++) {\n"
			+ "                vec2 d = vec2(float(dx), float(dy));\n"
			+ "                if (dx == 0 && dy == 0) continue;\n"
			+ "                if (o.x + d.x < 0.0 || o.y + d.y < 0.0 || e.x + d.x > u_size.x || e.y + d.y > u_size.y) continue;\n"
			+ "                float penalty = (4.0 / 255.0) * (abs(d.x) + abs(d.y));\n"
			+ "                float s = sad(o, n, d);\n"
			+ "                if (s + penalty < min(bestCost, limit)) {\n"
			+ "                    bestCost = s + penalty;\n"
			+ "                    best = s;\n"
			+ "                    bestD = d;\n"
			+ "                }\n"
			+ "            }\n"
			+ "        }\n"
			+ "    }\n"
			+ "    vec2 v = -bestD;\n"
			+ "    gl_FragColor = vec4((v.x + 128.0) / 255.0, best / (n.x * n.y), 0.0, (v.y + 128.0) / 255.0);\n"
			+ "}\n";

	/**
	 * Refinement on the full-size frames, one 8x8 block per fragment: the coarse motion of the block's
	 * place and of the four places around it, staying put, and (with {@code u_hasPrevious}) the motion
	 * the block had in the previous frame, each refined by a pixel each way (keeping the previous
	 * motion only has to fit as well as staying put, not 10% better), then
	 * to a quarter pixel with a parabola through the neighbouring costs. Output: motion * 4 + 128 in r
	 * and a, the mean error per pixel in g.
	 */
	private static final String REFINE = PRECISION
			+ "uniform sampler2D u_prev;\n"
			+ "uniform sampler2D u_curr;\n"
			+ "uniform sampler2D u_coarse;\n"
			+ "uniform sampler2D u_previous;\n"
			+ "uniform float u_hasPrevious;\n"
			+ "uniform vec2 u_size;\n"
			+ "uniform vec2 u_grid;\n"
			+ "uniform vec2 u_coarseGrid;\n"
			+ "varying vec2 v_uv;\n"
			+ "float luma(sampler2D t, vec2 p) {\n"
			+ "    return dot(texture2D(t, (p + 0.5) / u_size).rgb, vec3(0.299, 0.587, 0.114));\n"
			+ "}\n"
			+ "vec2 g_o;\n"
			+ "vec2 g_e;\n"
			+ "float sad(vec2 d) {\n"
			+ "    if (g_o.x + d.x < 0.0 || g_o.y + d.y < 0.0 || g_e.x + d.x > u_size.x || g_e.y + d.y > u_size.y) return 1e6;\n"
			+ "    float s = 0.0;\n"
			+ "    for (int y = 0; y < 8; y++) {\n"
			+ "        for (int x = 0; x < 8; x++) {\n"
			+ "            vec2 p = g_o + vec2(float(x), float(y));\n"
			+ "            if (p.x < u_size.x && p.y < u_size.y) {\n"
			+ "                s += abs(luma(u_curr, p) - luma(u_prev, p + d));\n"
			+ "            }\n"
			+ "        }\n"
			+ "    }\n"
			+ "    return s;\n"
			+ "}\n"
			+ "vec2 guess(vec2 cell) {\n"
			+ "    vec2 c = clamp(cell, vec2(0.0), u_coarseGrid - 1.0);\n"
			+ "    vec4 m = texture2D(u_coarse, (c + 0.5) / u_coarseGrid);\n"
			+ "    return -floor(vec2(m.r, m.a) * 255.0 - 128.0 + 0.5) * 4.0;\n"
			+ "}\n"
			+ "vec2 previousGuess(vec2 cell) {\n"
			+ "    vec4 m = texture2D(u_previous, (cell + 0.5) / u_grid);\n"
			+ "    return -floor((vec2(m.r, m.a) * 255.0 - 128.0) / 4.0 + 0.5);\n"
			+ "}\n"
			+ "float g_bestCost;\n"
			+ "float g_best;\n"
			+ "vec2 g_bestD;\n"
			+ "void refine(vec2 c) {\n"
			+ "    for (int dy = -1; dy <= 1; dy++) {\n"
			+ "        for (int dx = -1; dx <= 1; dx++) {\n"
			+ "            vec2 d = c + vec2(float(dx), float(dy));\n"
			+ "            float penalty = (2.0 / 255.0) * (abs(float(dx)) + abs(float(dy)));\n"
			+ "            if (penalty >= g_bestCost) continue;\n"
			+ "            float s = sad(d);\n"
			+ "            if (s + penalty < g_bestCost) {\n"
			+ "                g_bestCost = s + penalty;\n"
			+ "                g_best = s;\n"
			+ "                g_bestD = d;\n"
			+ "            }\n"
			+ "        }\n"
			+ "    }\n"
			+ "}\n"
			+ "float subPixel(vec2 d, float s0, vec2 u) {\n"
			+ "    float sa = sad(d - u);\n"
			+ "    float sb = sad(d + u);\n"
			+ "    if (sa >= 1e5 || sb >= 1e5) return 0.0;\n"
			+ "    float curve = sa - 2.0 * s0 + sb;\n"
			+ "    if (curve <= 0.0) return 0.0;\n"
			+ "    return clamp((sa - sb) / (2.0 * curve), -0.5, 0.5);\n"
			+ "}\n"
			+ "void main() {\n"
			+ "    vec2 cell = floor(v_uv * u_grid);\n"
			+ "    g_o = cell * 8.0;\n"
			+ "    g_e = min(g_o + 8.0, u_size);\n"
			+ "    vec2 n = g_e - g_o;\n"
			+ "    float zero = sad(vec2(0.0));\n"
			+ "    vec2 v = vec2(0.0);\n"
			+ "    float err = zero;\n"
			+ "    if (zero > 0.0) {\n"
			+ "        g_bestCost = 1e6;\n"
			+ "        g_best = zero;\n"
			+ "        g_bestD = vec2(0.0);\n"
			+ "        vec2 cc = floor((g_o + 4.0) / 16.0);\n"
			+ "        refine(vec2(0.0));\n"
			+ "        vec2 pg = vec2(0.0);\n"
			+ "        bool steady = false;\n"
			+ "        if (u_hasPrevious > 0.5) {\n"
			+ "            pg = previousGuess(cell);\n"
			+ "            steady = pg.x != 0.0 || pg.y != 0.0;\n"
			+ "            if (steady) refine(pg);\n"
			+ "        }\n"
			+ "        refine(guess(cc));\n"
			+ "        refine(guess(cc + vec2(-1.0, 0.0)));\n"
			+ "        refine(guess(cc + vec2(1.0, 0.0)));\n"
			+ "        refine(guess(cc + vec2(0.0, -1.0)));\n"
			+ "        refine(guess(cc + vec2(0.0, 1.0)));\n"
			+ "        bool moved = g_bestD.x != 0.0 || g_bestD.y != 0.0;\n"
			+ "        bool kept = steady && abs(g_bestD.x - pg.x) <= 1.0 && abs(g_bestD.y - pg.y) <= 1.0;\n"
			+ "        if (!moved || g_best <= zero * 0.9 || (kept && g_best <= zero)) {\n"
			+ "            float fx = subPixel(g_bestD, g_best, vec2(1.0, 0.0));\n"
			+ "            float fy = subPixel(g_bestD, g_best, vec2(0.0, 1.0));\n"
			+ "            v = -floor((g_bestD + vec2(fx, fy)) * 4.0 + 0.5);\n"
			+ "            err = g_best;\n"
			+ "        }\n"
			+ "    }\n"
			+ "    v = clamp(v, -127.0, 127.0);\n"
			+ "    gl_FragColor = vec4((v.x + 128.0) / 255.0, err / (n.x * n.y), 0.0, (v.y + 128.0) / 255.0);\n"
			+ "}\n";

	/** 3x3 median of the motion (r and a), so a single wrong block does not tear the picture. */
	private static final String MEDIAN = PRECISION
			+ "uniform sampler2D u_src;\n"
			+ "uniform vec2 u_grid;\n"
			+ "varying vec2 v_uv;\n"
			+ "#define s2(a, b) t = a; a = min(a, b); b = max(t, b);\n"
			+ "#define mn3(a, b, c) s2(a, b); s2(a, c);\n"
			+ "#define mx3(a, b, c) s2(b, c); s2(a, c);\n"
			+ "#define mnmx3(a, b, c) mx3(a, b, c); s2(a, b);\n"
			+ "#define mnmx4(a, b, c, d) s2(a, b); s2(c, d); s2(a, c); s2(b, d);\n"
			+ "#define mnmx5(a, b, c, d, e) s2(a, b); s2(c, d); mn3(a, c, e); mx3(b, d, e);\n"
			+ "#define mnmx6(a, b, c, d, e, f) s2(a, d); s2(b, e); s2(c, f); mn3(a, b, c); mx3(d, e, f);\n"
			+ "vec2 at(vec2 c) {\n"
			+ "    vec4 m = texture2D(u_src, (clamp(c, vec2(0.0), u_grid - 1.0) + 0.5) / u_grid);\n"
			+ "    return vec2(m.r, m.a);\n"
			+ "}\n"
			+ "void main() {\n"
			+ "    vec2 c = floor(v_uv * u_grid);\n"
			+ "    vec2 t;\n"
			+ "    vec2 v0 = at(c + vec2(-1.0, -1.0));\n"
			+ "    vec2 v1 = at(c + vec2(0.0, -1.0));\n"
			+ "    vec2 v2 = at(c + vec2(1.0, -1.0));\n"
			+ "    vec2 v3 = at(c + vec2(-1.0, 0.0));\n"
			+ "    vec2 v4 = at(c);\n"
			+ "    vec2 v5 = at(c + vec2(1.0, 0.0));\n"
			+ "    vec2 v6 = at(c + vec2(-1.0, 1.0));\n"
			+ "    vec2 v7 = at(c + vec2(0.0, 1.0));\n"
			+ "    vec2 v8 = at(c + vec2(1.0, 1.0));\n"
			+ "    float g = texture2D(u_src, (c + 0.5) / u_grid).g;\n"
			+ "    mnmx6(v0, v1, v2, v3, v4, v5);\n"
			+ "    mnmx5(v1, v2, v3, v4, v6);\n"
			+ "    mnmx4(v2, v3, v4, v7);\n"
			+ "    mnmx3(v3, v4, v8);\n"
			+ "    gl_FragColor = vec4(v4.x, g, 0.0, v4.y);\n"
			+ "}\n";

	/** The mean of the coarse errors (g), in one pixel; at most 64x64 blocks are looked at. */
	private static final String ERROR = PRECISION
			+ "uniform sampler2D u_src;\n"
			+ "uniform vec2 u_grid;\n"
			+ "varying vec2 v_uv;\n"
			+ "void main() {\n"
			+ "    vec2 step = max(ceil(u_grid / 64.0), vec2(1.0));\n"
			+ "    float sum = 0.0;\n"
			+ "    float n = 0.0;\n"
			+ "    for (int y = 0; y < 64; y++) {\n"
			+ "        for (int x = 0; x < 64; x++) {\n"
			+ "            vec2 c = vec2(float(x), float(y)) * step;\n"
			+ "            if (c.x < u_grid.x && c.y < u_grid.y) {\n"
			+ "                sum += texture2D(u_src, (c + 0.5) / u_grid).g;\n"
			+ "                n += 1.0;\n"
			+ "            }\n"
			+ "        }\n"
			+ "    }\n"
			+ "    float m = sum / max(n, 1.0);\n"
			+ "    gl_FragColor = vec4(m, m, m, 1.0);\n"
			+ "}\n";

	/** A compiled pass: the program and where its inputs go. */
	private static final class Pass {
		final int id;
		final int aPosition;
		final int aUv;

		Pass(int id) {
			this.id = id;
			aPosition = glGetAttribLocation(id, "a_position");
			aUv = glGetAttribLocation(id, "a_uv");
		}

		int uniform(String name) {
			return glGetUniformLocation(id, name);
		}
	}

	private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
			.order(ByteOrder.nativeOrder()).asFloatBuffer();
	private final int width;
	private final int height;
	private final int quarterWidth;
	private final int quarterHeight;
	private final int coarseCols;
	private final int coarseRows;
	private final int cols;
	private final int rows;
	private final int[] quarterTex = new int[FramePacer.KEPT];
	private int coarseTex;
	private int coarseMedianTex;
	private int refinedTex;
	/** Two textures in turn: the newest motion, and the one before it (the guess for the next). */
	private final int[] forwardTex = new int[2];
	private int current;
	private int backwardTex;
	private int errorTex;
	private int fbo;
	private Pass quarter;
	private Pass coarse;
	private Pass refine;
	private Pass median;
	private Pass error;
	private final ByteBuffer pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());

	private GpuMotionSearch(int width, int height) {
		this.width = width;
		this.height = height;
		this.quarterWidth = width / 4;
		this.quarterHeight = height / 4;
		this.coarseCols = (quarterWidth + 3) / 4;
		this.coarseRows = (quarterHeight + 3) / 4;
		this.cols = (width + BLOCK - 1) / BLOCK;
		this.rows = (height + BLOCK - 1) / BLOCK;
		quad.put(new float[]{
				-1f, -1f, 0f, 0f,
				-1f, 1f, 0f, 1f,
				1f, -1f, 1f, 0f,
				1f, 1f, 1f, 1f});
		quad.rewind();
	}

	/**
	 * Builds the GPU search for frames of this size.
	 *
	 * @return null if the size is too small or the GPU can not run it (the CPU search is used then)
	 */
	static GpuMotionSearch create(int width, int height) {
		if (width < 4 * BLOCK || height < 4 * BLOCK) {
			return null;
		}
		while (glGetError() != GL_NO_ERROR) {
			// an error somebody else left must not be blamed on this
		}
		GpuMotionSearch s = new GpuMotionSearch(width, height);
		if (!s.build() || glGetError() != GL_NO_ERROR) {
			s.release();
			while (glGetError() != GL_NO_ERROR) {
				// none may be left behind
			}
			return null;
		}
		return s;
	}

	private boolean build() {
		quarter = link(QUARTER);
		coarse = link(COARSE);
		refine = link(REFINE);
		median = link(MEDIAN);
		error = link(ERROR);
		if (quarter == null || coarse == null || refine == null || median == null || error == null) {
			return false;
		}
		int[] ids = new int[FramePacer.KEPT + 7];
		glGenTextures(ids.length, ids, 0);
		for (int i = 0; i < FramePacer.KEPT; i++) {
			quarterTex[i] = texture(ids[i], quarterWidth, quarterHeight);
		}
		coarseTex = texture(ids[3], coarseCols, coarseRows);
		coarseMedianTex = texture(ids[4], coarseCols, coarseRows);
		refinedTex = texture(ids[5], cols, rows);
		forwardTex[0] = texture(ids[6], cols, rows);
		forwardTex[1] = texture(ids[7], cols, rows);
		backwardTex = texture(ids[8], cols, rows);
		errorTex = texture(ids[9], 1, 1);
		int[] fb = new int[1];
		glGenFramebuffers(1, fb, 0);
		fbo = fb[0];
		// every target must be usable as a framebuffer
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		boolean ok = true;
		for (int tex : new int[]{quarterTex[0], coarseTex, refinedTex, errorTex}) {
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
			ok &= glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		if (!ok) {
			Log.w(TAG, "the GPU can not draw into the motion textures");
		}
		return ok;
	}

	private static int texture(int id, int w, int h) {
		glBindTexture(GL_TEXTURE_2D, id);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);
		glBindTexture(GL_TEXTURE_2D, 0);
		return id;
	}

	private static Pass link(String fragment) {
		int v = compile(GL_VERTEX_SHADER, VERTEX);
		int f = compile(GL_FRAGMENT_SHADER, fragment);
		if (v == 0 || f == 0) {
			if (v != 0) glDeleteShader(v);
			if (f != 0) glDeleteShader(f);
			return null;
		}
		int program = glCreateProgram();
		glAttachShader(program, v);
		glAttachShader(program, f);
		glLinkProgram(program);
		glDeleteShader(v);
		glDeleteShader(f);
		int[] status = new int[1];
		glGetProgramiv(program, GL_LINK_STATUS, status, 0);
		if (status[0] == 0) {
			Log.w(TAG, "link failed: " + glGetProgramInfoLog(program));
			glDeleteProgram(program);
			return null;
		}
		return new Pass(program);
	}

	private static int compile(int type, String source) {
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		int[] status = new int[1];
		glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0) {
			Log.w(TAG, "compile failed: " + glGetShaderInfoLog(shader));
			glDeleteShader(shader);
			return 0;
		}
		return shader;
	}

	int cols() {
		return cols;
	}

	int rows() {
		return rows;
	}

	/** The motion from the previous to the newest frame, as of the last {@link #search}. */
	int forwardTexture() {
		return forwardTex[current];
	}

	/** The motion from the newest to the previous frame (only after a search both ways). */
	int backwardTexture() {
		return backwardTex;
	}

	/** Makes the quarter-size grey copy of a newly arrived frame, kept in {@code slot}. */
	void addFrame(int slot, int frameTexture) {
		boolean dither = noDither();
		begin(quarter, quarterTex[slot], quarterWidth, quarterHeight);
		bind(0, frameTexture, quarter.uniform("u_src"));
		glUniform2f(quarter.uniform("u_srcSize"), width, height);
		glUniform2f(quarter.uniform("u_size"), quarterWidth, quarterHeight);
		end(quarter);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		restoreDither(dither);
	}

	/** Dithering may change the low bits written, which hold the motion: off during the passes. */
	private static boolean noDither() {
		boolean on = glIsEnabled(GL_DITHER);
		glDisable(GL_DITHER);
		return on;
	}

	private static void restoreDither(boolean on) {
		if (on) {
			glEnable(GL_DITHER);
		}
	}

	/**
	 * Searches the motion from the frame in {@code prevSlot} to the one in {@code currSlot} (both
	 * added with {@link #addFrame}), into {@link #forwardTexture()}; with {@code bothWays} also back
	 * into {@link #backwardTexture()}.
	 *
	 * @param usePrevious offer the motion of the last search as a guess (when it was into the
	 *                    previous frame: steady motion)
	 * @return whether the two frames have nothing in common (a scene cut: do not interpolate)
	 */
	boolean search(int prevSlot, int prevTexture, int currSlot, int currTexture, boolean bothWays,
				   boolean usePrevious) {
		boolean dither = noDither();
		int previous = usePrevious ? forwardTex[current] : 0;
		boolean cut = searchOneWay(prevSlot, prevTexture, currSlot, currTexture, forwardTex[1 - current],
				true, previous);
		if (!cut) {
			current = 1 - current;
		}
		if (bothWays && !cut) {
			searchOneWay(currSlot, currTexture, prevSlot, prevTexture, backwardTex, false, 0);
		}
		glActiveTexture(GL_TEXTURE0);
		restoreDither(dither);
		return cut;
	}

	private boolean searchOneWay(int fromSlot, int fromTexture, int toSlot, int toTexture, int target,
								 boolean measure, int previous) {
		// coarse search on the quarter-size frames
		begin(coarse, coarseTex, coarseCols, coarseRows);
		bind(0, quarterTex[fromSlot], coarse.uniform("u_prev"));
		bind(1, quarterTex[toSlot], coarse.uniform("u_curr"));
		glUniform2f(coarse.uniform("u_size"), quarterWidth, quarterHeight);
		glUniform2f(coarse.uniform("u_grid"), coarseCols, coarseRows);
		end(coarse);
		boolean cut = false;
		if (measure) {
			begin(error, errorTex, 1, 1);
			bind(0, coarseTex, error.uniform("u_src"));
			glUniform2f(error.uniform("u_grid"), coarseCols, coarseRows);
			end(error);
			pixel.clear();
			glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
			cut = (pixel.get(0) & 0xFF) > SCENE_CUT_ERROR;
			if (cut) {
				glBindFramebuffer(GL_FRAMEBUFFER, 0);
				return true;
			}
		}
		medianInto(coarseTex, coarseMedianTex, coarseCols, coarseRows);
		// refinement on the full-size frames
		begin(refine, refinedTex, cols, rows);
		bind(0, fromTexture, refine.uniform("u_prev"));
		bind(1, toTexture, refine.uniform("u_curr"));
		bind(2, coarseMedianTex, refine.uniform("u_coarse"));
		bind(3, previous != 0 ? previous : coarseMedianTex, refine.uniform("u_previous"));
		glUniform1f(refine.uniform("u_hasPrevious"), previous != 0 ? 1f : 0f);
		glUniform2f(refine.uniform("u_size"), width, height);
		glUniform2f(refine.uniform("u_grid"), cols, rows);
		glUniform2f(refine.uniform("u_coarseGrid"), coarseCols, coarseRows);
		end(refine);
		medianInto(refinedTex, target, cols, rows);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		return cut;
	}

	private void medianInto(int source, int target, int w, int h) {
		begin(median, target, w, h);
		bind(0, source, median.uniform("u_src"));
		glUniform2f(median.uniform("u_grid"), w, h);
		end(median);
	}

	private void begin(Pass pass, int target, int w, int h) {
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
		glViewport(0, 0, w, h);
		glUseProgram(pass.id);
		quad.position(0);
		glVertexAttribPointer(pass.aPosition, 2, GL_FLOAT, false, 16, quad);
		glEnableVertexAttribArray(pass.aPosition);
		quad.position(2);
		glVertexAttribPointer(pass.aUv, 2, GL_FLOAT, false, 16, quad);
		glEnableVertexAttribArray(pass.aUv);
	}

	private static void bind(int unit, int texture, int uniform) {
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texture);
		glUniform1i(uniform, unit);
	}

	private static void end(Pass pass) {
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		glDisableVertexAttribArray(pass.aPosition);
		glDisableVertexAttribArray(pass.aUv);
		// the targets are read by later passes: none may stay bound where a pass draws
		for (int unit : new int[]{GL_TEXTURE3, GL_TEXTURE2, GL_TEXTURE1, GL_TEXTURE0}) {
			glActiveTexture(unit);
			glBindTexture(GL_TEXTURE_2D, 0);
		}
	}

	/**
	 * Reads the vectors of the last search back (for tests): {@code cols * rows * 2} values, x then y
	 * of each block, in quarter pixels.
	 */
	int[] readVectors(boolean backward) {
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
				backward ? backwardTex : forwardTex[current], 0);
		ByteBuffer buf = ByteBuffer.allocateDirect(cols * rows * 4).order(ByteOrder.nativeOrder());
		glReadPixels(0, 0, cols, rows, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		int[] out = new int[cols * rows * 2];
		for (int i = 0; i < cols * rows; i++) {
			out[i * 2] = (buf.get(i * 4) & 0xFF) - 128;
			out[i * 2 + 1] = (buf.get(i * 4 + 3) & 0xFF) - 128;
		}
		return out;
	}

	/** Frees the GL objects. */
	void release() {
		for (Pass p : new Pass[]{quarter, coarse, refine, median, error}) {
			if (p != null) {
				glDeleteProgram(p.id);
			}
		}
		quarter = coarse = refine = median = error = null;
		int[] textures = {quarterTex[0], quarterTex[1], quarterTex[2], coarseTex, coarseMedianTex,
				refinedTex, forwardTex[0], forwardTex[1], backwardTex, errorTex};
		glDeleteTextures(textures.length, textures, 0);
		if (fbo != 0) {
			glDeleteFramebuffers(1, new int[]{fbo}, 0);
			fbo = 0;
		}
	}
}
