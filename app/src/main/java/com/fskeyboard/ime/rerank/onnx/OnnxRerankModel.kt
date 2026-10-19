package com.fskeyboard.ime.rerank.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.fskeyboard.ime.rerank.ContextCandidateEncoder
import com.fskeyboard.ime.rerank.ModelTensor
import com.fskeyboard.ime.rerank.NeuralRerankInput
import com.fskeyboard.ime.rerank.OnnxContract
import com.fskeyboard.ime.rerank.RerankEncoder
import com.fskeyboard.ime.rerank.RerankModel
import com.fskeyboard.ime.rerank.readScoreVector
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Loads an ONNX model and runs one scoring pass. [load] creates the session
 * once, on the rerank thread, from the asset copied by [OnnxModelStore].
 * [score] opens it only when that load has not finished or the file appeared
 * later. CPU only, one thread: a small model should stay inside the 50ms
 * budget. Swap [encoder] for character features or raw strings, or replace
 * this class with another [RerankModel].
 *
 * Expected outputs are named [OnnxContract.SCORES] with shape [N] or [1, N].
 */
internal class OnnxRerankModel(
    private val store: OnnxModelStore,
    private val encoder: RerankEncoder = ContextCandidateEncoder(),
    private val outputName: String = OnnxContract.SCORES,
) : RerankModel {
    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var failedStamp: Long = 0L

    @Volatile
    private var loadAttempted = false

    private val loadLock = Any()

    override val available: Boolean
        get() {
            if (session != null) return true
            if (!store.hasModel()) return false
            if (failure == null) return true
            val stamp = store.stamp()
            return stamp != 0L && stamp != failedStamp
        }

    override fun load() {
        if (session != null || loadAttempted) return
        synchronized(loadLock) {
            if (session != null || loadAttempted) return
            loadAttempted = true
            if (!store.hasModel()) return
            openSession()
        }
    }

    override fun score(input: NeuralRerankInput): FloatArray {
        val current = openSession()
        val specs = encoder.encode(input)
        val env = OrtEnvironment.getEnvironment()
        val feeds = LinkedHashMap<String, OnnxTensor>(specs.size)
        try {
            for (spec in specs) {
                feeds[spec.name] = tensor(env, spec)
            }
            current.run(feeds).use { result ->
                val named = result.get(outputName)
                if (!named.isPresent) error("missing output $outputName")
                return readScoreVector(named.get().value)
            }
        } finally {
            feeds.values.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        synchronized(loadLock) {
            session?.close()
            session = null
        }
    }

    private fun openSession(): OrtSession {
        session?.let { return it }
        synchronized(loadLock) {
            session?.let { return it }
            val file = store.resolve() ?: error("rerank model missing")
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                val created = OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
                failure = null
                session = created
                return created
            } catch (error: Throwable) {
                failedStamp = file.lastModified()
                failure = error
                throw error
            } finally {
                options.close()
            }
        }
    }

    private fun tensor(env: OrtEnvironment, spec: ModelTensor): OnnxTensor {
        val expected = spec.shape.fold(1L) { product, dim -> product * dim }
        return when (spec) {
            is ModelTensor.Floats -> {
                require(expected == spec.values.size.toLong()) { spec.name }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(spec.values), spec.shape)
            }
            is ModelTensor.Longs -> {
                require(expected == spec.values.size.toLong()) { spec.name }
                OnnxTensor.createTensor(env, LongBuffer.wrap(spec.values), spec.shape)
            }
            is ModelTensor.Strings -> {
                require(expected == spec.values.size.toLong()) { spec.name }
                OnnxTensor.createTensor(env, spec.values, spec.shape)
            }
        }
    }
}
