# NextJ2ME

NextJ2ME is a community fork of [J2ME Loader](https://github.com/nikita36078/J2ME-Loader), a J2ME (Java ME) emulator for Android. It keeps everything the original does and focuses on three things:

- **New features** that help people who play, study and debug J2ME games.
- **Performance**: faster, lighter emulation and tools that stay out of the way when they are not used.
- **Bug fixes**: problems found while using the emulator, fixed and kept covered by tests where possible.

> NextJ2ME is an independent fork. It is not affiliated with or endorsed by the authors of J2ME Loader.

## What is new in this fork

### Memory Debugger

A built-in tool to scan, watch, edit and freeze the state of a running game. J2ME Loader has no VM of its own (games are converted to DEX and run on ART), so the debugger works on the **real Java state of the game**: it never shows host pointers or Android process memory.

Turn it on in **Settings → Developer / Debugging** (off by default). Then open it from the toolbar while a game is running.

- **Scan sources:** Raw memory (a virtual big-endian address space built from the game's primitive arrays), Java objects, Primitive arrays and Static fields.
- **Value types:** int8, uint8, int16, uint16, int32, uint32, int64, uint64, float, double, boolean, string and raw bytes.
- **Scan modes:** exact value, unknown initial value, changed / unchanged, increased / decreased (optionally by an amount), equal / not equal to, and **group scan** (find several values that sit together, such as a score next to lives).
- **Scan history:** go back to the results of any earlier scan step.
- **Memory view:** List and Hex views, change the data type on the fly, and jump to an address from a scan result or a watch.
- **Edit and freeze:** write a new value, or keep a value fixed while the game runs.
- **Watch list:** keep an eye on values and open any of them in the memory view.
- **Per-game persistence:** watches, frozen values and scan settings are saved separately for each game.
- **Memory safety:** scans are capped, history is trimmed when memory runs low and the user is told when results are dropped, so the debugger does not take the game down.

### Branding

New launcher icon (light style), app name and About screen that credit the original project.

## Roadmap

This is the direction of the project, not a list of finished work. Ideas and bug reports are welcome.

- Performance work on rendering and input handling, measured on real games before and after.
- Bug fixes for games that are reported as broken on the compatibility lists.
- More translations for the new screens.

## Building

You need Android Studio (or the Android SDK with a JDK) and the Android NDK, because the project contains native code.

```bash
./gradlew :app:assembleDevDebug
```

Run the unit tests (including the Memory Debugger tests) with:

```bash
./gradlew :app:testDevDebugUnitTest
```

## Contributing

Bug reports, feature requests and pull requests are welcome through the [issue tracker](https://github.com/kaiyasith-dev/NextJ2ME/issues). When you report a bug, please include the game name, your Android version and device, and the steps to reproduce it. For changes to the debugger, please add or update a test in `app/src/test/java/ru/playsoftware/j2meloader/debugger/`.

## Credits and license

NextJ2ME is built on the work of the J2ME Loader authors. Their copyright notices are kept in every source file, and the new code is marked `Copyright 2026 ksdevla`.

> Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0). See the [LICENSE](LICENSE) file for the whole license text.

The rest of this file is the original project's README, kept for reference. Download links in it point to the original app, not to NextJ2ME.

---

# J2ME Loader (upstream project)

J2ME-Loader is a J2ME emulator for Android. It supports most 2D and 3D games (including Mascot Capsule 3D ones). Emulator has a virtual keyboard, individual settings for each application, scaling support.
This project is a fork of [J2meLoader](https://github.com/NaikSoftware/J2meLoader).  
Special thanks to [woesss](https://github.com/woesss), the author of [JL-Mod](https://github.com/woesss/JL-Mod), for creating open-source Mascot Capsule implementation.

System requirements: Android 4.0+  
[4PDA discussion](https://4pda.to/forum/index.php?showtopic=824201)  
[XDA-Developers](https://forum.xda-developers.com/android/apps-games/app-j2me-loader-t3777889)  
[EmuGen wiki](https://emulation.gametechwiki.com/index.php/J2ME_Loader)  
[Discord](https://discord.gg/Ag4rcpz)  
[Automated builds](https://install.appcenter.ms/users/nikita36078/apps/j2me-loader/distribution_groups/testers)

## Compatibility
[List of the tested Java Games (Touchscreen)](https://github.com/nikita36078/J2ME-Loader/wiki/List-of-Tested-Java-Games-(Touchscreen))  
[List of the tested Java Games (Non Touchscreen)](https://github.com/nikita36078/J2ME-Loader/wiki/List-of-Tested-Java-Games-(Non-Touchscreen))  
[List of the Java Games with Bugs](https://github.com/nikita36078/J2ME-Loader/wiki/List-of-Java-Games-with-Bugs)

## Tips
 - Enabling filtering in some cases can greatly reduce performance. Disable this option if game is too slow.
 - Image flickering issues can be fixed by enabling the "Immediate processing mode" option.

## Screenshots

<img src="/screenshots/screen.jpg" width="288" height="512"> <img src="/screenshots/screen2.jpg" width="288" height="512">
<img src="/screenshots/screen3.jpg" width="288" height="512"> <img src="/screenshots/screen4.jpg" width="288" height="512">
* For more screenshots check out the [wiki](https://emulation.gametechwiki.com/index.php/J2ME_Loader#Screenshots)

## License
> Copyright 2017-2024 Nikita Shakarun.
> Licensed under the [Apache License, Version 2.0.](http://www.apache.org/licenses/LICENSE-2.0)  
> (See the [LICENSE](https://github.com/nikita36078/J2ME-Loader/blob/master/LICENSE) file for the whole license text.)
