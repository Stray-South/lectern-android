package com.straysouth.lectern.data.repository

/**
 * V2.4 — minimal RSVP tokenizer for plain-text sources (.txt + clipboard).
 *
 * EPUB token source reuses the Readium tokenizer in the TTS pipeline; this
 * file handles the non-EPUB paths only.
 *
 * Per ADR-AND-X: no logging, no persistence, no network. The tokenizer runs
 * in-process and the resulting token list is held only on the RSVP screen's
 * lifecycle. Clipboard content is treated as user-private and is NEVER
 * written to disk, logs, or analytics.
 */
object PlainTextTokenizer {

    /**
     * Splits [input] into a sequence of word tokens. Whitespace separates
     * tokens; punctuation stays attached (so the cadence engine can detect
     * sentence/paragraph boundaries by inspecting the trailing character).
     *
     * Empty input → empty list. Excess whitespace runs collapse.
     */
    fun tokenize(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        return input.split(WHITESPACE).filter { it.isNotEmpty() }
    }

    private val WHITESPACE = Regex("\\s+")
}
