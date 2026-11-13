package moe.shizuku.manager.terminal

/**
 * aMiNo r1384 — Integrated Terminal Environment: shared models.
 *
 * The terminal is a REAL execution engine, not a display mock:
 *  - persistent sessions (one long-lived `sh` per session — cwd/env state survives)
 *  - separate stdout / stderr capture per command
 *  - real exit codes via an in-stream marker protocol
 *  - multiple independent sessions, user sessions kept separate from agent sessions
 *
 * Backends are only ever REPORTED as available when actually probed — we never
 * invent an environment that is not there (no fake root, no fake Termux).
 */
enum class TermBackend(val id: String, val title: String, val startDir: String) {
    /** The app's own sandbox: uid of aMiNo, toybox tools, always exists. */
    LOCAL_APP("local", "Local app shell", ""),

    /** The ADB shell identity (uid 2000) through the wireless debugging channel. */
    ADB_SHELL("adb", "ADB shell", "/data/local/tmp"),

    /** Root — only reported/usable when `su` REALLY runs and returns uid 0. */
    ROOT("root", "Root shell", "/data/local/tmp"),

    /** Termux app — detection + RUN_COMMAND handoff; never claimed without the app. */
    TERMUX("termux", "Termux", ""),

    /**
     * A real Debian 12 user-space through PRoot (r1385), executed by the aMiNo
     * service (shell uid) from /data/local/tmp/amino-linux — installed on demand
     * via LinuxEnvManager, NEVER claimed before a real probe succeeds.
     * PRoot's -0 fakes uid 0 INSIDE the container only — no real root, ever.
     */
    LINUX_USERSPACE("linux", "Linux (Debian 12 · PRoot)", "/root")
}

/** One environment as reported by honest discovery. */
data class EnvironmentInfo(
    val backend: TermBackend,
    val available: Boolean,
    val status: String,        // human-readable REAL state
    val path: String,          // where commands run from / home dir
    val identity: String,      // uid / permission level actually held
    val notes: String          // limits, package manager availability...
)

/** One line of terminal scrollback. */
data class TermLine(
    val seq: Long,
    val kind: TermLine.Kind,
    val text: String,
    val ts: Long = System.currentTimeMillis()
) {
    enum class Kind { CMD, OUT, ERR, SYS }
}

/** Result of one terminal_execute — everything real, nothing invented. */
data class ExecOutcome(
    val ok: Boolean,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val cwd: String?,
    val durationMs: Long,
    val timedOut: Boolean,
    val stillRunning: Boolean,
    val fgPid: Int?,
    val sessionRestarted: Boolean,
    val note: String? = null
)

/** Session status snapshot for tools / UI. */
data class SessionStatus(
    val id: String,
    val name: String,
    val backend: TermBackend,
    val agentSession: Boolean,
    val alive: Boolean,
    val ready: Boolean,
    val busy: Boolean,
    val cwd: String?,
    val lastExitCode: Int?,
    val fgPid: Int?,
    val restarts: Int,
    val lineCount: Int,
    /** r1446 — the identity-bound record of the process currently believed to run (null when idle). */
    val process: ProcessRecord? = null
)

/**
 * r1446 — identity of ONE executed command's process tree. The Unix-pidfile
 * lesson applied to the terminal: a bare pid is never trusted on its own —
 * pids are recycled by the kernel, and the old `fgPid: Int` survived backend
 * restarts, so a stop could signal an innocent recycled process.
 *
 * Two identity anchors, measured by the wrapper the moment the pid is known:
 *  - [startTicks]: field 22 of /proc/<pid>/stat (starttime clock ticks) — the
 *    (pid, starttime) pair is impossible for the kernel to recycle. This is
 *    the ROOT gate: platform truth (sandbox-proven 2026-11-13) is that an
 *    exported shell variable NEVER appears in a forked subshell's
 *    /proc/<pid>/environ (that block is fixed at exec time) — so the tracked
 *    background subshell can only be identity-gated by starttime.
 *  - [eid]: the per-execute environ token. It DOES appear in the environ of
 *    every exec'd descendant (the shell builds the exec envp from exported
 *    vars), so it gates the scan loop per victim: a recycled descendant pid
 *    fails the environ match and is never signaled.
 */
data class ProcessRecord(
    val pid: Int,
    val eid: String,
    val command: String,
    val startedAtMs: Long,
    val sessionId: String,
    val backend: TermBackend,
    val adbPort: Int? = null,
    val startTicks: String? = null
)

/** r1446 — the REAL verdict of a stop attempt. Success is claimed only for verified death. */
enum class StopVerdict {
    /** /proc probe confirms the root pid (and, via environ-identity, its tree) is dead. */
    STOPPED_VERIFIED,
    /** The pid was already dead before we signaled — a blind kill was prevented. */
    PID_GONE_ALREADY,
    /** The pid now belongs to a DIFFERENT process (recycled/spoofed) — kill REFUSED. */
    IDENTITY_MISMATCH,
    /** SIGKILL was delivered but the process is still alive after the death-wait. */
    STILL_ALIVE,
    /** The kill channel itself was unreachable/timed out — never claimed as stopped. */
    KILL_UNAVAILABLE,
    /** Nothing is tracked (no FG marker yet / idle session). */
    NO_PROCESS
}

/** r1446 — full honest outcome of terminal_stop_process. */
data class StopOutcome(
    val verdict: StopVerdict,
    val record: ProcessRecord?,
    val childrenKilled: Int,
    val sessionRestarted: Boolean,
    val note: String
)
