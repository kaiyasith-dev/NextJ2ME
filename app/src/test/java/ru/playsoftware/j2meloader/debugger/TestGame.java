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

package ru.playsoftware.j2meloader.debugger;

import java.util.Vector;

/**
 * Stand-in for a J2ME game with predictable state. It plays the role of the internal test
 * MIDlet: the debugger inspects it exactly like it inspects a converted game on ART.
 */
public class TestGame {
	public static int health = 100;
	public static int money = 5000;
	public static int level = 1;
	public static float speed = 1.0f;
	public static boolean godMode = false;
	public static int[] inventory = {3, 7, 100, 42, 100, 9};
	/** Contains 00 00 00 64 at odd offset 2, to exercise unaligned raw scans. */
	public static byte[] saveData = {1, 2, 0, 0, 0, 100, 9};
	public static String playerName = "Hero";
	public static Player player = new Player();
	public static Vector<Player> party = new Vector<>();
	public static int[] big;
	/** Compile time constants are inlined by the compiler and are not scanned. */
	public static final int MAX_LEVEL = 99;
	private static int hidden = 777;

	public int score = 4242;
	public Player owner = player;

	/** Per object state. */
	public static class Player {
		public int hp = 100;
		public int gold = 250;
		public short mp = 30;
		public byte level8 = 7;
		public char grade = 'A';
		public long xp = 123456789012L;
		public double luck = 0.5;
		public String title = "Squire";
		public byte[] stats = {10, 20, 30, 40};
	}

	public static void reset() {
		health = 100;
		money = 5000;
		level = 1;
		speed = 1.0f;
		godMode = false;
		inventory = new int[]{3, 7, 100, 42, 100, 9};
		saveData = new byte[]{1, 2, 0, 0, 0, 100, 9};
		playerName = "Hero";
		player = new Player();
		party = new Vector<>();
		party.add(new Player());
		big = null;
		hidden = 777;
	}

	public static int hidden() {
		return hidden;
	}
}
