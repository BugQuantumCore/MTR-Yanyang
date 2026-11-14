package org.mtr.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class PidsLanguageTextTest {

	@Test
	void separatorOnlyMessagesProvideOneEmptyLanguage() {
		assertArrayEquals(new String[]{""}, PidsLanguageText.split("|"));
		assertArrayEquals(new String[]{""}, PidsLanguageText.split("|||"));
		final String[] languages = PidsLanguageText.split("||");
		assertEquals("", languages[17 % languages.length]);
	}

	@Test
	void keepsNormalRotationAndTrailingSeparatorSemantics() {
		assertArrayEquals(new String[]{"晏阳", "Yanyang"}, PidsLanguageText.split("晏阳|Yanyang||"));
		assertArrayEquals(new String[]{"", "A", "", "B"}, PidsLanguageText.split("|A||B|"));
		assertArrayEquals(new String[]{"A"}, PidsLanguageText.split("A"));
	}

	@Test
	void emptyTextAlreadyHasOneLanguage() {
		assertArrayEquals(new String[]{""}, PidsLanguageText.split(""));
	}
}
