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

package com.mascotcapsule.micro3d.v3;

import static android.opengl.GLES20.GL_ACTIVE_TEXTURE;
import static android.opengl.GLES20.GL_BLEND;
import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_COMPILE_STATUS;
import static android.opengl.GLES20.GL_DEPTH_ATTACHMENT;
import static android.opengl.GLES20.GL_DEPTH_COMPONENT16;
import static android.opengl.GLES20.GL_DEPTH_TEST;
import static android.opengl.GLES20.GL_NO_ERROR;
import static android.opengl.GLES20.GL_FLOAT;
import static android.opengl.GLES20.GL_FRAGMENT_SHADER;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_FRAMEBUFFER_COMPLETE;
import static android.opengl.GLES20.GL_LINK_STATUS;
import static android.opengl.GLES20.GL_MAX_RENDERBUFFER_SIZE;
import static android.opengl.GLES20.GL_MAX_TEXTURE_SIZE;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_RENDERBUFFER;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_SCISSOR_TEST;
import static android.opengl.GLES20.GL_TEXTURE0;
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
import static android.opengl.GLES20.glBindRenderbuffer;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glCheckFramebufferStatus;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;
import static android.opengl.GLES20.glCompileShader;
import static android.opengl.GLES20.glCreateProgram;
import static android.opengl.GLES20.glCreateShader;
import static android.opengl.GLES20.glDeleteFramebuffers;
import static android.opengl.GLES20.glDeleteProgram;
import static android.opengl.GLES20.glDeleteRenderbuffers;
import static android.opengl.GLES20.glDeleteShader;
import static android.opengl.GLES20.glDeleteTextures;
import static android.opengl.GLES20.glDisable;
import static android.opengl.GLES20.glDisableVertexAttribArray;
import static android.opengl.GLES20.glDrawArrays;
import static android.opengl.GLES20.glEnable;
import static android.opengl.GLES20.glEnableVertexAttribArray;
import static android.opengl.GLES20.glFramebufferRenderbuffer;
import static android.opengl.GLES20.glFramebufferTexture2D;
import static android.opengl.GLES20.glGenFramebuffers;
import static android.opengl.GLES20.glGenRenderbuffers;
import static android.opengl.GLES20.glGenTextures;
import static android.opengl.GLES20.glGetAttribLocation;
import static android.opengl.GLES20.glGetError;
import static android.opengl.GLES20.glGetIntegerv;
import static android.opengl.GLES20.glGetProgramiv;
import static android.opengl.GLES20.glGetShaderiv;
import static android.opengl.GLES20.glGetUniformLocation;
import static android.opengl.GLES20.glIsEnabled;
import static android.opengl.GLES20.glLineWidth;
import static android.opengl.GLES20.glLinkProgram;
import static android.opengl.GLES20.glRenderbufferStorage;
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
 * Renders the 3D scene at a multiple of the game's size and averages it down: a higher internal
 * resolution. The game still gets a picture of its own size, but polygon edges are smooth because
 * every output pixel is the mean of scale x scale rendered pixels.
 * <p>
 * It owns an off-screen framebuffer ({@code scale} times the size of the game screen) that the
 * renderer draws into instead of the pbuffer. {@link #resolve()} then draws that framebuffer, shrunk,
 * onto the pbuffer, where the renderer reads the finished picture as it always did.
 * <p>
 * All methods must be called with the renderer's EGL context current.
 */
final class SuperSampler {
	private static final String VERTEX =
			"attribute vec4 a_position;\n"
					+ "attribute vec2 a_uv;\n"
					+ "varying vec2 v_uv;\n"
					+ "void main() {\n"
					+ "    gl_Position = a_position;\n"
					+ "    v_uv = a_uv;\n"
					+ "}\n";

	/**
	 * The centre of the output pixel is in the middle of a block of scale x scale rendered pixels:
	 * the block's first pixel is half a block (less half a pixel) before it.
	 */
	private static final String FRAGMENT =
			"precision highp float;\n"
					+ "uniform sampler2D u_tex;\n"
					+ "uniform vec2 u_texel;\n"
					+ "uniform float u_scale;\n"
					+ "varying vec2 v_uv;\n"
					+ "void main() {\n"
					+ "    vec2 origin = v_uv - u_texel * (u_scale * 0.5 - 0.5);\n"
					+ "    vec4 sum = vec4(0.0);\n"
					+ "    for (int j = 0; j < 4; j++) {\n"
					+ "        if (float(j) >= u_scale) break;\n"
					+ "        for (int i = 0; i < 4; i++) {\n"
					+ "            if (float(i) >= u_scale) break;\n"
					+ "            sum += texture2D(u_tex, origin + vec2(float(i), float(j)) * u_texel);\n"
					+ "        }\n"
					+ "    }\n"
					+ "    gl_FragColor = sum / (u_scale * u_scale);\n"
					+ "}\n";

	/** The biggest multiple the shader can average. */
	static final int MAX_SCALE = 4;

	private static final FloatBuffer QUAD = ByteBuffer.allocateDirect(16 * 4)
			.order(ByteOrder.nativeOrder()).asFloatBuffer()
			.put(new float[]{
					-1f, -1f, 0f, 0f,
					1f, -1f, 1f, 0f,
					-1f, 1f, 0f, 1f,
					1f, 1f, 1f, 1f});

	/** How many times bigger than the game screen the scene is rendered. */
	final int scale;
	private final int width;
	private final int height;
	private int fbo;
	private int texture;
	private int depth;
	private int program;
	private int aPosition;
	private int aUv;
	private int uTex;
	private int uTexel;
	private int uScale;

	private SuperSampler(int width, int height, int scale) {
		this.width = width;
		this.height = height;
		this.scale = scale;
	}

	/**
	 * Creates the off-screen framebuffer for a game screen of this size.
	 *
	 * @return null if the device can not do it (too big, no shader...): the caller then renders at
	 * the normal size
	 */
	static SuperSampler create(int width, int height, int wanted) {
		int[] max = new int[2];
		glGetIntegerv(GL_MAX_TEXTURE_SIZE, max, 0);
		glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, max, 1);
		int limit = Math.min(max[0], max[1]);
		int scale = Math.min(wanted, MAX_SCALE);
		while (scale > 1 && (width * scale > limit || height * scale > limit)) {
			scale--;
		}
		if (scale < 2) {
			Log.w(Util3D.TAG, "3D resolution: " + width + "x" + height + " is too big to render larger");
			return null;
		}
		while (glGetError() != GL_NO_ERROR) {
			// an error somebody else left must not be blamed on this
		}
		SuperSampler s = new SuperSampler(width, height, scale);
		if (!s.allocate() || glGetError() != GL_NO_ERROR) {
			s.destroy();
			while (glGetError() != GL_NO_ERROR) {
				// the renderer throws on any pending error, so none may be left behind
			}
			return null;
		}
		return s;
	}

	private boolean allocate() {
		int w = width * scale;
		int h = height * scale;
		int[] id = new int[1];

		glGenTextures(1, id, 0);
		texture = id[0];
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);

		glGenRenderbuffers(1, id, 0);
		depth = id[0];
		glBindRenderbuffer(GL_RENDERBUFFER, depth);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, w, h);
		glBindRenderbuffer(GL_RENDERBUFFER, 0);

		glGenFramebuffers(1, id, 0);
		fbo = id[0];
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
		boolean complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
		if (!complete) {
			Log.w(Util3D.TAG, "3D resolution: the larger framebuffer is not usable");
			glBindFramebuffer(GL_FRAMEBUFFER, 0);
			return false;
		}
		if (!createProgram()) {
			glBindFramebuffer(GL_FRAMEBUFFER, 0);
			return false;
		}
		// start from a clean picture, like the pbuffer does
		glViewport(0, 0, w, h);
		glDisable(GL_SCISSOR_TEST);
		glClearColor(0, 0, 0, 1);
		glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT | android.opengl.GLES20.GL_DEPTH_BUFFER_BIT);
		return true;
	}

	private boolean createProgram() {
		int v = compile(GL_VERTEX_SHADER, VERTEX);
		int f = compile(GL_FRAGMENT_SHADER, FRAGMENT);
		if (v == 0 || f == 0) {
			return false;
		}
		program = glCreateProgram();
		glAttachShader(program, v);
		glAttachShader(program, f);
		glLinkProgram(program);
		glDeleteShader(v);
		glDeleteShader(f);
		int[] status = new int[1];
		glGetProgramiv(program, GL_LINK_STATUS, status, 0);
		if (status[0] == 0) {
			Log.w(Util3D.TAG, "3D resolution: the shader did not link");
			return false;
		}
		aPosition = glGetAttribLocation(program, "a_position");
		aUv = glGetAttribLocation(program, "a_uv");
		uTex = glGetUniformLocation(program, "u_tex");
		uTexel = glGetUniformLocation(program, "u_texel");
		uScale = glGetUniformLocation(program, "u_scale");
		return aPosition >= 0 && aUv >= 0 && uTex >= 0 && uTexel >= 0 && uScale >= 0;
	}

	private static int compile(int type, String source) {
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		int[] status = new int[1];
		glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0) {
			Log.w(Util3D.TAG, "3D resolution: the shader did not compile");
			glDeleteShader(shader);
			return 0;
		}
		return shader;
	}

	/** Makes the renderer draw into the larger framebuffer. */
	void bind() {
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, width * scale, height * scale);
		// lines are one pixel wide; keep them that wide after shrinking (drivers may ignore it)
		glLineWidth(scale);
	}

	/**
	 * Averages the larger picture down onto the pbuffer. Afterwards the pbuffer is the framebuffer
	 * again, so the picture can be read from it.
	 */
	void resolve() {
		boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
		int[] unit = new int[1];
		glGetIntegerv(GL_ACTIVE_TEXTURE, unit, 0);

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glViewport(0, 0, width, height);
		glDisable(GL_SCISSOR_TEST);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_BLEND);
		glUseProgram(program);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, texture);
		glUniform1i(uTex, 0);
		glUniform2f(uTexel, 1f / (width * scale), 1f / (height * scale));
		glUniform1f(uScale, scale);
		QUAD.position(0);
		glVertexAttribPointer(aPosition, 2, GL_FLOAT, false, 16, QUAD);
		glEnableVertexAttribArray(aPosition);
		QUAD.position(2);
		glVertexAttribPointer(aUv, 2, GL_FLOAT, false, 16, QUAD);
		glEnableVertexAttribArray(aUv);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		glDisableVertexAttribArray(aPosition);
		glDisableVertexAttribArray(aUv);

		glActiveTexture(unit[0]);
		if (scissor) {
			glEnable(GL_SCISSOR_TEST);
		}
		int error;
		while ((error = glGetError()) != GL_NO_ERROR) {
			// the renderer throws on any pending error: this one only costs some quality
			Log.w(Util3D.TAG, "3D resolution: glError " + error);
		}
	}

	/** Frees the GL objects; the framebuffer binding goes back to the pbuffer. */
	void destroy() {
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glLineWidth(1f);
		if (fbo != 0) glDeleteFramebuffers(1, new int[]{fbo}, 0);
		if (depth != 0) glDeleteRenderbuffers(1, new int[]{depth}, 0);
		if (texture != 0) glDeleteTextures(1, new int[]{texture}, 0);
		if (program != 0) glDeleteProgram(program);
		fbo = depth = texture = program = 0;
	}
}
