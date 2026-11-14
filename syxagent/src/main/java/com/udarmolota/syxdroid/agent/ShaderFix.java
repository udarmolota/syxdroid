package com.udarmolota.syxdroid.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns rectangle samplers into plain 2D samplers, so Songs of Syx's GLSL compiles on GLES.
 *
 * <p>The game samples every sprite texture through {@code sampler2DRect} in pixel coordinates.
 * GLES has no rectangle textures, and {@link SyxTransformer} binds those textures as
 * {@code GL_TEXTURE_2D} instead. The shaders are adjusted to match:
 * <ul>
 *   <li>{@code sampler2DRect} becomes {@code sampler2D} (uniforms and function parameters);</li>
 *   <li>{@code texture(s, p)} on such a sampler becomes
 *       {@code texture(s, (p) / vec2(textureSize(s, 0)))}: pixel to normalised coordinates;</li>
 *   <li>{@code textureSize(s)} gets the level argument a 2D sampler needs: {@code textureSize(s, 0)};</li>
 *   <li>{@code texelFetch(s, p)} likewise gets level 0.</li>
 * </ul>
 * Results are the same for the nearest and linear filtering the game uses, since a texel centre
 * in pixel units ({@code x + 0.5}) maps to the same normalised centre.
 *
 * <p>Pure text work with no GL calls, so it is unit-testable on the desktop.
 */
public final class ShaderFix {
    private static final Pattern RECT_DECL = Pattern.compile("\\bsampler2DRect\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern RECT_TYPE = Pattern.compile("\\bsampler2DRect\\b");

    private ShaderFix() {}

    /** The rewritten source, or the source itself when it uses no rectangle sampler. */
    public static String fix(String src) {
        if (src == null || !src.contains("sampler2DRect")) return src;

        Set<String> rectNames = new LinkedHashSet<>();
        Matcher m = RECT_DECL.matcher(src);
        while (m.find()) rectNames.add(m.group(1));

        String out = rewriteCalls(src, rectNames);
        return RECT_TYPE.matcher(out).replaceAll("sampler2D");
    }

    /** Rewrites texture/textureSize/texelFetch calls whose first argument is a rectangle sampler. */
    private static String rewriteCalls(String src, Set<String> rectNames) {
        StringBuilder out = new StringBuilder(src.length() + 256);
        int i = 0;
        int n = src.length();
        while (i < n) {
            String fn = callAt(src, i);
            if (fn == null) {
                out.append(src.charAt(i));
                i++;
                continue;
            }
            int open = src.indexOf('(', i + fn.length());
            int close = matchingParen(src, open);
            if (close < 0) {
                // Unbalanced: leave the rest as it is; the GLSL compiler will report it.
                out.append(src, i, n);
                break;
            }
            List<String> args = splitArgs(src.substring(open + 1, close));
            // Arguments may hold nested calls on rectangle samplers too (rare, but cheap to handle).
            for (int a = 0; a < args.size(); a++) args.set(a, rewriteCalls(args.get(a), rectNames));
            String sampler = args.isEmpty() ? "" : args.get(0).trim();

            if (!rectNames.contains(sampler)) {
                out.append(fn).append('(').append(String.join(",", args)).append(')');
            } else if (fn.equals("texture") && args.size() >= 2) {
                StringBuilder call = new StringBuilder();
                call.append("texture(").append(args.get(0)).append(", (").append(args.get(1).trim())
                        .append(") / vec2(textureSize(").append(sampler).append(", 0))");
                for (int a = 2; a < args.size(); a++) call.append(',').append(args.get(a)); // bias
                call.append(')');
                out.append(call);
            } else if (fn.equals("textureSize") && args.size() == 1) {
                out.append("textureSize(").append(sampler).append(", 0)");
            } else if (fn.equals("texelFetch") && args.size() == 2) {
                out.append("texelFetch(").append(args.get(0)).append(',').append(args.get(1)).append(", 0)");
            } else {
                out.append(fn).append('(').append(String.join(",", args)).append(')');
            }
            i = close + 1;
        }
        return out.toString();
    }

    /** "texture", "textureSize" or "texelFetch" when a call to it starts at i, else null. */
    private static String callAt(String s, int i) {
        if (i > 0 && isIdent(s.charAt(i - 1))) return null;
        for (String fn : new String[]{"textureSize", "texelFetch", "texture"}) {
            if (!s.startsWith(fn, i)) continue;
            int j = i + fn.length();
            if (j < s.length() && isIdent(s.charAt(j))) continue; // e.g. textureLod
            while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
            if (j < s.length() && s.charAt(j) == '(') return fn;
        }
        return null;
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int matchingParen(String s, int open) {
        if (open < 0) return -1;
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return k;
        }
        return -1;
    }

    /** Top-level comma split, keeping the original spacing of each argument. */
    private static List<String> splitArgs(String inner) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int k = 0; k < inner.length(); k++) {
            char c = inner.charAt(k);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                args.add(inner.substring(start, k));
                start = k + 1;
            }
        }
        if (inner.length() > 0 || !args.isEmpty()) args.add(inner.substring(start));
        return args;
    }
}
