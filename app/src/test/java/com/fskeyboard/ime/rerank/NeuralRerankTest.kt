package com.fskeyboard.ime.rerank

import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.engine.EngineSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NeuralRerankTest {
    @Test
    fun scoresReorderOnlyTheHeadAndKeepEngineIndexes() {
        val candidates = words("一", "二", "三", "四", "五", "尾")
        val model = FakeModel(scores = floatArrayOf(0.1f, 0.9f, 0.4f, 0.2f, 0.3f))
        val reranker = reranker(model, window = 5)
        val ranked = reranker.rerank(request(candidates))

        assertSame(candidates[1], ranked[0])
        assertSame(candidates[2], ranked[1])
        assertSame(candidates[4], ranked[2])
        assertSame(candidates[3], ranked[3])
        assertSame(candidates[0], ranked[4])
        assertSame(candidates[5], ranked[5])
        assertEquals(1, ranked[0].engineIndex)
        assertEquals(null, reranker.lastTrace?.fallback)
        reranker.close()
    }

    @Test
    fun slowResultFallsBackToRimeOrder() {
        val candidates = words("一", "二", "三")
        var ticks = 0L
        val reranker = reranker(
            model = FakeModel(scores = floatArrayOf(1f, 0f, 0f)),
            clock = NanoClock {
                ticks += 60
                ticks * 1_000_000L
            },
        )
        val ranked = reranker.rerank(request(candidates))

        assertEquals(candidates, ranked)
        assertEquals(RerankFallback.TOO_SLOW, reranker.lastTrace?.fallback)
        reranker.close()
    }

    @Test
    fun disabledAndMissingModelDoNotScore() {
        val candidates = words("一", "二")
        val disabledModel = FakeModel(scores = floatArrayOf(1f, 0f))
        val disabled = reranker(disabledModel, enabled = { false })
        assertEquals(candidates, disabled.rerank(request(candidates)))
        assertEquals(0, disabledModel.calls)
        assertEquals(RerankFallback.DISABLED, disabled.lastTrace?.fallback)
        disabled.close()

        val missing = FakeModel(scores = floatArrayOf(1f, 0f), ready = false)
        val reranker = reranker(missing)
        assertEquals(candidates, reranker.rerank(request(candidates)))
        assertEquals(0, missing.calls)
        assertEquals(RerankFallback.UNAVAILABLE, reranker.lastTrace?.fallback)
        reranker.close()
    }

    @Test
    fun badScoresAndModelErrorsKeepTheOriginalList() {
        val candidates = words("一", "二", "三")
        val nan = reranker(FakeModel(scores = floatArrayOf(1f, Float.NaN, 0f)))
        assertEquals(candidates, nan.rerank(request(candidates)))
        assertEquals(RerankFallback.BAD_SCORES, nan.lastTrace?.fallback)
        nan.close()

        val broken = reranker(FakeModel(onScore = { error("boom") }))
        assertEquals(candidates, broken.rerank(request(candidates)))
        assertEquals(RerankFallback.FAILED, broken.lastTrace?.fallback)
        broken.close()
    }

    @Test
    fun modelSeesTheContextTailAndAtMostNineCandidates() {
        val candidates = words(*(1..12).map { it.toString() }.toTypedArray())
        val model = FakeModel(scores = FloatArray(9) { index -> index.toFloat() })
        val reranker = reranker(model)
        val ranked = reranker.rerank(
            RerankRequest(
                preedit = "ㄋㄧ",
                preview = "",
                candidates = candidates,
                contextBefore = "甲".repeat(40) + "乙",
            ),
        )

        assertEquals(9, model.seen?.candidates?.size)
        assertEquals(20, model.seen?.contextBefore?.length)
        assertTrue(model.seen?.contextBefore?.endsWith("乙") == true)
        assertEquals("ㄋㄧ", model.seen?.preedit)
        assertSame(candidates[8], ranked[0])
        assertSame(candidates[0], ranked[8])
        assertSame(candidates[9], ranked[9])
        assertSame(candidates[11], ranked[11])
        reranker.close()
    }

    @Test
    fun timeoutFallsBackToTheOriginalOrder() {
        val model = FakeModel(onScore = {
            Thread.sleep(500)
            floatArrayOf(1f, 0f, 0f)
        })
        val reranker = NeuralCandidateReranker(
            model = model,
            config = NeuralRerankConfig(windowSize = 5, budgetMillis = 40),
        )
        val candidates = words("一", "二", "三")
        val started = System.nanoTime()
        assertEquals(candidates, reranker.rerank(request(candidates)))
        val waited = (System.nanoTime() - started) / 1_000_000L
        assertTrue(waited < 400)
        assertEquals(RerankFallback.TOO_SLOW, reranker.lastTrace?.fallback)
        reranker.close()
    }

    @Test
    fun inferenceTimeIsSeparateFromTheWait() {
        val stamps = ArrayDeque(listOf(0L, 5L, 10L, 20L))
        val model = FakeModel(scores = floatArrayOf(0f, 1f, 0f))
        val reranker = NeuralCandidateReranker(
            model = model,
            config = NeuralRerankConfig(windowSize = 5, budgetMillis = RerankLimits.BUDGET_MILLIS),
            clock = NanoClock { stamps.removeFirst() * 1_000_000L },
            executor = inlineExecutor(),
        )
        val candidates = words("一", "二", "三")
        val ranked = reranker.rerank(request(candidates))

        assertSame(candidates[1], ranked[0])
        assertEquals(5L, reranker.lastTrace?.inferMillis)
        assertEquals(20L, reranker.lastTrace?.elapsedMillis)
        assertEquals(null, reranker.lastTrace?.fallback)
        reranker.close()
    }

    @Test
    fun asyncRerankDoesNotBlockTheCallerPastTheBudget() {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val model = FakeModel(onScore = {
            started.countDown()
            Thread.sleep(400)
            floatArrayOf(0f, 1f, 0f)
        })
        val reranker = NeuralCandidateReranker(
            model = model,
            config = NeuralRerankConfig(windowSize = 5, budgetMillis = 50),
        )
        val candidates = words("一", "二", "三")
        var trace: RerankTrace? = null
        val submitStart = System.nanoTime()
        reranker.rerankAsync(request(candidates)) { result ->
            trace = result
            finished.countDown()
        }
        val returned = (System.nanoTime() - submitStart) / 1_000_000L

        assertTrue(returned < 200)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(null, trace)
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(RerankFallback.TOO_SLOW, trace?.fallback)
        assertSame(candidates[0], trace?.candidates?.get(0))
        assertSame(candidates[1], trace?.candidates?.get(1))
        assertTrue((trace?.elapsedMillis ?: 0) > 50)
        assertTrue((trace?.inferMillis ?: 0) > 50)
        reranker.close()
    }

    @Test
    fun aSingleCandidateSkipsInference() {
        val model = FakeModel(scores = floatArrayOf(1f))
        val reranker = reranker(model)
        val only = words("一")
        assertSame(only[0], reranker.rerank(request(only))[0])
        assertEquals(0, model.calls)
        reranker.close()
    }

    @Test
    fun aLateResultIsNotAppliedAfterTheNextKey() {
        assertTrue(acceptRerankUpdate(ticket = 2, current = 2, fallback = null))
        assertTrue(!acceptRerankUpdate(ticket = 2, current = 3, fallback = null))
        assertTrue(!acceptRerankUpdate(ticket = 2, current = 2, fallback = RerankFallback.TOO_SLOW))
    }

    @Test
    fun aRunningInferenceMakesTheNextCallFallBack() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val model = FakeModel(onScore = {
            started.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            floatArrayOf(0f, 1f, 0f)
        })
        val reranker = NeuralCandidateReranker(
            model = model,
            config = NeuralRerankConfig(windowSize = 5, budgetMillis = 1_000),
        )
        val candidates = words("一", "二", "三")
        val first = Thread { reranker.rerank(request(candidates)) }
        first.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(candidates, reranker.rerank(request(candidates)))
        assertEquals(RerankFallback.BUSY, reranker.lastTrace?.fallback)
        release.countDown()
        first.join(2_000)
        assertEquals(null, reranker.lastTrace?.fallback)
        assertSame(candidates[1], reranker.lastTrace?.candidates?.get(0))
        reranker.close()
    }

    @Test
    fun encoderShapeIsStableAndIncludesRank() {
        val encoder = ContextCandidateEncoder()
        val input = NeuralRerankInput(
            contextBefore = "你好世界",
            preedit = "ㄋ",
            candidates = listOf(Candidate("世界", engineIndex = 3), Candidate("甲", engineIndex = 4)),
        )
        val first = encoder.encode(input)
        val second = encoder.encode(input)
        val features = first[0] as ModelTensor.Floats
        val mask = first[1] as ModelTensor.Floats

        assertEquals(OnnxContract.FEATURES, features.name)
        assertArrayEquals(longArrayOf(1, 2, OnnxContract.FEATURE_DIM.toLong()), features.shape)
        assertEquals(2 * OnnxContract.FEATURE_DIM, features.values.size)
        assertEquals(0f, features.values[0], 0f)
        assertEquals(1f / RerankLimits.MAX_WINDOW, features.values[OnnxContract.FEATURE_DIM], 0.0001f)
        assertEquals(1f, features.values[4], 0f)
        assertEquals(0f, features.values[OnnxContract.FEATURE_DIM + 4], 0f)
        assertArrayEquals(floatArrayOf(1f, 1f), mask.values, 0f)
        assertTrue(features.values.contentEquals((second[0] as ModelTensor.Floats).values))
    }

    @Test
    fun scoreVectorUnwrapsABatchDimension() {
        assertArrayEquals(floatArrayOf(0.2f, 0.8f), readScoreVector(floatArrayOf(0.2f, 0.8f)), 0f)
        assertArrayEquals(
            floatArrayOf(0.2f, 0.8f),
            readScoreVector(arrayOf(floatArrayOf(0.2f, 0.8f))),
            0f,
        )
    }

    @Test
    fun stringEncoderCapsContextAndCandidates() {
        val encoder = DirectStringEncoder()
        val candidates = (1..12).map { Candidate("詞$it", engineIndex = it) }
        val tensors = encoder.encode(
            NeuralRerankInput(
                contextBefore = "甲".repeat(30) + "尾",
                preedit = "ㄋ",
                candidates = candidates,
            ),
        )
        val context = tensors[0] as ModelTensor.Strings
        val texts = tensors[1] as ModelTensor.Strings

        assertEquals(OnnxContract.CONTEXT, context.name)
        assertArrayEquals(longArrayOf(1), context.shape)
        assertEquals(1, context.values.size)
        assertEquals(20, context.values[0].length)
        assertTrue(context.values[0].endsWith("尾"))
        assertEquals(OnnxContract.CANDIDATES, texts.name)
        assertArrayEquals(longArrayOf(9), texts.shape)
        assertEquals("詞1", texts.values[0])
        assertEquals("詞9", texts.values[8])
        assertEquals(emptyList<ModelTensor>(), encoder.encode(
            NeuralRerankInput(contextBefore = "甲", preedit = "", candidates = emptyList()),
        ))
    }

    @Test
    fun stringRankerPermutesInstancesAndLeavesTheTail() {
        val head = listOf("同", "高", "同")
        val tail = listOf("尾")
        val input = head + tail
        val model = FakeModel(scores = floatArrayOf(0.2f, 0.9f, 0.1f, 0f))
        val ranker = stringRanker(model, window = 5)
        val ranked = ranker.rerank("甲".repeat(25) + "乙", input, preedit = "ㄍ")

        assertSame(input[1], ranked[0])
        assertSame(input[0], ranked[1])
        assertSame(input[2], ranked[2])
        assertSame(input[3], ranked[3])
        assertEquals(20, model.seen?.contextBefore?.length)
        assertTrue(model.seen?.contextBefore?.endsWith("乙") == true)
        assertEquals("ㄍ", model.seen?.preedit)
        assertEquals(null, ranker.lastTrace?.fallback)
        ranker.close()
    }

    @Test
    fun stringRankerReordersOnlyNineAndKeepsEngineIndexesOnTheImePath() {
        val texts = (0 until 12).map { "字$it" }
        val model = FakeModel(scores = FloatArray(9) { index -> index.toFloat() })
        val ranker = stringRanker(model)
        val ranked = ranker.rerank("上文", texts)

        assertEquals(9, model.seen?.candidates?.size)
        assertSame(texts[8], ranked[0])
        assertSame(texts[0], ranked[8])
        assertSame(texts[9], ranked[9])
        assertSame(texts[11], ranked[11])

        val candidates = words("一", "二", "三")
        val imeRanked = ranker.rerank(request(candidates))
        assertSame(candidates[2], imeRanked[0])
        assertEquals(2, imeRanked[0].engineIndex)
        ranker.close()
    }

    @Test
    fun stringRankerTimeoutAndLoadFailureKeepTheOriginalOrder() {
        var ticks = 0L
        val input = listOf("甲", "乙", "丙")
        val slow = OnnxCandidateRanker(
            model = FakeModel(scores = floatArrayOf(0f, 1f, 0f)),
            clock = NanoClock {
                ticks += 60
                ticks * 1_000_000L
            },
            executor = inlineExecutor(),
        )
        val kept = slow.rerank("上文", input)
        assertSame(input[0], kept[0])
        assertSame(input[1], kept[1])
        assertSame(input[2], kept[2])
        assertEquals(RerankFallback.TOO_SLOW, slow.lastTrace?.fallback)
        slow.close()

        val model = FakeModel(scores = floatArrayOf(0f, 1f), failLoad = true)
        val messages = mutableListOf<String>()
        val ranker = OnnxCandidateRanker(
            model = model,
            executor = inlineExecutor(),
            logger = RerankLogger { messages += it },
        )
        ranker.load()
        ranker.load()
        assertEquals(1, model.loads)
        assertEquals(listOf("rerank load failed IllegalStateException"), messages)
        val originals = listOf("甲", "乙")
        val ranked = ranker.rerank("上文", originals)
        assertSame(originals[0], ranked[0])
        assertSame(originals[1], ranked[1])
        assertEquals(0, model.calls)
        assertEquals(RerankFallback.UNAVAILABLE, ranker.lastTrace?.fallback)
        ranker.close()
    }

    @Test
    fun pipelineForwardsContextBeforeTheCursor() {
        var seen = ""
        val pipeline = CandidatePipeline(
            reranker = CandidateReranker { request ->
                seen = request.contextBefore
                request.candidates
            },
        )
        pipeline.present(
            EngineSnapshot(candidates = listOf(Candidate("你", engineIndex = 0))),
            "上文",
        )
        assertEquals("上文", seen)
        pipeline.close()
    }

    private fun stringRanker(
        model: FakeModel,
        window: Int = RerankLimits.DEFAULT_WINDOW,
    ): OnnxCandidateRanker {
        return OnnxCandidateRanker(
            model = model,
            config = NeuralRerankConfig(windowSize = window, budgetMillis = RerankLimits.BUDGET_MILLIS),
            clock = NanoClock { 0L },
            executor = inlineExecutor(),
        )
    }

    private fun reranker(
        model: FakeModel,
        window: Int = RerankLimits.DEFAULT_WINDOW,
        enabled: () -> Boolean = { true },
        clock: NanoClock = NanoClock { 0L },
    ): NeuralCandidateReranker {
        return NeuralCandidateReranker(
            model = model,
            enabled = enabled,
            config = NeuralRerankConfig(windowSize = window, budgetMillis = RerankLimits.BUDGET_MILLIS),
            clock = clock,
            executor = inlineExecutor(),
        )
    }

    private fun request(candidates: List<Candidate>): RerankRequest {
        return RerankRequest(preedit = "", preview = "", candidates = candidates, contextBefore = "前文")
    }

    private fun words(vararg text: String): List<Candidate> {
        return text.mapIndexed { index, word -> Candidate(word, engineIndex = index) }
    }

    private class FakeModel(
        private val scores: FloatArray = floatArrayOf(),
        var ready: Boolean = true,
        var failLoad: Boolean = false,
        private val onScore: ((NeuralRerankInput) -> FloatArray)? = null,
    ) : RerankModel {
        var calls = 0
        var loads = 0
        var seen: NeuralRerankInput? = null
        override val available: Boolean
            get() = ready

        override fun load() {
            loads += 1
            if (failLoad) {
                ready = false
                error("disk")
            }
        }

        override fun score(input: NeuralRerankInput): FloatArray {
            calls += 1
            seen = input
            return onScore?.invoke(input) ?: scores
        }

        override fun close() = Unit
    }

    private fun inlineExecutor(): ExecutorService {
        return object : AbstractExecutorService() {
            private val stopped = AtomicBoolean(false)
            override fun execute(command: Runnable) = command.run()
            override fun shutdown() {
                stopped.set(true)
            }
            override fun shutdownNow(): MutableList<Runnable> {
                stopped.set(true)
                return mutableListOf()
            }
            override fun isShutdown(): Boolean = stopped.get()
            override fun isTerminated(): Boolean = stopped.get()
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        }
    }
}
