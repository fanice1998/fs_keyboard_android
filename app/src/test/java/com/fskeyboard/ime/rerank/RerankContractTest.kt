package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CandidateSource
import com.fskeyboard.ime.candidate.SelectionPolicy
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.engine.KeyPolicy
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.script.OutputScript
import org.junit.Assert.assertEquals
import org.junit.Test

class RerankContractTest {
    @Test
    fun boostReordersWithoutRewritingTheEngineIndex() {
        val first = Candidate("台灣", engineIndex = 0)
        val second = Candidate("臺灣", engineIndex = 1)
        val reranked = ScoreBoostReranker { candidate ->
            if (candidate.engineIndex == 1) 5 else 0
        }.rerank(RerankRequest("taiwan", "台灣", listOf(first, second)))

        assertEquals(1, reranked[0].engineIndex)
        assertEquals("臺灣", reranked[0].text)
        assertEquals(0, reranked[1].engineIndex)
    }

    @Test
    fun pipelineConvertsOnlySyntheticCandidates() {
        val pipeline = CandidatePipeline(
            reranker = CandidateReranker { request ->
                request.candidates + Candidate("后面", engineIndex = -1)
            },
            script = OutputScript { text -> if (text == "后面") "後面" else text },
        )
        val presented = pipeline.present(
            EngineSnapshot(candidates = listOf(Candidate("台灣", engineIndex = 3))),
        )

        assertEquals("台灣", presented[0].text)
        assertEquals(3, presented[0].engineIndex)
        assertEquals(CandidateSource.RIME, presented[0].source)
        assertEquals("後面", presented[1].text)
        assertEquals(CandidateSource.SYNTHETIC, presented[1].source)
    }

    @Test
    fun selectionKeepsRimeLearningOnOriginalIndex() {
        val decision = SelectionPolicy.decide(Candidate("台灣", engineIndex = 4))
        val synthetic = SelectionPolicy.decide(Candidate("自訂", engineIndex = -1))

        assertEquals(SelectionPolicy.Decision.SelectEngine(4), decision)
        assertEquals(SelectionPolicy.Decision.CommitSynthetic("自訂"), synthetic)
    }

    @Test
    fun spaceAcceptsThePresentedCandidateRatherThanRimesOrder() {
        val presented = listOf(Candidate("後", engineIndex = 2), Candidate("候", engineIndex = 0))
        assertEquals(
            KeyPolicy.Space.AcceptFirst,
            KeyPolicy.space(KeyboardMode.ZHUYIN, presented, composing = true),
        )
        assertEquals(
            KeyPolicy.Space.SendToRime,
            KeyPolicy.space(KeyboardMode.ZHUYIN, emptyList(), composing = true),
        )
        assertEquals(
            KeyPolicy.Space.CommitSpace,
            KeyPolicy.space(KeyboardMode.LATIN, presented, composing = true),
        )
    }
}
