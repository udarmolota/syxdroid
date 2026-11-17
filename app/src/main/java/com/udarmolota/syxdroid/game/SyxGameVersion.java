package com.udarmolota.syxdroid.game;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The version of an instance's Songs of Syx, read from its SongsOfSyx.jar: the game keeps it as
 * two compile-time constants of {@code game.VERSION} ({@code VERSION_MAJOR = 71},
 * {@code VERSION_MINOR = 44} for 0.71.44), which sit in the class file's constant pool. So the
 * version is known right after the import, before the game has ever run (its own settings file
 * records it only on a clean exit). Read once per jar and kept in memory.
 */
public final class SyxGameVersion {
    private static final String TAG = "SyxGameVersion";
    private static final String JAR = "SongsOfSyx.jar";
    private static final String VERSION_CLASS = "game/VERSION.class";

    /** Major and minor, e.g. 71 and 44. */
    public final int major, minor;

    private SyxGameVersion(int major, int minor) {
        this.major = major;
        this.minor = minor;
    }

    /** "0.71.44", as the game prints it. */
    @Override
    public String toString() {
        return "0." + major + "." + minor;
    }

    private static final Map<String, SyxGameVersion> CACHE = new HashMap<>();

    /** The version of this instance's game, or null when its jar cannot be read. */
    @Nullable
    public static SyxGameVersion of(GameInstance gi) {
        File jar = new File(gi.getGamePath(), JAR);
        if (!jar.isFile()) return null;
        String key = jar.getAbsolutePath() + "@" + jar.lastModified() + "/" + jar.length();
        synchronized (CACHE) {
            if (CACHE.containsKey(key)) return CACHE.get(key);
        }
        SyxGameVersion v = null;
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry e = zip.getEntry(VERSION_CLASS);
            if (e != null) {
                try (InputStream in = zip.getInputStream(e)) {
                    v = readConstants(new DataInputStream(in));
                }
            }
        } catch (IOException | RuntimeException ex) {
            Log.w(TAG, "could not read the version from " + jar, ex);
        }
        synchronized (CACHE) { CACHE.put(key, v); }
        return v;
    }

    /** VERSION_MAJOR and VERSION_MINOR from the class file's ConstantValue attributes. */
    @Nullable
    private static SyxGameVersion readConstants(DataInputStream in) throws IOException {
        if (in.readInt() != 0xCAFEBABE) return null;
        in.readUnsignedShort();                       // minor class version
        in.readUnsignedShort();                       // major class version
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        Integer[] ints = new Integer[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: utf8[i] = in.readUTF(); break;                              // Utf8
                case 3: ints[i] = in.readInt(); break;                              // Integer
                case 4: in.readInt(); break;                                        // Float
                case 5: case 6: in.readLong(); i++; break;                          // Long, Double: two slots
                case 7: case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;      // MethodHandle
                default: throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();                       // access flags
        in.readUnsignedShort();                       // this class
        in.readUnsignedShort();                       // super class
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) in.readUnsignedShort();
        int fields = in.readUnsignedShort();
        int major = -1, minor = -1;
        for (int f = 0; f < fields; f++) {
            in.readUnsignedShort();                   // access flags
            String name = utf8[in.readUnsignedShort()];
            in.readUnsignedShort();                   // descriptor
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                String attr = utf8[in.readUnsignedShort()];
                int len = in.readInt();
                if ("ConstantValue".equals(attr) && len == 2) {
                    Integer value = ints[in.readUnsignedShort()];
                    if (value != null && "VERSION_MAJOR".equals(name)) major = value;
                    if (value != null && "VERSION_MINOR".equals(name)) minor = value;
                } else {
                    in.readFully(new byte[len]);   // skipBytes may skip less on a zip stream
                }
            }
        }
        return major >= 0 ? new SyxGameVersion(major, Math.max(minor, 0)) : null;
    }
}
