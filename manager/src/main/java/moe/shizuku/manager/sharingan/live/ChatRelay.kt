package moe.shizuku.manager.sharingan.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.shizuku.manager.agent.AgentOrchestrator
import moe.shizuku.manager.memory.MemoryRepository
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.1.0-alpha — CHAT RELAY: everything the live session hears, decides and
 * executes lands in the aMiNo CHAT (the user's verbatim complaint was «لا
 * ينفذ ولا يرسل مايران للشات» — nothing executed, nothing appeared in the
 * chat). Rows are appended to the CURRENT conversation via MemoryRepository
 * (the same SQLite store the typed chat uses) and the orchestrator state is
 * refreshed so the AgentHomeActivity list updates live.
 *
 * Expert-audit hardened:
 *  - the conversation id is CAPTURED ONCE PER TURN ([beginTurn]) — a typed
 *    chat conversation switch mid-turn can no longer fragment the live
 *    transcript across two conversations (#35);
 *  - refresh() is DEBOUNCED (300ms coalescing) — one 5-row turn causes ONE
 *    full chat reload instead of five (#36);
 *  - a single-thread executor keeps row ORDER deterministic.
 */
object ChatRelay {

    private const val TAG = "ChatRelay"
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ChatRelay").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshPending = AtomicBoolean(false)

    /** Conversation pinned for the CURRENT live turn (set by LiveSession). */
    @Volatile private var turnConvId: Long = 0

    fun beginTurn(context: Context) {
        io.execute { turnConvId = resolveConversation(context) }
    }

    fun endTurn() {
        turnConvId = 0
    }

    private fun resolveConversation(context: Context): Long {
        var convId = AgentOrchestrator.state.value.conversationId
        if (convId <= 0) {
            AgentOrchestrator.init(context)
            AgentOrchestrator.loadLatestOrNew(context)
            convId = AgentOrchestrator.state.value.conversationId
        }
        return convId
    }

    /** Debounced single refresh — coalesces a burst of rows into one reload. */
    private fun scheduleRefresh(context: Context) {
        if (!refreshPending.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            refreshPending.set(false)
            runCatching { AgentOrchestrator.refresh(context.applicationContext) }
                .onFailure { Log.w(TAG, "refresh failed", it) }
        }, 300)
    }

    /** 🎤 the corrected voice command (with the raw ASR text for honesty). */
    fun user(context: Context, corrected: String, rawVoice: String?, localFix: Boolean) {
        io.execute {
            runCatching {
                val convId = if (turnConvId > 0) turnConvId else resolveConversation(context)
                if (convId <= 0) return@execute
                val shown = buildString {
                    append(corrected)
                    appendLine()
                    appendLine(if (localFix) "🎤 (بصوت — تصحيح محلي دون شبكة)" else "🎤 (بصوت)")
                    if (!rawVoice.isNullOrBlank() && rawVoice.trim() != corrected.trim()) {
                        val orig = rawVoice.take(120)
                        appendLine("النص الأصلي المسجّل: «$orig${if (rawVoice.length > 120) "…" else ""}»")
                    }
                }
                MemoryRepository.addMessage(context, convId, "user", shown)
                scheduleRefresh(context)
            }.onFailure { Log.w(TAG, "relay user failed", it) }
        }
    }

    /** The eye's spoken/decided reply. */
    fun assistant(context: Context, text: String) {
        io.execute {
            runCatching {
                val convId = if (turnConvId > 0) turnConvId else resolveConversation(context)
                if (convId <= 0) return@execute
                MemoryRepository.addMessage(context, convId, "assistant", text.take(4000))
                scheduleRefresh(context)
            }.onFailure { Log.w(TAG, "relay assistant failed", it) }
        }
    }

    /** A real executed action row (tool semantics, honest ok/fail). */
    fun tool(context: Context, name: String, ok: Boolean, output: String) {
        io.execute {
            runCatching {
                val convId = if (turnConvId > 0) turnConvId else resolveConversation(context)
                if (convId <= 0) return@execute
                MemoryRepository.addMessage(context, convId, "tool",
                    output.take(1500).ifBlank { "(بدون مخرجات)" }, name, ok)
                scheduleRefresh(context)
            }.onFailure { Log.w(TAG, "relay tool failed", it) }
        }
    }

    /** Live lifecycle notes (started/stopped) — assistant rows with an icon. */
    fun note(context: Context, text: String) = assistant(context, text)
}
