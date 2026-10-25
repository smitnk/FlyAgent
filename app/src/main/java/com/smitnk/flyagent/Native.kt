package com.smitnk.flyagent

import android.content.Context
import java.io.File

/** JNI entry points (app/src/main/cpp/jni_bridge.cpp). */
object NativeBrain {
    init { System.loadLibrary("flyagent") }
    @JvmStatic external fun load(path: String): Long
    @JvmStatic external fun free(h: Long)
    @JvmStatic external fun source(h: Long): String
    @JvmStatic external fun edges(h: Long): Long
    @JvmStatic external fun ids(h: Long): LongArray
    @JvmStatic external fun simulate(h: Long, exc: IntArray, hz: Double, sil: IntArray, ms: Double, trials: Int, seed: Long): LongArray
    @JvmStatic external fun cancel()
}

object NativeLlm {
    init { System.loadLibrary("flyagent") }
    @JvmStatic external fun loadBackends(libDir: String): String
    @JvmStatic external fun stats(h: Long): String
    @JvmStatic external fun load(path: String, nCtx: Int, threads: Int): Long
    fun interface TokenListener { fun onToken(utf8: ByteArray): Boolean }
    @JvmStatic external fun chat(h: Long, roles: Array<String>, texts: Array<String>, maxTokens: Int, listener: TokenListener?): ByteArray
    @JvmStatic external fun free(h: Long)
}

class ConnectomeBrain private constructor(private val h: Long, override val label: String) : BrainEngine {
    private val ids = NativeBrain.ids(h)
    private val index = HashMap<Long, Int>(ids.size * 2).apply { ids.forEachIndexed { i, id -> put(id, i) } }
    override val source = NativeBrain.source(h)
    override val neurons = ids.size
    override val edges = NativeBrain.edges(h)
    override fun has(id: Long) = id in index

    override fun simulate(excite: List<Long>, hz: Double, silence: List<Long>, ms: Double, trials: Int): SimResult {
        val exc = excite.mapNotNull { index[it] }.toIntArray()
        val out = NativeBrain.simulate(h, exc, hz, silence.mapNotNull { index[it] }.toIntArray(), ms, trials, 1234)
        val rates = HashMap<Long, Double>()
        for (i in 2 until out.size) if (out[i] > 0) rates[ids[i - 2]] = out[i] / (trials * ms / 1000.0)
        return SimResult(rates, out[0], out[1], excite.filter { it in index }, excite.filter { it !in index })
    }

    fun release() = NativeBrain.free(h)

    companion object {
        const val SUBSET_ASSET = "fly783_subset.bin"
        const val FULL_URL = "https://github.com/smitnk/FlyAgent/releases/download/connectome-v783/fly783_full.bin.gz"
        fun fullFile(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "fly783_full.bin")
        @Volatile private var cached: ConnectomeBrain? = null

        /** Full brain if downloaded, else the bundled subset. Loads once; ~150 MB RAM for the full brain. */
        @Synchronized fun get(ctx: Context): ConnectomeBrain {
            cached?.let { return it }
            val full = fullFile(ctx)
            val b = if (full.exists()) ConnectomeBrain(NativeBrain.load(full.absolutePath), "full brain") else {
                val f = File(ctx.filesDir, SUBSET_ASSET)
                if (!f.exists()) ctx.assets.open(SUBSET_ASSET).use { i -> f.outputStream().use { i.copyTo(it) } }
                ConnectomeBrain(NativeBrain.load(f.absolutePath), "subset")
            }
            cached = b; return b
        }
        @Synchronized fun reset() { cached?.release(); cached = null }
    }
}
