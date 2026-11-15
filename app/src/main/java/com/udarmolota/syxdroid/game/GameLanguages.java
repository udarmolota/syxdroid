package com.udarmolota.syxdroid.game;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The languages of an imported Songs of Syx and the choice between them.
 *
 * <p>The game keeps its translations in {@code base/locale.zip}, one folder per language under
 * {@code langs/} ("ru", "pt-BR", "zh-CN", ...); English is built in. Its own launcher, which
 * SyxDroid skips, writes the choice as {@code LANGUAGE} in LauncherSettings.txt (empty =
 * English); SyxGameSettings does that now, before each start.
 */
public final class GameLanguages {
    /** Stored choice: the phone's language when the game has it, else English. */
    public static final String AUTO = "auto";
    /** Stored choice and game value for English (the game's built-in language). */
    public static final String ENGLISH = "";

    private GameLanguages() {}

    /** The language codes in this instance's locale.zip, sorted; empty if it cannot be read. */
    @NonNull
    public static List<String> available(@NonNull GameInstance instance) {
        TreeSet<String> codes = new TreeSet<>();
        File zip = new File(instance.getGamePath(), "base/locale.zip");
        if (zip.isFile()) {
            try (ZipFile z = new ZipFile(zip)) {
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName().replace('\\', '/');
                    if (!name.startsWith("langs/")) continue;
                    String rest = name.substring("langs/".length());
                    int slash = rest.indexOf('/');
                    if (slash > 0) codes.add(rest.substring(0, slash));
                }
            } catch (Exception ignored) {
                // A damaged locale.zip leaves English only; the game would not read it either.
            }
        }
        return new ArrayList<>(codes);
    }

    /** The value for the game's LANGUAGE key: a code, or "" for English. */
    @NonNull
    public static String resolve(@NonNull String stored, @NonNull GameInstance instance) {
        if (!AUTO.equals(stored)) return stored;
        return phoneLanguage(available(instance));
    }

    /** The phone's language as one of these codes, or "" (English) when the game does not have it. */
    @NonNull
    static String phoneLanguage(@NonNull List<String> codes) {
        Locale phone = Locale.getDefault();
        String lang = phone.getLanguage();
        if ("en".equals(lang)) return ENGLISH;
        if ("zh".equals(lang)) {
            // Traditional script (Taiwan, Hong Kong, Macau) or simplified.
            String script = phone.getScript();
            String country = phone.getCountry();
            boolean traditional = "Hant".equals(script) || "TW".equals(country)
                    || "HK".equals(country) || "MO".equals(country);
            String code = traditional ? "zh-TW" : "zh-CN";
            return codes.contains(code) ? code : ENGLISH;
        }
        String tag = lang + "-" + phone.getCountry();
        if (codes.contains(tag)) return tag;                       // pt-BR, es-ES
        if (codes.contains(lang)) return lang;                     // ru, de, uk...
        for (String c : codes) if (c.startsWith(lang + "-")) return c;   // es -> es-ES, pt -> pt-BR
        return ENGLISH;
    }

    /** A code's name in that language itself ("Русский", "Deutsch"), as players look for it. */
    @NonNull
    public static String displayName(@NonNull String code) {
        if (ENGLISH.equals(code)) return "English";
        Locale l = Locale.forLanguageTag(code);
        String name = l.getDisplayName(l);
        if (name.isEmpty()) return code;
        return name.substring(0, 1).toUpperCase(l) + name.substring(1);
    }
}
