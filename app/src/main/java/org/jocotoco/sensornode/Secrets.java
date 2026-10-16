package org.jocotoco.sensornode;

import android.content.Context;
import java.security.SecureRandom;

final class Secrets {
    private Secrets() {}

    static synchronized String token(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
        String current = prefs.getString("token", null);
        if (current != null && current.length() == 48) return current;
        byte[] data = new byte[24];
        new SecureRandom().nextBytes(data);
        StringBuilder out = new StringBuilder();
        final char[] hex = "0123456789abcdef".toCharArray();
        for (byte b : data) { int i = b & 255; out.append(hex[i >>> 4]).append(hex[i & 15]); }
        current = out.toString();
        if (!prefs.edit().putString("token", current).commit()) {
            throw new IllegalStateException("No se pudo guardar el token");
        }
        return current;
    }

    static boolean tokenEquals(Context context, String supplied) {
        String expected = token(context);
        if (supplied == null || supplied.length() != expected.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) diff |= expected.charAt(i) ^ supplied.charAt(i);
        return diff == 0;
    }
}
