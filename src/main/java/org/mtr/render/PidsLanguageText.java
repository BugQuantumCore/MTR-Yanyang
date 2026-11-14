package org.mtr.render;

import java.util.regex.Pattern;

/** Keeps the existing language split while ensuring a valid rotation modulus. */
final class PidsLanguageText {

	private static final Pattern PIPE_SPLIT = Pattern.compile("\\|");

	private PidsLanguageText() {
	}

	static String[] split(String text) {
		final String[] languages = PIPE_SPLIT.split(text);
		return languages.length == 0 ? new String[]{""} : languages;
	}
}
