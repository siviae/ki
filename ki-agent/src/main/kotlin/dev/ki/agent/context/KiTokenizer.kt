package dev.ki.agent.context

import ai.koog.prompt.Prompt
import ai.koog.prompt.tokenizer.OnDemandTokenizer
import ai.koog.prompt.tokenizer.PromptTokenizer
import ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer

/**
 * Rough token estimator for the **pre-send budget trigger** (M6). Backed by koog's
 * regex tokenizer, which undercounts real BPE — callers apply a safety factor. This
 * drives compaction decisions only; the token counts shown to the user come from the
 * LLM's own `ResponseMetaInfo` (see `KiAgent` usage capture), not from here.
 */
class KiTokenizer(
    private val delegate: PromptTokenizer = OnDemandTokenizer(SimpleRegexBasedTokenizer()),
) {
    fun estimate(prompt: Prompt): Int = delegate.tokenCountFor(prompt)
}
