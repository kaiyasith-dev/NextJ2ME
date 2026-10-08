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

import static android.opengl.GLES20.GL_COMPILE_STATUS;
import static android.opengl.GLES20.GL_FRAGMENT_SHADER;
import static android.opengl.GLES20.GL_LINK_STATUS;
import static android.opengl.GLES20.GL_VERTEX_SHADER;
import static android.opengl.GLES20.glAttachShader;
import static android.opengl.GLES20.glCompileShader;
import static android.opengl.GLES20.glCreateProgram;
import static android.opengl.GLES20.glCreateShader;
import static android.opengl.GLES20.glDeleteProgram;
import static android.opengl.GLES20.glDeleteShader;
import static android.opengl.GLES20.glGetAttribLocation;
import static android.opengl.GLES20.glGetProgramInfoLog;
import static android.opengl.GLES20.glGetProgramiv;
import static android.opengl.GLES20.glGetShaderInfoLog;
import static android.opengl.GLES20.glGetShaderiv;
import static android.opengl.GLES20.glGetUniformLocation;
import static android.opengl.GLES20.glLinkProgram;
import static android.opengl.GLES20.glShaderSource;

import android.util.Log;

/**
 * The shader that draws a picture between two game frames (see {@link FrameGenerator}): it
 * samples the previous and the newest frame, moves each part of them along the motion found
 * between the frames and blends the two.
 */
final class InterpolationProgram {
	private static final String TAG = InterpolationProgram.class.getName();

	private static final String VERTEX =
			"attribute vec4 a_position;\n"
					+ "attribute vec2 a_uv;\n"
					+ "varying vec2 v_uv;\n"
					+ "void main() {\n"
					+ "    gl_Position = a_position;\n"
					+ "    v_uv = a_uv;\n"
					+ "}\n";

	/**
	 * The motion texture has one texel per block and two 8-bit channels (luminance and alpha) that
	 * hold the motion in frame pixels plus 128. A vector (vx, vy) means: what is at p in the
	 * previous frame is at p + v in the newest frame. At blend position t, the previous frame is
	 * sampled at p - v*t and the newest frame at p + v*(1-t).
	 */
	private static final String FRAGMENT =
			"#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
					+ "precision highp float;\n"
					+ "#else\n"
					+ "precision mediump float;\n"
					+ "#endif\n"
					+ "uniform sampler2D u_prev;\n"
					+ "uniform sampler2D u_curr;\n"
					+ "uniform sampler2D u_motion;\n"
					+ "uniform float u_t;\n"
					+ "uniform float u_useMotion;\n"
					+ "uniform vec2 u_texSize;\n"
					+ "uniform vec2 u_blockPx;\n"
					+ "uniform vec2 u_grid;\n"
					+ "varying vec2 v_uv;\n"
					+ "void main() {\n"
					+ "    vec2 mv = vec2(0.0);\n"
					+ "    if (u_useMotion > 0.5) {\n"
					+ "        vec2 bi = floor(v_uv * u_texSize / u_blockPx);\n"
					+ "        vec2 muv = (min(bi, u_grid - 1.0) + 0.5) / u_grid;\n"
					+ "        vec4 m = texture2D(u_motion, muv);\n"
					+ "        mv = (vec2(m.r, m.a) * 255.0 - 128.0) / u_texSize;\n"
					+ "    }\n"
					+ "    vec4 a = texture2D(u_prev, v_uv - mv * u_t);\n"
					+ "    vec4 b = texture2D(u_curr, v_uv + mv * (1.0 - u_t));\n"
					+ "    gl_FragColor = mix(a, b, u_t);\n"
					+ "}\n";

	final int id;
	final int aPosition;
	final int aUv;
	final int uPrev;
	final int uCurr;
	final int uMotion;
	final int uT;
	final int uUseMotion;
	final int uTexSize;
	final int uBlockPx;
	final int uGrid;

	private InterpolationProgram(int id) {
		this.id = id;
		aPosition = glGetAttribLocation(id, "a_position");
		aUv = glGetAttribLocation(id, "a_uv");
		uPrev = glGetUniformLocation(id, "u_prev");
		uCurr = glGetUniformLocation(id, "u_curr");
		uMotion = glGetUniformLocation(id, "u_motion");
		uT = glGetUniformLocation(id, "u_t");
		uUseMotion = glGetUniformLocation(id, "u_useMotion");
		uTexSize = glGetUniformLocation(id, "u_texSize");
		uBlockPx = glGetUniformLocation(id, "u_blockPx");
		uGrid = glGetUniformLocation(id, "u_grid");
	}

	/** Compiles and links the shader; null if the device refuses it (frame generation then stays off). */
	static InterpolationProgram create() {
		int vertex = compile(GL_VERTEX_SHADER, VERTEX);
		int fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT);
		if (vertex == 0 || fragment == 0) {
			return null;
		}
		int program = glCreateProgram();
		glAttachShader(program, vertex);
		glAttachShader(program, fragment);
		glLinkProgram(program);
		int[] status = new int[1];
		glGetProgramiv(program, GL_LINK_STATUS, status, 0);
		glDeleteShader(vertex);
		glDeleteShader(fragment);
		if (status[0] == 0) {
			Log.e(TAG, "link failed: " + glGetProgramInfoLog(program));
			glDeleteProgram(program);
			return null;
		}
		InterpolationProgram p = new InterpolationProgram(program);
		if (p.aPosition < 0 || p.aUv < 0 || p.uPrev < 0 || p.uCurr < 0 || p.uT < 0) {
			Log.e(TAG, "the shader does not have the expected inputs");
			glDeleteProgram(program);
			return null;
		}
		return p;
	}

	private static int compile(int type, String source) {
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		int[] status = new int[1];
		glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0) {
			Log.e(TAG, "compile failed: " + glGetShaderInfoLog(shader));
			glDeleteShader(shader);
			return 0;
		}
		return shader;
	}
}
