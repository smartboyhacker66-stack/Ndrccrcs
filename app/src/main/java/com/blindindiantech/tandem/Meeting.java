package com.blindindiantech.tandem;

import java.security.SecureRandom;

/** Meeting ID helpers: 7 digits followed by 1 uppercase letter, e.g. 1234567A. */
final class Meeting {
    private static final String[] NATO = {
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India",
            "Juliett", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo",
            "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"
    };
    private static final SecureRandom RANDOM = new SecureRandom();

    private Meeting() {}

    static String generate() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) sb.append(RANDOM.nextInt(10));
        sb.append((char) ('A' + RANDOM.nextInt(26)));
        return sb.toString();
    }

    static boolean isValid(String id) {
        return id != null && id.matches("\\d{7}[A-Z]");
    }

    /** JAWS-style reading: every character separately, letter with its phonetic word. */
    static String spoken(String id) {
        if (!isValid(id)) return id == null ? "" : id;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            sb.append(id.charAt(i)).append(", ");
        }
        char letter = id.charAt(7);
        sb.append(letter).append(", as in ").append(NATO[letter - 'A']);
        return sb.toString();
    }
}
