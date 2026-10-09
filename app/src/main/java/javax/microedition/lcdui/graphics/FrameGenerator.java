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
import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_FLOAT;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_FRAMEBUFFER_COMPLETE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_LUMINANCE_ALPHA;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_TEXTURE0;
import static android.opengl.GLES20.GL_TEXTURE1;
import static android.opengl.GLES20.GL_TEXTURE2;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.GL_TEXTURE_MAG_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_MIN_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_S;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_T;
import static android.opengl.GLES20.GL_TRIANGLE_STRIP;
import static android.opengl.GLES20.GL_UNPACK_ALIGNMENT;
import static android.opengl.GLES20.GL_UNSIGNED_BYTE;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glCheckFramebufferStatus;
import static android.opengl.GLES20.glDeleteFramebuffers;
import static android.opengl.GLES20.glDeleteProgram;
import static android.opengl.GLES20.glDeleteTextures;
import static android.opengl.GLES20.glDisableVertexAttribArray;
import static android.opengl.GLES20.glDrawArrays;
import static android.opengl.GLES20.glEnableVertexAttribArray;
import static android.opengl.GLES20.glFramebufferTexture2D;
import static android.opengl.GLES20.glGenFramebuffers;
import static android.opengl.GLES20.glGenTextures;
import static android.opengl.GLES20.glPixelStorei;
import static android.opengl.GLES20.glTexImage2D;
import static android.opengl.GLES20.glTexParameteri;
import static android.opengl.GLES20.glUniform1f;
import static android.opengl.GLES20.glUniform1i;
import static android.opengl.GLES20.glUniform2f;
import static android.opengl.GLES20.glUseProgram;
import static android.opengl.GLES20.glVertexAttribPointer;
import static android.opengl.GLES20.glViewport;

import android.graphics.Bitmap;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Frame generation: shows pictures between the frames a game finishes, so that a game that
 * draws 20 frames per second looks smoother on a 60 Hz screen. The game itself is not changed
 * or sped up; only what is shown on screen.
 * <p>
 * It keeps the previous and the newest game frame as textures. On every screen refresh it draws a
 * picture between them into an off-screen texture, which the normal drawing code (with the
 * user's shader filter and scaling) then puts on the screen. With motion enabled, how each block
 * of the picture moved between the two frames is worked out on a worker thread
 * ({@link MotionEstimator}) and the parts are moved along that motion before they are blended;
 * until it is ready, or when nothing matches, the two frames are only cross-faded.
 * <p>
 * The price is one game frame of added delay, because the newest frame is shown only after the
 * picture between it and the previous one. All methods must be called on the GL thread.
 */
public final class FrameGenerator {
	private static final String TAG = FrameGenerator.class.getName();
	private static final int BLOCK = 8;
	private static final int RANGE = 8;

	/** What the worker found out about the motion into a frame (no data: it could not tell). */
	private static final class Motion {
		final int generation;
		final int frame;
		final int cols;
		final int rows;
		final byte[] data;
		final boolean sceneCut;
		final long readyNanos;

		Motion(int generation, int frame, int cols, int rows, byte[] data, boolean sceneCut,
			   long readyNanos) {
			this.generation = generation;
			this.frame = frame;
			this.cols = cols;
			this.rows = rows;
			this.data = data;
			this.sceneCut = sceneCut;
			this.readyNanos = readyNanos;
		}
	}

	/** The previous picture seen by the worker; only the worker thread touches it. */
	private static final class GrayState {
		byte[] gray;
		int frame = -2;
	}

	private final boolean wantMotion;
	private final boolean linear;
	private final InterpolationProgram program;
	private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
			.order(ByteOrder.nativeOrder()).asFloatBuffer();
	private final AtomicBoolean busy = new AtomicBoolean();
	/** The kept game frames: frame n is in frameTex[n % KEPT]. */
	private final int[] frameTex = new int[FramePacer.KEPT];
	/** The motion into each kept frame, once the worker has it. */
	private final Motion[] motions = new Motion[FramePacer.KEPT];
	private FramePacer pacer = new FramePacer(false);

	private int width;
	private int height;
	private int texMotion;
	private int texOut;
	private int fbo;
	private boolean allocated;

	/** Counts the (re)allocations: motion from an earlier one is ignored. */
	private int generation;
	private GrayState grayState = new GrayState();
	private int seenFrame = -1;
	private int frameIndex;

	private MotionEstimator estimator;
	private int[] pixels;
	private ExecutorService worker;
	private volatile Motion motion;
	private int uploadedMotion = -1;
	private int gridCols = 1;
	private int gridRows = 1;
	private float blockPxX = 1;
	private float blockPxY = 1;

	private FrameGenerator(InterpolationProgram program, boolean wantMotion, boolean linear) {
		this.program = program;
		this.wantMotion = wantMotion;
		this.linear = linear;
		// a full-screen quad: clip position then texture position, so the output texture is the
		// same picture as the input textures
		quad.put(new float[]{
				-1f, -1f, 0f, 0f,
				-1f, 1f, 0f, 1f,
				1f, -1f, 1f, 0f,
				1f, 1f, 1f, 1f});
		quad.rewind();
	}

	/**
	 * Creates the generator on the GL thread.
	 *
	 * @param withMotion follow the motion between frames; otherwise the frames are only cross-faded
	 * @param linear     filter the textures smoothly (the screen's filtering setting)
	 * @return null if the device can not run the shader, in which case the normal drawing is used
	 */
	public static FrameGenerator create(boolean withMotion, boolean linear) {
		InterpolationProgram p = InterpolationProgram.create();
		return p == null ? null : new FrameGenerator(p, withMotion, linear);
	}

	/**
	 * Works out the picture to show now.
	 *
	 * @param bitmap       the game's newest finished frame
	 * @param bufferLock   the lock that guards {@code bitmap}
	 * @param frameCounter changes every time the game finished a frame
	 * @param frameNanos   when the game finished its newest frame ({@code System.nanoTime()})
	 * @param nowNanos     the current {@code System.nanoTime()}
	 * @return the id of a texture with the picture, or 0 if frame generation can not be used
	 */
	public int render(Bitmap bitmap, Object bufferLock, int frameCounter, long frameNanos, long nowNanos) {
		if (!allocated || bitmap.getWidth() != width || bitmap.getHeight() != height) {
			if (!allocate(bitmap.getWidth(), bitmap.getHeight())) {
				return 0;
			}
		}
		if (frameCounter != seenFrame) {
			seenFrame = frameCounter;
			acceptFrame(bitmap, bufferLock, frameNanos);
		}
		return draw(nowNanos);
	}

	/** Stops the worker and frees the GL objects (when the context is lost they go with it). */
	public void shutdown() {
		releaseBuffers();
		try {
			glDeleteProgram(program.id);
		} catch (RuntimeException e) {
			// the context may already be gone
		}
	}

	/**
	 * Lets go of a generator whose GL context is gone (a new one was made): stops the worker but
	 * makes no GL calls, since its texture and program numbers may now belong to the new context.
	 */
	public void abandon() {
		if (worker != null) {
			worker.shutdownNow();
			worker = null;
		}
		allocated = false;
	}

	/** Frees the textures and the framebuffer, and stops the worker. */
	private void releaseBuffers() {
		if (worker != null) {
			worker.shutdownNow();
			worker = null;
		}
		if (allocated) {
			try {
				glDeleteTextures(5, new int[]{frameTex[0], frameTex[1], frameTex[2], texMotion, texOut}, 0);
				glDeleteFramebuffers(1, new int[]{fbo}, 0);
			} catch (RuntimeException e) {
				// the context may already be gone
			}
			allocated = false;
		}
	}

	// ------------------------------------------------------------------ setup

	private boolean allocate(int w, int h) {
		releaseBuffers();
		generation++;
		grayState = new GrayState();
		int[] ids = new int[5];
		glGenTextures(5, ids, 0);
		System.arraycopy(ids, 0, frameTex, 0, FramePacer.KEPT);
		texMotion = ids[3];
		texOut = ids[4];
		for (int tex : frameTex) {
			setupTexture(tex, linear);
		}
		setupTexture(texMotion, false);
		setupTexture(texOut, linear);
		glBindTexture(GL_TEXTURE_2D, texOut);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);
		int[] fb = new int[1];
		glGenFramebuffers(1, fb, 0);
		fbo = fb[0];
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texOut, 0);
		boolean complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		if (!complete) {
			Log.e(TAG, "the off-screen framebuffer is not usable");
			glDeleteTextures(5, ids, 0);
			glDeleteFramebuffers(1, fb, 0);
			return false;
		}
		width = w;
		height = h;
		allocated = true;
		seenFrame = -1;
		frameIndex = 0;
		uploadedMotion = -1;
		java.util.Arrays.fill(motions, null);
		motion = null;
		estimator = null;
		pixels = null;
		if (wantMotion && w >= 4 * BLOCK && h >= 4 * BLOCK) {
			int gw = w / 2;
			int gh = h / 2;
			estimator = new MotionEstimator(gw, gh, BLOCK, RANGE);
			pixels = new int[w * h];
			gridCols = estimator.cols();
			gridRows = estimator.rows();
			blockPxX = (float) BLOCK * w / gw;
			blockPxY = (float) BLOCK * h / gh;
			worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "FrameGenMotion");
				t.setDaemon(true);
				return t;
			});
			busy.set(false);
		}
		pacer = new FramePacer(estimator != null);
		return true;
	}

	private static void setupTexture(int tex, boolean linear) {
		glBindTexture(GL_TEXTURE_2D, tex);
		int filter = linear ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}

	// ------------------------------------------------------------------ a new game frame

	private void acceptFrame(Bitmap bitmap, Object bufferLock, long frameNanos) {
		boolean grab = estimator != null && !busy.get();
		// the pacer makes sure the frame this one replaces is no longer shown
		int n = pacer.onFrame(frameNanos, grab);
		frameIndex = n;
		glActiveTexture(GL_TEXTURE0);
		synchronized (bufferLock) {
			glBindTexture(GL_TEXTURE_2D, frameTex[n % FramePacer.KEPT]);
			GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
			if (n == 1) {
				// the first frame is blended from itself
				glBindTexture(GL_TEXTURE_2D, frameTex[0]);
				GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
			}
			if (grab) {
				bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
			}
		}
		if (grab) {
			busy.set(true);
			startMotionJob(n);
		}
	}

	/** Hands the picture grabbed under the lock to the worker, which finds the motion into it. */
	private void startMotionJob(final int index) {
		final int[] px = pixels;
		final int w = width;
		final int h = height;
		final MotionEstimator est = estimator;
		final ExecutorService executor = worker;
		final GrayState state = grayState;
		final int gen = generation;
		executor.execute(() -> {
			try {
				byte[] gray = MotionEstimator.toGray(px, w, h);
				// a new picture is only grabbed after this job finished, so nothing else reads it
				Motion found = null;
				if (state.gray != null && state.frame == index - 1) {
					MotionEstimator.Result r = est.estimate(state.gray, gray);
					found = new Motion(gen, index, r.cols, r.rows, r.encode(2), r.sceneCut,
							System.nanoTime());
				}
				state.gray = gray;
				state.frame = index;
				// also when nothing was found, so the blend does not wait for it
				motion = found != null ? found
						: new Motion(gen, index, 0, 0, null, false, System.nanoTime());
			} catch (RuntimeException e) {
				Log.w(TAG, "motion estimation failed", e);
			} finally {
				busy.set(false);
			}
		});
	}

	// ------------------------------------------------------------------ one screen refresh

	private int draw(long nowNanos) {
		Motion published = motion;
		if (published != null && published.generation == generation
				&& published.frame > frameIndex - FramePacer.KEPT
				&& motions[published.frame % FramePacer.KEPT] != published) {
			motions[published.frame % FramePacer.KEPT] = published;
			pacer.onMotionReady(published.frame, published.readyNanos);
		}
		int k = pacer.update(nowNanos);
		float t = pacer.blend();
		boolean useMotion = false;
		Motion m = motions[k % FramePacer.KEPT];
		if (m != null && m.frame == k && m.data != null) {
			if (m.sceneCut) {
				t = 1f; // a different scene: show it as it is
			} else {
				if (uploadedMotion != m.frame) {
					uploadMotion(m);
				}
				useMotion = true;
			}
		}

		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, width, height);
		glUseProgram(program.id);

		quad.position(0);
		glVertexAttribPointer(program.aPosition, 2, GL_FLOAT, false, 16, quad);
		glEnableVertexAttribArray(program.aPosition);
		quad.position(2);
		glVertexAttribPointer(program.aUv, 2, GL_FLOAT, false, 16, quad);
		glEnableVertexAttribArray(program.aUv);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, frameTex[(k - 1) % FramePacer.KEPT]);
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D, frameTex[k % FramePacer.KEPT]);
		glActiveTexture(GL_TEXTURE2);
		glBindTexture(GL_TEXTURE_2D, texMotion);
		glUniform1i(program.uPrev, 0);
		glUniform1i(program.uCurr, 1);
		glUniform1i(program.uMotion, 2);
		glUniform1f(program.uT, t);
		glUniform1f(program.uUseMotion, useMotion ? 1f : 0f);
		glUniform2f(program.uTexSize, width, height);
		glUniform2f(program.uBlockPx, blockPxX, blockPxY);
		glUniform2f(program.uGrid, gridCols, gridRows);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

		glDisableVertexAttribArray(program.aPosition);
		glDisableVertexAttribArray(program.aUv);
		glActiveTexture(GL_TEXTURE0);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		return texOut;
	}

	private void uploadMotion(Motion m) {
		ByteBuffer buf = ByteBuffer.allocateDirect(m.data.length);
		buf.put(m.data).position(0);
		glActiveTexture(GL_TEXTURE2);
		glBindTexture(GL_TEXTURE_2D, texMotion);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, m.cols, m.rows, 0,
				GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, buf);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		glActiveTexture(GL_TEXTURE0);
		gridCols = m.cols;
		gridRows = m.rows;
		uploadedMotion = m.frame;
	}
}
