package com.delta.system.support;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Repairs UTF-8 text that was decoded as Windows-1252 and then stored again as UTF-8.
 * This happens when an ad-hoc MySQL client uses latin1 while inserting Chinese metadata.
 */
public final class MojibakeTextRepair {
    private static final Map<Character, Integer> WINDOWS_1252 = new HashMap<>();

    static {
        for (int value = 0; value <= 0xFF; value++) {
            WINDOWS_1252.put((char) value, value);
        }
        String punctuation = "\u20AC\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u017D\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u017E\u0178";
        int[] bytes = {0x80, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A,
                0x8B, 0x8C, 0x8E, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97,
                0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9E, 0x9F};
        for (int index = 0; index < punctuation.length(); index++) {
            WINDOWS_1252.put(punctuation.charAt(index), bytes[index]);
        }
    }

    private MojibakeTextRepair() {
    }

    public static String repair(String value) {
        if (value == null || value.isEmpty()) return value;
        boolean hasNonAscii = false;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) {
                hasNonAscii = true;
                break;
            }
        }
        if (!hasNonAscii) return value;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            Integer mapped = WINDOWS_1252.get(value.charAt(index));
            if (mapped == null) return value;
            bytes.write(mapped);
        }

        String decoded;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoded = decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException ignored) {
            return value;
        }
        if (decoded.equals(value) || !containsHan(decoded) || decoded.indexOf('\uFFFD') >= 0) return value;
        return decoded;
    }

    private static boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
