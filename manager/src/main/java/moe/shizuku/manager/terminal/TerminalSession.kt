package moe.shizuku.manager.terminal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbInteractiveShell
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val TAG = "TerminalSession"

/**
 * aMiNo r1384 — ONE REAL persistent terminal session.
 *
 * A session owns a single long-lived `sh` process (mksh), so the current
 * directory, exported environment variables and shell state SURVIVE between
 * commands — a new process is NOT spawned per command.
 *
 * Marker protocol (raw `shell:` stream / local pipes carry no echo):
 *   __AMINO_T9_HI_<n>    session init done
 *   __AMINO_T9_ERRB/ERRE stderr block of the running command
 *   __AMINO_T9_RC_<n>    real exit code of the command
 *   __AMINO_T9_FG_<pid>  pid of the backgrounded command (0 for builtins)
 *   __AMINO_T9_CWD_<dir> cwd AFTER the command (cd/export persist — same shell)
 *
 * Commands run as background jobs with `wait` so a long-running process can be
 * stopped out-of-band (kill of the tracked pid) while the session survives with
 * its state. Shell builtins that must mutate the session (cd/export/...) run in
 * the shell context in the foreground instead, which is what makes
 * `cd x` → `pwd` work, per the acceptance tests.
 *
 * stdout/stderr separation: the command's stderr is redirected to a per-session
 * temp file and re-emitted between ERRB/ERRE markers — parsed into a SEPARATE
 * buffer. (Legacy `shell:` merges both on one fd; the file hop gives true
 * attribution on every Android version. The local backend additionally drains
 * the native stderr pipe so it can never block.)
 */
class TerminalSession(
    val id: String,
    val backend: TermBackend,
    private val appContext: Context,
    val displayName: String,
    val isAgentSession: Boolean,
    private val adbPort: Int? = null
) {

    companion object {
        private const val M = "__AMINO_T9_"
        private val RE_RC = Regex("__AMINO_T9_RC_(-?\\d+)")
        private val RE_FG = Regex("__AMINO_T9_FG_(-?\\d+)")
        // r1446 — FST_<starttime-ticks>: field 22 of /proc/<pid>/stat for the
        // tracked root, echoed by the wrapper right after FG. The (pid, ticks)
        // pair is the root's kernel-grade identity — no recycle can repeat it.
        private val RE_FST = Regex("__AMINO_T9_FST_(\\d+)")
        private val RE_CWD = Regex("__AMINO_T9_CWD_(.*)")
        private val RE_HI = Regex("__AMINO_T9_HI_(-?\\d+)")

        // r1446 — per-execute identity sequence: the environ token generation.
        private val eidGen = java.util.concurrent.atomic.AtomicLong(0)
        private const val ERRB = "__AMINO_T9_ERRB"
        private const val ERRE = "__AMINO_T9_ERRE"

        /** First-word builtins that MUST run in the shell context (state mutation). */
        private val BUILTINS = setOf(
            "cd", "export", "unset", "alias", "unalias", "source", ".", "set",
            "umask", "eval", "exit", "history", "hash", "declare", "typeset",
            "readonly", "local", "shift", "trap", "jobs", "bg", "fg", "disown",
            "popd", "pushd", "dirs", "ulimit", "times", "getopts", "bind"
        )

        fun isBuiltinCommand(command: String): Boolean {
            val t = command.trim().split(Regex("\\s+"))
            val first = t.getOrNull(0) ?: return false
            // skip leading env-assignment prefix (FOO=bar cmd)
            val word = t.firstOrNull { !Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(it) } ?: first
            return word in BUILTINS
        }

        /** Escape a user command for single-quoted eval embedding (newlines kept). */
        fun shellQuote(command: String): String =
            "'" + command.replace("'", "'\\''") + "'"

        /**
         * r1446 — the identity-checked tree-kill script (POSIX sh, mksh/toybox
         * safe: no arrays, no pkill -P, integer sleep). Design (expert review,
         * 2026-11-13): walking per-pid children globs under /proc needs
         * CONFIG_PROC_CHILDREN which is NOT guaranteed on Android kernels, so
         * the tree is identified by an exec token instead: execute() exports
         * __AMINO_EID=<sid>_<seq> BEFORE forking the command subshell — every
         * descendant inherits it (a process's /proc/<pid>/environ block is
         * snapshotted at fork and survives exec). Membership is proven
         * PER-VICTIM by a fixed-string word-boundary grep on each candidate's
         * environ — strictly safer than ancestry: recycled pids fail the gate,
         * mid-kill forks are caught, spoofed FG markers are refused, and the
         * session shell itself can never match (its export mutates shell
         * variables, not the fixed environ block from exec time). The script
         * excludes itself (dollar-dollar) and its parent (dollar-PPID) like
         * the r1400 sweep does. Self-bounded (≤ ~6s).
         * Verdict: exactly one line "AMINO_STOP {gone|mismatch|stopped|alive} ck=N".
         */
        fun buildTreeKillScript(rootPid: Int, eid: String, startTicks: String?): String = buildString {
            append("root=$rootPid\n")
            append("eid=$eid\n")
            append("fst=${startTicks ?: ""}\n")
            append("me=\$\$; pp=\$PPID\n")
            append("ck=0\n")
            append("scan() {\n")
            append("  for p in /proc/[0-9]*; do\n")
            append("    q=\${p#/proc/}\n")
            append("    [ \"\$q\" = \"\$me\" ] && continue\n")
            append("    [ \"\$q\" = \"\$pp\" ] && continue\n")
            append("    if grep -aqwF \"__amino_eid=\$eid\" /proc/\$q/environ 2>/dev/null; then\n")
            append("      if kill -9 \"\$q\" 2>/dev/null; then\n")
            append("        [ \"\$q\" = \"\$root\" ] || ck=\$((ck+1))\n")
            append("      fi\n")
            append("    fi\n")
            append("  done\n")
            append("}\n")
            append("if kill -0 \"\$root\" 2>/dev/null; then :; else scan; echo \"AMINO_STOP gone ck=\$ck\"; exit 0; fi\n")
            // r1446 — ROOT identity gate: /proc/<pid>/stat field 22 (starttime
            // ticks) recorded by the FST marker at fork time. A recycled pid
            // can NEVER repeat the (pid, ticks) pair — mismatch = kill REFUSED.
            // Empty fst (compat/old marker) degrades honestly: no gate, the
            // eid still protects every exec'd descendant in the scan.
            append("if [ -n \"\$fst\" ]; then\n")
            append("  cur=\$(sed -e 's/^[0-9]* (.*) //' /proc/\$root/stat 2>/dev/null | cut -d' ' -f20)\n")
            append("  [ \"\$cur\" = \"\$fst\" ] || { echo \"AMINO_STOP mismatch ck=0\"; exit 0; }\n")
            append("fi\n")
            // r1446 (verify-B3) — the scan runs on EVERY path: even when the
            // root already died on its own, its exec'd descendants (eid-tagged)
            // are swept instead of leaking as orphans the sweep can never see.
            append("scan\n")
            append("kill -9 \"\$root\" 2>/dev/null\n")
            append("i=0\n")
            append("while [ \"\$i\" -lt 3 ]; do\n")
            append("  if kill -0 \"\$root\" 2>/dev/null; then sleep 1; else break; fi\n")
            append("  i=\$((i+1))\n")
            append("done\n")
            append("if kill -0 \"\$root\" 2>/dev/null; then echo \"AMINO_STOP alive ck=\$ck\"; else echo \"AMINO_STOP stopped ck=\$ck\"; fi\n")
        }
    }

    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught terminal error [$id]", e)
        runCatching { addSys("internal error: ${e.message}") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private val execMutex = Mutex()

    private val lock = Any()
    // r1401 — the launch gate is NOT `lock` on purpose: start() waits up to 30s
    // (recovery sweep + two 15s init attempts + sleeps) and the READER THREADS
    // must be able to take `lock` during that wait to deliver the init markers.
    // Holding `lock` across the whole launch starved the parser forever: the
    // stderr reader captured __AMINO_T9_HI_42 into the tail (capture happens
    // BEFORE parseLine) then blocked on `lock` — ready could never become true
    // → INIT_TIMEOUT on every device since r1385 (the r1395–r1400 device
    // dialogs: "stderr: __AMINO_T9_HI_42" + stderrReceipt=false + alive — the
    // exact fingerprint). One-shots and probes always worked because they
    // never touch this session's lock.
    private val startGate = Any()
    private val lines = ArrayList<TermLine>()
    private val seqGen = AtomicLong(0)
    private val lineBuf = StringBuilder()          // partial line from the stream

    // --- session state ---
    @Volatile var alive = false; private set
    @Volatile var ready = false; private set

    /** r1395 — the launch diagnostic of the LAST start attempt (observable launch). */
    var lastLaunch: LaunchDiagnostic? = null; private set
    @Volatile var busy = false; private set
    @Volatile var cwd: String? = null; private set
    @Volatile var lastExitCode: Int? = null; private set
    @Volatile var fgPid: Int? = null; private set
    /** r1446 — identity-bound record of the CURRENT command's process tree (null when idle). */
    @Volatile var currentProc: ProcessRecord? = null; private set
    // r1446 — per-executing-command identity: the environ token every tree
    // descendant inherits (exported by the wrapper BEFORE the fork) + the
    // command text, bound into the ProcessRecord when the FG marker arrives.
    private var pendingEid: String? = null
    private var pendingCmd: String? = null
    private var pendingFst: String? = null
    @Volatile var restarts = 0; private set
    val history = ArrayDeque<String>()             // commands typed in THIS session

    // per-executing-command capture
    private var outCap = StringBuilder()
    private var errCap = StringBuilder()
    private var pendingRc: Int? = null
    private var pendingFg: Int? = null
    private var pendingCwd: String? = null
    private var errState = false                   // between ERRB and ERRE
    private var pending: CompletableDeferred<ExecOutcome>? = null
    private var execStart = 0L

    // backend handles
    private var proc: Process? = null              // LOCAL_APP / ROOT
    private var adbShell: AdbInteractiveShell? = null   // ADB_SHELL
    private var remoteProc: moe.shizuku.manager.terminal.linux.ShizukuExec.RemoteProc? = null // LINUX_USERSPACE
    private var stdin: java.io.OutputStream? = null
    private var errFile: String? = null
    private var procGen = 0                        // r1396 — launch-attempt generation (guards reader threads)

    val startDir: String
        get() = when (backend) {
            TermBackend.LOCAL_APP -> appContext.filesDir.absolutePath
            else -> backend.startDir
        }

    // ---------- lifecycle ----------

    fun start(): Boolean {
        // r1401 — serialize launches on startGate (NOT lock): the init handshake
        // completes only if the reader threads can parseLine (lock) WHILE this
        // thread sleeps inside the bounded waits. alive/ready are @Volatile so
        // the plain reads below are safe without holding lock.
        synchronized(startGate) {
            if (alive) return true
            return try {
                when (backend) {
                    TermBackend.ADB_SHELL -> startAdb()
                    TermBackend.LOCAL_APP, TermBackend.ROOT -> startLocal(root = backend == TermBackend.ROOT)
                    TermBackend.LINUX_USERSPACE -> startLinux()
                    else -> false
                }
            } catch (e: Throwable) {
                Log.e(TAG, "session start failed [$id]", e)
                addSys("start failed: ${e.message ?: e.javaClass.simpleName}")
                alive = false
                false
            }
        }
    }

    /**
     * r1385 — Linux userspace backend: one persistent bash INSIDE the Debian
     * rootfs, spawned through the aMiNo service (shell uid, no root) under PRoot.
     * Streams come from the service over binder; the same marker protocol and
     * reader loop as the local backend drive the session.
     */
    private fun startLinux(): Boolean {
        // r1395 — observable launch: every stage records evidence; the first
        // failing stage names itself exactly (Task 1 + Task 2).
        val base = moe.shizuku.manager.terminal.linux.LinuxEnvManager.BASE
        val rootfs = moe.shizuku.manager.terminal.linux.LinuxEnvManager.ROOTFS
        val d = LaunchDiagnostic(id)
        lastLaunch = d
        d.environmentId = backend.id
        d.rootfsPath = rootfs
        d.guestShell = "/bin/bash"
        d.prootPath = "$base/bin/proot"

        // ---- stage 1: the service (the session is spawned BY the service) ----
        if (!moe.shizuku.manager.terminal.linux.ShizukuExec.available()) {
            d.fail("SERVICE_UNAVAILABLE",
                "aMiNo service binder is not connected — sessions are spawned BY the service. " +
                "Start the aMiNo service from the home page (wireless debugging) and retry. " +
                "NOT a root problem: this environment runs as Android shell uid 2000 through PRoot " +
                "(fake uid 0 inside the container only) and never needs real root.")
            addSys("launch failed at SERVICE_UNAVAILABLE: ${d.summary}")
            return false
        }
        d.serviceUid = moe.shizuku.manager.terminal.linux.ShizukuExec.serviceUid()

        // ---- stage 1.5 (r1400): recover from a previous crash BEFORE spawning ----
        // The r1399 device sequence: the app crashed mid-session (toast on a
        // background thread) → the previous session's PRoot + guest shell
        // survived as SERVICE orphans holding the rootfs → the next
        // "Ouvrir le terminal" raced the leftover tree and failed with
        // INIT_TIMEOUT + "Function not implemented" (/tmp staging). If no
        // OTHER live Linux session exists in this process, sweep the orphan
        // tree and re-initialise the temp dirs first. Best-effort: the
        // preflight below stays the hard gate, and a live sibling session is
        // never touched.
        val othersAlive = TerminalEngine.listSessions().any {
            it.id != id && it.alive && it.backend == TermBackend.LINUX_USERSPACE
        }
        if (!othersAlive) {
            addSys("crash recovery: no live Linux session — sweeping orphaned PRoot processes + re-initialising tmp dirs...")
            val rec = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    moe.shizuku.manager.terminal.linux.LinuxEnvManager.recoverAfterCrash()
                }
            }
            addSys(if (rec != null) "crash recovery: $rec"
                   else "crash recovery timed out (continuing — the preflight is the gate)")
        } else {
            addSys("crash recovery skipped — another Linux session is live (its PRoot tree must not be touched)")
        }

        // ---- stage 2: build the exact command + read-only runtime preflight ----
        // r1399 — errFile must exist BEFORE the init script is baked into the
        // guest's argv (the init embeds it for the execute() wrapper).
        errFile = "/tmp/.amino_err_$id"
        val initScript = linuxInitScript(guest = "/bin/bash")
        val (cmd, env) = moe.shizuku.manager.terminal.linux.LinuxEnvManager.sessionCommand(
            appContext, guest = "/bin/bash", initScript = initScript)
        d.commandSanitized = cmd.joinToString(" ")
        val tmpDir = env.firstOrNull { it.startsWith("PROOT_TMP_DIR=") }?.substringAfter('=') ?: "$base/tmp"
        // one service roundtrip (read-only): uid, SELinux domain, proot exec bit,
        // tmp dir writable, loader present, guest shell present, proot version.
        val pre = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(6_000) {
                moe.shizuku.manager.terminal.linux.ShizukuExec.oneShot(
                    "echo UID=\$(id -u 2>/dev/null); " +
                    "echo DOMAIN=\$(cat /proc/self/attr/current 2>/dev/null); " +
                    "test -x $base/bin/proot && echo PROOT_EXEC_OK || echo PROOT_EXEC_MISSING; " +
                    "mkdir -p '$tmpDir' 2>/dev/null; test -w '$tmpDir' && echo TMP_OK || echo TMP_BAD; " +
                    "mkdir -p $base/shared 2>/dev/null; " +
                    "test -f $base/libexec/proot/loader && echo LOADER_OK || echo LOADER_MISSING; " +
                    "test -x $rootfs/bin/bash && echo BASH_OK || echo BASH_MISSING; " +
                    "LD_LIBRARY_PATH=$base/lib $base/bin/proot --version 2>&1 | head -n 1",
                    5_000)
            }
        }
        if (pre == null) {
            d.fail("PREFLIGHT_TIMEOUT", "the service binder answered but the preflight probe timed out (service busy?)")
            addSys("launch failed at PREFLIGHT_TIMEOUT: ${d.summary}")
            return false
        }
        Regex("UID=(\\d+)").find(pre.output)?.groupValues?.get(1)?.toIntOrNull()?.let { d.serviceUid = it }
        Regex("DOMAIN=(\\S+)").find(pre.output)?.groupValues?.get(1)?.let { d.serviceDomain = it }
        d.prootVersion = pre.output.lineSequence().firstOrNull { it.startsWith("proot") }?.trim()
        if (pre.output.contains("shizuku_service_unavailable")) {
            d.fail("SERVICE_UNAVAILABLE", "the binder died between the ping and the preflight probe — restart the aMiNo service")
            addSys("launch failed at SERVICE_UNAVAILABLE: ${d.summary}")
            return false
        }
        val assetProblems = ArrayList<String>()
        if (!pre.output.contains("PROOT_EXEC_OK")) assetProblems.add("proot not executable at $base/bin/proot")
        if (!pre.output.contains("TMP_OK")) assetProblems.add("PROOT_TMP_DIR not writable: $tmpDir")
        if (!pre.output.contains("LOADER_OK")) assetProblems.add("proot loader missing at $base/libexec/proot/loader")
        if (!pre.output.contains("BASH_OK")) assetProblems.add("guest shell missing/not executable: $rootfs/bin/bash")
        if (assetProblems.isNotEmpty()) {
            d.fail("PREFLIGHT", assetProblems.joinToString("; ") + " · preflight output: ${pre.output.take(300)}")
            addSys("launch failed at PREFLIGHT: ${d.summary}")
            return false
        }

        // ---- stage 3: spawn by the service (r1396 attempt ladder) ----
        // Attempt 1 = /bin/bash through the WRAPPED spawn (sh -c 'exec …') — the
        // exact shape every service execution that WORKS on devices uses (probe,
        // preflight, install). On a SILENT INIT_TIMEOUT (alive + empty stderr —
        // the r1395 dialog evidence) attempt 2 retries ONCE with /bin/sh, the
        // shell class proven on this device by every probe/preflight run.
        linuxAttempt(cmd, env, d)
        if (ready) {
            d.summary = "session ready — the PRoot guest shell answered the init marker"
            addSys("launch diagnostic: ${d.oneLine()}")
            return true
        }
        // r1397 — the r1396 gate (stderrTail blank) was self-defeating: the INE
        // receipt is WRITTEN TO STDERR by design, so any guest that started
        // executing made stderrTail non-blank and silently disabled this
        // fallback. A failed INIT_TIMEOUT now always earns the one /bin/sh
        // retry; each attempt still records its own honest evidence.
        if (d.failedStage == "INIT_TIMEOUT") {
            addSys("attempt 1 (guest ${d.guestShell}) diagnostic: ${d.oneLine()}")
            val d2 = LaunchDiagnostic(id).also {
                it.environmentId = d.environmentId; it.rootfsPath = d.rootfsPath
                it.guestShell = "/bin/sh"; it.prootPath = d.prootPath
                it.serviceUid = d.serviceUid; it.serviceDomain = d.serviceDomain
            }
            val (cmd2, env2) = moe.shizuku.manager.terminal.linux.LinuxEnvManager.sessionCommand(
                appContext, guest = "/bin/sh", initScript = linuxInitScript(guest = "/bin/sh"))
            d2.commandSanitized = cmd2.joinToString(" ")
            lastLaunch = d2
            procGen++                                   // invalidate attempt-1 reader threads FIRST
            try { remoteProc?.destroy() } catch (_: Throwable) {}
            linuxAttempt(cmd2, env2, d2)
            if (ready) {
                d2.summary = "session ready via /bin/sh fallback — /bin/bash hung silently at init (attempt 1 diagnostic above)"
                addSys("session ready via /bin/sh fallback — guest /bin/bash was spawned but never answered (evidence above)")
                addSys("attempt 2 (guest /bin/sh) diagnostic: ${d2.oneLine()}")
            } else {
                addSys("attempt 2 (guest /bin/sh) diagnostic: ${d2.oneLine()}")
            }
        } else {
            addSys("attempt 1 (guest ${d.guestShell}) diagnostic: ${d.oneLine()}")
        }
        return true
    }

    /**
     * r1396 — one Linux launch attempt: spawn by the service, wire the streams,
     * run the init exchange. Returns the ready verdict. Full evidence lands in
     * the diagnostic + the page log + Logcat — r1395 captured stdout/stderr tails
     * on failure but never surfaced them; every failed attempt now shows its own
     * oneLine evidence (tails + receipt markers).
     */
    private fun linuxAttempt(cmd: List<String>, env: Array<String>, d: LaunchDiagnostic): Boolean {
        val rp = try {
            moe.shizuku.manager.terminal.linux.ShizukuExec.spawn(cmd, env, "/")
        } catch (e: Throwable) {
            d.fail("SPAWN_FAILED", "${e.message ?: e.javaClass.simpleName}")
            addSys("launch failed at SPAWN_FAILED: ${d.summary}")
            Log.w("LinuxSession", "launch failed [$id] guest=${d.guestShell}: ${d.oneLine()}")
            return false
        }
        remoteProc = rp
        val gen = ++procGen
        stdin = rp.stdin
        // r1399 — errFile is assigned in startLinux BEFORE sessionCommand bakes
        // it into the argv-delivered init; nothing to do here anymore.
        d.spawned = true
        d.processAliveAfterSpawn = try { rp.alive() } catch (_: Throwable) { null }
        val outStart = synchronized(lock) { outCap.length }   // per-attempt stdout slice
        // r1398 — the stderr pipe is the PROVEN stream on the user's device
        // (r1397 dialog evidence: the INE receipt AND proot's own warning both
        // arrived on stderr while stdout stayed silent with the process alive).
        // The stderr drain is now a LINE READER that feeds the SAME parser as
        // stdout, so the init handshake and every protocol marker work no matter
        // which stream actually flows. Bounded capture kept for diagnostics.
        val errCapture = StringBuilder()
        Thread({
            try {
                val r = BufferedReader(InputStreamReader(rp.stderr, Charsets.UTF_8))
                var line = r.readLine()
                while (line != null) {
                    synchronized(errCapture) { if (errCapture.length < 4096) errCapture.appendLine(line) }
                    parseLine(line, fromErr = true)
                    line = r.readLine()
                }
            } catch (e: Throwable) {
                d.stderrReaderError = e.message ?: e.javaClass.simpleName   // r1398 — never swallow again
            }
            if (gen == procGen) onBackendDied()      // only the CURRENT attempt may bury the session
        }, "amino-linux-errdrain-$id-$gen").apply { isDaemon = true; start() }
        Thread({
            try {
                val r = BufferedReader(InputStreamReader(rp.stdout, Charsets.UTF_8))
                var line = r.readLine()
                while (line != null) { parseLine(line); line = r.readLine() }
            } catch (e: Throwable) {
                d.stdoutReaderError = e.message ?: e.javaClass.simpleName   // r1398 — never swallow again
            }
            if (gen == procGen) onBackendDied()      // only the CURRENT attempt may bury the session
        }, "amino-linux-out-$id-$gen").apply { isDaemon = true; start() }
        alive = true
        sendLinuxInit()
        if (ready) {
            // r1399 — the fd1 health probe moved here, POST-ready and ARMORED:
            // the probe echo is backgrounded so a hostile fd1 (this device
            // black-holes child stdout) can only stall a stray subshell — never
            // the session shell. The r1398 pre-ready design wrote the IN
            // receipt to fd1 as the FIRST init line: the guest stalled INSIDE
            // that write (alive, INE echoed, HI never reached — device dialogs
            // r1397+r1398), which is exactly why the init now rides argv and
            // the fd1 question is answered only after the session is live.
            probeAndHealFd1()
        } else {
            val aliveNow = try { rp.alive() } catch (_: Throwable) { false }
            d.stderrTail = synchronized(errCapture) { errCapture.toString() }.trim().take(500)
            d.stdoutTail = synchronized(lock) { outCap.substring(outStart) }.trim().take(500)
            d.stdinReceipt = d.stdoutTail.contains("__AMINO_T9_IN_")
            d.stderrReceipt = d.stderrTail.contains("__AMINO_T9_INE_")
            d.fail(
                if (aliveNow) "INIT_TIMEOUT" else "GUEST_DIED",
                if (aliveNow) "no init marker (__AMINO_T9_HI_42) within 15s — the guest shell was spawned but never answered"
                else "the guest shell exited during init — stderr: ${d.stderrTail.take(300).ifBlank { "(empty)" }}"
            )
            addSys("launch failed at ${d.failedStage}: ${d.summary}")
            Log.w("LinuxSession", "launch failed [$id] guest=${d.guestShell}: ${d.oneLine()}")
        }
        return ready
    }

    /**
     * r1399 — the init script travels INSIDE the guest's argv
     * (`<guest> -c '<script>'`), the delivery channel PROVEN live on the
     * user's device: every one-shot and the PRoot runtime probe answer
     * through argv, while stdin-fed inits INIT_TIMEOUT (r1397 + r1398 device
     * dialogs: the guest executed at most the first two stdin lines — INE
     * echoed on stderr, then stalled alive; HI never came; re-sent stdin
     * markers never echoed either). Order is fail-safe: the ready marker HI
     * rides the PROVEN stderr stream as the VERY FIRST statement — zero
     * setup before it — so the session goes ready within milliseconds of
     * guest start no matter what else is broken. The guest-executes receipt
     * (INE, stderr) follows, then the environment the execute() wrapper
     * needs (err file), then the handover `exec <guest>` — the session shell
     * that keeps reading stdin for user commands.
     */
    private fun linuxInitScript(guest: String): String = buildString {
        append("echo \"${M}HI_\$((6*7))\" >&2; ")
        append("echo \"${M}INE_$id\" >&2; ")
        append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ")
        append("__amino_err=${shellQuote(errFile ?: "/tmp/.amino_err_$id")}; export __amino_err; ")
        append("mkdir -p /tmp 2>/dev/null; ")
        append("cd ${shellQuote(startDir)} 2>/dev/null || cd /; ")
        append("exec ").append(guest)
    }

    /**
     * r1399 — ARMORED fd1 health probe, shared by every backend. The probe
     * echo is BACKGROUND (`&`) so a hostile fd1 can only stall a stray
     * subshell — never the session shell. If the receipt does not surface on
     * stdout within 1.5s, the guest's stdout is merged into the proven stderr
     * stream (exec 1>&2, builtin in bash and dash) and the fact is recorded
     * honestly. Devices where stdout works keep classic dual-stream mode.
     */
    private fun probeAndHealFd1() {
        writeRaw("echo \"${M}INP_$id\" &\n")
        val t0 = System.currentTimeMillis()
        var sawInp = false
        while (System.currentTimeMillis() - t0 < 1500) {
            if (synchronized(lock) { outCap.contains("${M}INP_") }) { sawInp = true; break }
            Thread.sleep(60)
        }
        if (sawInp) {
            addSys("guest fd1 verified alive (INP receipt on stdout) — classic dual-stream mode")
        } else {
            writeRaw("exec 1>&2\n")
            addSys("guest fd1 delivered nothing in 1.5s (this device black-holes child stdout) — fd1 merged into the proven stderr stream (exec 1>&2); all output now rides stderr")
            Log.w(TAG, "[$id] fd1 silent (no INP receipt) — merged fd1 into stderr")
        }
    }

    /**
     * Linux init wait: r1399 — the init itself travels in ARGV (see
     * linuxInitScript); stdin now carries only FALLBACK ready markers
     * (re-sent at +5s and +10s). If the argv init executed but its HI marker
     * was lost, the queued markers are executed by the handover shell and
     * still complete the handshake; extra markers are harmless (ready stays
     * true). Returns the init verdict.
     */
    private fun sendLinuxInit(): Boolean {
        ready = false
        val marker = "echo \"${M}HI_\$((6*7))\" >&2\n"
        // PRoot startup is slower than a plain shell — allow more time
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 15000
        var retry1 = t0 + 5000
        var retry2 = t0 + 10000
        while (alive && !ready && System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            if (now >= retry1) { writeRaw(marker); retry1 = Long.MAX_VALUE }
            if (now >= retry2) { writeRaw(marker); retry2 = Long.MAX_VALUE }
            Thread.sleep(80)
        }
        if (ready) {
            cwd = startDir
            addSys("session ready — env: ${backend.title} (Debian 12 userspace via PRoot — host identity: Android shell UID 2000, NOT real root)")
        } else {
            addSys("session init timed out — the environment did not answer")
        }
        return ready
    }

    private fun startLocal(root: Boolean): Boolean {
        // r1399 — the init travels in ARGV (`sh -c '<init>'`), the delivery
        // channel proven on the user's device: the local environment failed
        // there with the EXACT same INIT_TIMEOUT as the linux session (agent
        // transcript 2026-10-01) because BOTH fed the init through stdin. HI
        // (ready) rides STDERR as the FIRST statement — and the stderr drain
        // is now a LINE READER feeding the same parser (was: drained and
        // discarded, so the local handshake could only ever ride stdout —
        // exactly the stream this device black-holes).
        errFile = "${appContext.cacheDir.absolutePath}/.amino_err_${id}"
        val init = buildString {
            append("echo \"${M}HI_\$((6*7))\" >&2; ")
            append("export PATH=/system/bin:/system/xbin:/vendor/bin:/odm/bin:/apex/com.android.runtime/bin:/su/bin:/sbin:\$PATH; ")
            append("__amino_err=${shellQuote(errFile ?: "")}; export __amino_err; ")
            append("mkdir -p /data/local/tmp 2>/dev/null; ")
            append("cd ${shellQuote(startDir)} 2>/dev/null || cd /; ")
            append("exec sh")
        }
        val pb = ProcessBuilder(if (root) listOf("su", "-c", init) else listOf("sh", "-c", init))
        pb.redirectErrorStream(false)
        val p = pb.start()
        proc = p
        stdin = p.outputStream
        // r1446 (verify-A2) — generation-guard parity with linuxAttempt: the
        // restart flow must invalidate the PREVIOUS attempt's stdout thread,
        // or its later EOF (orphan exit) buries the freshly restarted session.
        val gen = ++procGen
        val errCapture = StringBuilder()
        // native stderr pipe MUST be drained or the child can block on a full
        // pipe — r1399: drained as LINES through the same parser (bounded
        // capture kept for the failure evidence).
        Thread({
            try {
                val r = BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8))
                var line = r.readLine()
                while (line != null) {
                    synchronized(errCapture) { if (errCapture.length < 4096) errCapture.appendLine(line) }
                    parseLine(line, fromErr = true)
                    line = r.readLine()
                }
            } catch (_: Throwable) {}
        }, "amino-term-errdrain-$id").apply { isDaemon = true; start() }
        Thread({
            try {
                val r = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
                var line = r.readLine()
                while (line != null) { parseLine(line); line = r.readLine() }
            } catch (_: Throwable) {}
            if (gen == procGen) onBackendDied()   // r1446 (verify-A2) — only the CURRENT attempt may bury the session
        }, "amino-term-out-$id").apply { isDaemon = true; start() }
        alive = true
        // bounded wait for the HI marker (stderr or stdout), with ONE stdin
        // fallback re-send at +4s (queued markers execute at the handover
        // shell's read loop if the argv init ran but HI was lost).
        val marker = "echo \"${M}HI_\$((6*7))\" >&2\n"
        val deadline = System.currentTimeMillis() + 8000
        var retry = System.currentTimeMillis() + 4000
        while (alive && !ready && System.currentTimeMillis() < deadline) {
            if (System.currentTimeMillis() >= retry) { writeRaw(marker); retry = Long.MAX_VALUE }
            Thread.sleep(80)
        }
        if (ready) {
            cwd = startDir
            addSys("session ready — env: ${backend.title}")
            probeAndHealFd1()
        } else {
            val tail = synchronized(errCapture) { errCapture.toString() }.trim().take(300)
            addSys("session init timed out — the environment did not answer" +
                if (tail.isNotBlank()) " · stderr tail: $tail" else "")
        }
        return ready
    }

    private fun startAdb(): Boolean {
        val port = adbPort ?: throw IllegalStateException("no ADB port resolved")
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "amino")
        val s = AdbInteractiveShell("127.0.0.1", port, key)
        s.connect()
        s.openShell(
            onOutput = { data -> feed(String(data, Charsets.UTF_8)) },
            onClosed = { err ->
                Log.w(TAG, "adb terminal stream closed [$id]: ${err?.message}")
                onBackendDied()
            }
        )
        adbShell = s
        stdin = null
        errFile = "/data/local/tmp/.amino_err_$id"
        alive = true
        sendInit()
        return true
    }

    private fun sendInit() {
        ready = false
        // r1399 — HI (ready) FIRST, on stderr (the stream proven live where
        // child stdout is black-holed); setup follows; fd1 is merged at the
        // end, before the shell starts reading user commands. On ADB the
        // stream is a merged PTY so all of this is a no-op there; the same
        // shape heals any backend whose stdout is hostile.
        val path = "/system/bin:/system/xbin:/vendor/bin:/odm/bin:/apex/com.android.runtime/bin:/su/bin:/sbin:\$PATH"
        val init = buildString {
            append("echo \"${M}HI_\$((6*7))\" >&2; ")
            append("export PATH=$path; ")
            append("__amino_err=${shellQuote(errFile ?: "")}; export __amino_err; ")
            append("mkdir -p /data/local/tmp 2>/dev/null; ")
            append("cd ${shellQuote(startDir)} 2>/dev/null || cd /; ")
            append("exec 1>&2")
        }
        writeRaw(init)
        // bounded busy-wait (Thread.sleep is not cancellable — use a wall deadline)
        val deadline = System.currentTimeMillis() + 8000
        while (alive && !ready && System.currentTimeMillis() < deadline) Thread.sleep(80)
        val ok = ready
        if (ok) {
            cwd = startDir
            addSys("session ready — env: ${backend.title}${if (backend == TermBackend.ADB_SHELL) " (uid 2000 shell)" else ""}")
        } else {
            addSys("session init timed out — the environment did not answer")
        }
    }

    private fun onBackendDied() {
        val wasAlive: Boolean
        synchronized(lock) { wasAlive = alive; alive = false; ready = false }
        if (!wasAlive) return
        // r1446 — the backend died while a command's tree may still run: the
        // record becomes an engine orphan for the sweep (the old code silently
        // dropped both the pid and the truth), and the raw pid is cleared so a
        // recycled pid can never be signaled later.
        currentProc?.let { TerminalEngine.registerOrphan(it) }
        currentProc = null; fgPid = null
        addSys("backend connection lost — the session will restart on the next command (shell state is reset)")
        failPending("backend connection lost")
    }

    // ---------- low-level io ----------

    private fun writeRaw(text: String) {
        try {
            val sh = adbShell
            if (sh != null) { sh.send(text); return }
            stdin?.write(text.toByteArray(Charsets.UTF_8))
            stdin?.flush()
        } catch (e: Throwable) {
            Log.e(TAG, "writeRaw failed [$id]", e)
            addSys("write failed: ${e.message}")
            onBackendDied()
        }
    }

    /** Feed a raw chunk from the adb stream (may contain partial lines). */
    private fun feed(chunk: String) {
        synchronized(lock) {
            lineBuf.append(chunk)
            var idx: Int
            while (lineBuf.indexOf("\n").also { idx = it } >= 0) {
                val line = lineBuf.substring(0, idx)
                lineBuf.delete(0, idx + 1)
                parseLineLocked(line.trimEnd('\r'))
            }
        }
    }

    /** Feed one complete line from the local process stdout — or stderr (r1398). */
    private fun parseLine(line: String, fromErr: Boolean = false) = synchronized(lock) { parseLineLocked(line.trimEnd('\r'), fromErr) }

    private fun parseLineLocked(line: String, fromErr: Boolean = false) {
        if (errState) {
            if (line.contains(ERRE)) { errState = false } else { appendLine(TermLine.Kind.ERR, line); errCap.appendLine(line) }
            return
        }
        // r1398/r1399 — init receipt markers are diagnostic, not terminal
        // content: IN (stdout, pre-merge) and INP (post-ready fd1 probe) are
        // recorded in outCap for the fd1-health verdict; INE lives in the
        // bounded stderr capture. None are displayed.
        if (line.startsWith("${M}IN_") || line.startsWith("${M}INE_") || line.startsWith("${M}INP_")) {
            if (!fromErr) outCap.appendLine(line)
            return
        }
        // markers may arrive in any order; completion = RC seen (CWD checked too)
        RE_HI.find(line)?.let {
            if (it.groupValues[1] == "42") ready = true
            return
        }
        if (line.contains(ERRB)) { errState = true; return }
        RE_RC.find(line)?.let { m ->
            pendingRc = m.groupValues[1].toIntOrNull()
            maybeComplete(); return
        }
        RE_FG.find(line)?.let { m ->
            pendingFg = m.groupValues[1].toIntOrNull()
            if ((pendingFg ?: 0) > 0) {
                fgPid = pendingFg
                // r1446 — bind the IDENTITY at the moment the pid is learned:
                // the wrapper echoes FG BEFORE wait now, so this is the CURRENT
                // command's pid (the old post-wait echo left fgPid holding the
                // PREVIOUS command's pid for the whole run — the A1 defect).
                val pe = pendingEid
                // r1446 (verify-A3) — bind ONCE per eid and carry the ticks
                // already received: a duplicate/late FG line (any command can
                // print a marker-shaped line to stdout) must never re-bind the
                // record with startTicks=null — that would silently void the
                // (pid,starttime) recycle gate for the RUNNING command.
                if (pe != null && currentProc?.eid != pe) currentProc = ProcessRecord(
                    pid = pendingFg!!, eid = pe,
                    command = pendingCmd ?: "", startedAtMs = execStart,
                    sessionId = id, backend = backend, adbPort = adbPort,
                    startTicks = pendingFst
                )
            }
            maybeComplete(); return
        }
        RE_CWD.find(line)?.let { m ->
            pendingCwd = m.groupValues[1].trim()
            maybeComplete(); return
        }
        RE_FST.find(line)?.let { m ->
            // r1446 — anchor the root's kernel identity the moment it is
            // measurable: currentProc was just bound by the FG handler (FG is
            // echoed before FST by the wrapper), so this is a copy-update.
            pendingFst = m.groupValues[1]
            currentProc = currentProc?.copy(startTicks = pendingFst)
            return
        }
        if (line.isBlank() && !busy) return   // ignore stray newlines when idle
        appendLine(if (fromErr) TermLine.Kind.ERR else TermLine.Kind.OUT, line)
        outCap.appendLine(line)
    }

    private fun maybeComplete() {
        val rc = pendingRc ?: return
        if (pendingCwd == null && alive) return   // wait for the CWD marker (order: RC then FG then CWD)
        pendingRc = null; val fg = pendingFg; pendingFg = null; val cd = pendingCwd; pendingCwd = null
        errState = false
        cwd = cd ?: cwd
        lastExitCode = rc
        busy = false
        // r1446 — THIS command's tree finished: drop the binding AND the raw
        // pid (the old code kept fgPid forever — a stale pid is a recycling
        // hazard that could make a later stop signal an innocent process).
        currentProc = null; fgPid = null
        pendingEid = null; pendingCmd = null; pendingFst = null
        appendLine(TermLine.Kind.SYS, "rc=$rc" + (if (fg != null && fg > 0) " · pid $fg" else ""))
        val d = pending
        pending = null
        d?.complete(
            ExecOutcome(
                ok = rc == 0, exitCode = rc,
                stdout = outCap.toString().trimEnd('\n'),
                stderr = errCap.toString().trimEnd('\n'),
                cwd = cwd, durationMs = System.currentTimeMillis() - execStart,
                timedOut = false, stillRunning = false, fgPid = fg,
                sessionRestarted = restarts > 0
            )
        )
    }

    private fun failPending(reason: String) {
        busy = false
        errState = false
        pendingEid = null; pendingCmd = null; pendingFst = null
        val d = pending
        pending = null
        val (o, e) = synchronized(lock) { outCap.toString() to errCap.toString() }
        d?.complete(
            ExecOutcome(false, null, o, e,
                null, System.currentTimeMillis() - execStart, false, false, null, true, reason)
        )
    }

    // ---------- public api ----------

    /**
     * Execute a command INSIDE this persistent shell. Returns the REAL result:
     * stdout, stderr, exit code, cwd. On timeout the command keeps running
     * (streaming output continues) — stop it with [stopProcess].
     */
    suspend fun execute(command: String, timeoutMs: Long = 20_000): ExecOutcome {
        if (command.isBlank()) return ExecOutcome(false, null, "", "", cwd, 0, false, false, null, false, "empty command")
        return execMutex.withLock {
            if (!alive || !ready) {
                addSys("restarting session backend...")
                restarts++
                // r1446 (A6) — destroy the OLD backend handles first: the old
                // code called start() without destroyBackend(), leaking the
                // previous proc/adbShell/remoteProc and leaving its reader
                // threads armed against the restarted session.
                if (proc != null || adbShell != null || remoteProc != null) destroyBackend()
                outCap = StringBuilder(); errCap = StringBuilder()
                if (!start()) return@withLock ExecOutcome(
                    false, null, "", "", cwd, 0, false, false, null, true,
                    "cannot (re)start backend ${backend.title} — environment unavailable"
                )
            }
            if (busy) return@withLock ExecOutcome(
                false, null, outCap.toString(), errCap.toString(), cwd, 0, false, true, fgPid, false,
                "session busy: previous command still running (pid ${fgPid ?: "?"}) — wait or stop it first"
            )
            execStart = System.currentTimeMillis()
            outCap = StringBuilder(); errCap = StringBuilder()
            errState = false   // r1446 (verify-B4) — a death between ERRB/ERRE must not poison the next exchange
            pendingRc = null; pendingFg = null; pendingCwd = null
            // r1446 — per-command identity token: zero-padded seq (fixed width
            // means no eid can be a substring-prefix of another) exported by the
            // wrapper BEFORE the fork so every tree descendant inherits it and
            // the kill script can prove membership per victim.
            val eid = "${id}_" + String.format(java.util.Locale.ROOT, "%06d", eidGen.incrementAndGet())
            pendingEid = eid; pendingCmd = command
            appendLine(TermLine.Kind.CMD, command.replace("\n", " ⏎ "))
            if (history.lastOrNull() != command) { history.addLast(command); if (history.size > 50) history.removeFirst() }
            busy = true
            val deferred = CompletableDeferred<ExecOutcome>()
            pending = deferred

            val quoted = shellQuote(command)
            val wrapper = if (isBuiltinCommand(command)) {
                // builtins must run in the shell context so cd/export persist.
                // r1398 — ALL protocol markers now echo to STDERR: the stream
                // proven live on the user's device (stdout stayed silent there
                // even with the process alive). The parser is fed from BOTH
                // streams, so healthy devices behave identically.
                ": >\"\$__amino_err\"; eval $quoted 2>\"\$__amino_err\"; __amino_rc=\$?; " +
                    "echo \"$ERRB\" >&2; cat \"\$__amino_err\" 2>/dev/null >&2; echo \"$ERRE\" >&2; " +
                    "echo \"${M}RC_\${__amino_rc}\" >&2; echo \"${M}FG_0\" >&2; echo \"${M}CWD_\$PWD\" >&2\n"
            } else {
                // external commands: background + tracked pid (killable, output
                // streams live). r1446 — the eid is exported BEFORE the fork
                // (every descendant inherits it in its environ), and the FG
                // marker is echoed BEFORE wait (was: after — the old order left
                // fgPid holding the PREVIOUS command's pid for the whole run,
                // so every mid-run stop targeted the wrong process, A1).
                ": >\"\$__amino_err\"; __amino_eid='$eid'; export __amino_eid; " +
                    "eval $quoted 2>\"\$__amino_err\" & __amino_fg=\$!; " +
                    "echo \"${M}FG_\${__amino_fg}\" >&2; " +
                    // r1446 — FST: the root's /proc/<pid>/stat starttime ticks.
                    // Platform truth (sandbox-proven 2026-11-13): an exported var
                    // NEVER shows in a forked subshell's environ block (fixed at
                    // exec time) — the (pid, ticks) pair is the only root gate the
                    // kernel cannot fool by recycling. The eid still gates every
                    // exec'd DESCENDANT in the kill scan (exec envp carries it).
                    "echo \"${M}FST_\$(sed -e 's/^[0-9]* (.*) //' /proc/\${__amino_fg}/stat 2>/dev/null | cut -d' ' -f20)\" >&2; " +
                    "wait \"\$__amino_fg\"; __amino_rc=\$?; echo \"$ERRB\" >&2; cat \"\$__amino_err\" 2>/dev/null >&2; echo \"$ERRE\" >&2; " +
                    "echo \"${M}RC_\${__amino_rc}\" >&2; echo \"${M}CWD_\$PWD\" >&2\n"
            }
            writeRaw(wrapper)

            // bounded wait: the parser completes the deferred when the RC+CWD
            // markers arrive; on timeout the command KEEPS RUNNING (output keeps
            // streaming) and can be stopped with stopProcess.
            val outcome = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (outcome != null) outcome else {
                val (outSnap, errSnap) = synchronized(lock) { outCap.toString() to errCap.toString() }
                ExecOutcome(
                    ok = false, exitCode = null,
                    stdout = outSnap.trimEnd('\n'),
                    stderr = errSnap.trimEnd('\n'),
                    cwd = cwd, durationMs = System.currentTimeMillis() - execStart,
                    timedOut = true, stillRunning = true, fgPid = fgPid, sessionRestarted = false,
                    note = "timeout after ${timeoutMs / 1000}s — the command is STILL RUNNING (pid ${fgPid ?: "?"}); " +
                        "output keeps streaming; stop it with terminal_stop_process"
                )
            }
        }
    }

    /** Raw stdin write — for interactive programs started via execute. */
    fun sendInput(text: String): Boolean {
        if (!alive) return false
        writeRaw(if (text.endsWith("\n")) text else "$text\n")
        return true
    }

    /**
     * Stop the running foreground command — r1446 fire-and-forget wrapper kept
     * for the UI call sites (TerminalActivity, LinuxAcceptance T7); the REAL
     * logic is [stopProcessAwait]. The old version killed a bare pid out-of-band
     * (which was the background SUBSHELL — often the PREVIOUS command's pid)
     * and reported "process stopped" from an unconditional KILLDONE echo
     * without ever verifying the process actually died.
     */
    fun stopProcess() {
        scope.launch { runCatching { stopProcessAwait() } }
    }

    /**
     * r1446 — identity-checked, death-VERIFIED stop. The tracked record carries
     * the per-execute environ token; TerminalEngine.runTreeKill matches
     * /proc/<pid>/environ per victim before signaling (a recycled pid, a pid
     * from a dead backend, or a spoofed FG marker is REFUSED, never harmed),
     * waits for real death with kill -0 probes, and returns the honest verdict.
     * Success is claimed ONLY for verified death (or pid-already-gone) — never
     * for "the kill command was sent".
     */
    suspend fun stopProcessAwait(): StopOutcome {
        val rec = currentProc
        if (rec == null) {
            val note = if (busy)
                "command running but its pid marker has not arrived yet — retry in a moment"
            else "nothing to stop — no tracked process"
            addSys(note)
            return StopOutcome(StopVerdict.NO_PROCESS, null, 0, false, note)
        }
        addSys("stopping pid ${rec.pid} (${rec.command.take(40)}) — identity-checked tree kill...")
        val parsed = TerminalEngine.runTreeKill(rec)
        val outcome = when (parsed?.verdict) {
            "stopped" -> {
                if (currentProc === rec) { currentProc = null; fgPid = null }   // r1446 (verify-B5) — never wipe a NEWER command's binding
                // give the rc-marker flow a bounded moment to unblock `wait`
                var waited = 0
                while (busy && waited < 1500) { Thread.sleep(100); waited += 100 }
                addSys("process stopped — verified dead; session state preserved (cwd: ${cwd ?: "?"})")
                StopOutcome(StopVerdict.STOPPED_VERIFIED, rec, parsed.children, false,
                    "verified stopped (children killed: ${parsed.children})" +
                        if (busy) " — tree confirmed dead; the session wait is unblocking" else "")
            }
            "gone" -> {
                if (currentProc === rec) { currentProc = null; fgPid = null }
                addSys("pid ${rec.pid} was already dead — eid-tagged descendants swept: ${parsed.children}")
                StopOutcome(StopVerdict.PID_GONE_ALREADY, rec, parsed.children, false,
                    "pid already dead — " +
                        (if (parsed.children > 0) "${parsed.children} surviving descendant(s) swept by the environ scan"
                        else "nothing was signaled"))
            }
            "mismatch" -> {
                if (currentProc === rec) { currentProc = null; fgPid = null }
                addSys("pid ${rec.pid} now belongs to a DIFFERENT process — kill REFUSED by the identity check")
                StopOutcome(StopVerdict.IDENTITY_MISMATCH, rec, 0, false,
                    "pid recycled or marker spoofed — kill refused (no innocent process was harmed)")
            }
            "alive" -> {
                addSys("SIGKILL delivered but pid ${rec.pid} is STILL ALIVE after the death-wait — recorded as orphan for the sweep")
                TerminalEngine.registerOrphan(rec)
                StopOutcome(StopVerdict.STILL_ALIVE, rec, parsed.children, false,
                    "SIGKILL delivered but the process survived the 3s death-wait (uninterruptible state?) — orphan sweep will retry")
            }
            else -> {
                // KILL_UNAVAILABLE — the kill channel never answered honestly.
                // Record the orphan (the sweep retries it), then destroy and
                // restart the backend transparently — state loss is REPORTED.
                TerminalEngine.registerOrphan(rec)
                currentProc = null; fgPid = null
                destroyBackend(); restarts++; busy = false
                failPending("kill channel unavailable — session restarted; process recorded as orphan for sweep")
                addSys("out-of-band kill unreachable — session restarted (state is lost); the orphan sweep will retry the pid")
                StopOutcome(StopVerdict.KILL_UNAVAILABLE, rec, 0, true,
                    "kill channel unreachable — session restarted; process recorded as orphan for the sweep")
            }
        }
        // r1446 (R4) — every stop is a lazy sweep trigger: bounded, honest,
        // never a background daemon.
        scope.launch { runCatching { TerminalEngine.sweepOrphans() } }
        return outcome
    }

    private fun destroyBackend() {
        // r1446 — invalidate stale reader threads FIRST (A3 parity), orphan the
        // running tree if any, and clear the raw pid (recycling hazard).
        procGen++
        currentProc?.let { TerminalEngine.registerOrphan(it) }
        currentProc = null; fgPid = null
        runCatching { adbShell?.close() }
        runCatching { proc?.destroy() }
        val rp = remoteProc
        if (rp != null) {
            runCatching { rp.destroy() }
            // best-effort sweep of anything left bound to the runtime tree
            // (bash itself exits on stdin EOF; traced children follow the tracer).
            // r1400 — guarded: never sweep when another live Linux session
            // exists (the old unconditional sweep killed ITS proot tree too).
            runCatching {
                kotlinx.coroutines.runBlocking {
                    moe.shizuku.manager.terminal.linux.LinuxEnvManager.sweepStrayProcesses(excludeSessionId = id)
                }
            }
        }
        adbShell = null; proc = null; remoteProc = null; stdin = null
        alive = false; ready = false
    }

    fun close() {
        destroyBackend()
        addSys("session closed")
        failPending("session closed")
    }

    // ---------- scrollback ----------

    fun snapshotLines(): List<TermLine> = synchronized(lock) { lines.toList() }

    fun clearLines() = synchronized(lock) { lines.clear() }

    private fun addSys(text: String) = appendLine(TermLine.Kind.SYS, "[aMiNo] $text")

    private fun appendLine(kind: TermLine.Kind, text: String) {
        if (text.isEmpty() && kind == TermLine.Kind.OUT) return
        synchronized(lock) {
            lines.add(TermLine(seqGen.incrementAndGet(), kind, text))
            if (lines.size > 800) lines.subList(0, lines.size - 800).clear()
        }
    }

    fun status(): SessionStatus = SessionStatus(
        id = id, name = displayName, backend = backend, agentSession = isAgentSession,
        alive = alive, ready = ready, busy = busy, cwd = cwd, lastExitCode = lastExitCode,
        fgPid = if (busy) fgPid else null, restarts = restarts,
        lineCount = synchronized(lock) { lines.size },
        process = if (busy) currentProc else null
    )

    /** r1395 — the last N system lines (launch evidence survives even after the fact). */
    fun sysTail(n: Int = 6): String = synchronized(lock) {
        lines.filter { it.kind == TermLine.Kind.SYS }.takeLast(n).joinToString(" | ") { it.text }
    }
}

/**
 * aMiNo r1395 — OBSERVABLE LAUNCH (user Task 2): every Linux launch attempt
 * records the full stage-by-stage diagnostic — timestamp, environment, rootfs
 * path, service uid + SELinux domain, proot path/version, guest shell, the
 * exact sanitized command, spawn result, init outcome, stdout/stderr tails and
 * the EXACT failing stage. Never swallowed; surfaced in the UI and in the
 * exception the caller sees.
 *
 * Stage ladder (the first failure wins):
 *   SERVICE_UNAVAILABLE -> PREFLIGHT_TIMEOUT -> PREFLIGHT -> SPAWN_FAILED
 *   -> INIT_TIMEOUT / GUEST_DIED -> (ok)
 */
class LaunchDiagnostic(val sessionId: String) {
    val timestamp: Long = System.currentTimeMillis()
    var environmentId: String = ""
    var rootfsPath: String = ""
    var serviceUid: Int? = null
    var serviceDomain: String? = null
    var prootPath: String = ""
    var prootVersion: String? = null
    var guestShell: String = ""
    var commandSanitized: String = ""
    var spawned: Boolean = false
    var processAliveAfterSpawn: Boolean? = null
    var failedStage: String? = null
    var summary: String = ""
    var stdoutTail: String = ""
    var stderrTail: String = ""
    var stdinReceipt: Boolean? = null    // r1396 — the IN receipt marker arrived on stdout (stdin reached the guest)
    var stderrReceipt: Boolean? = null   // r1396 — the INE receipt marker arrived on stderr (the guest executes)
    var stdoutReaderError: String? = null // r1398 — the app-side stdout reader thread failed (never swallowed again)
    var stderrReaderError: String? = null // r1398 — the app-side stderr reader thread failed

    fun fail(stage: String, why: String) { failedStage = stage; summary = why }

    // r1397 — decision-critical fields FIRST (stage, verdict, receipts, tails);
    // the bulky identity fields (rootfs/serviceUid/domain/proot/cmd) go LAST so
    // no fixed-width window can ever amputate the evidence again.
    fun oneLine(): String = buildString {
        append("ts=").append(timestamp)
        append(" · env=").append(environmentId)
        append(" · guest=").append(guestShell)
        append(" · spawn=").append(if (spawned) "ok" else "not reached")
        processAliveAfterSpawn?.let { append(" · aliveAfterSpawn=").append(it) }
        append(" · stage=").append(failedStage ?: "READY")
        if (summary.isNotBlank()) append(" — ").append(summary.take(300))
        stdinReceipt?.let { append(" · stdinReceipt=").append(it) }
        stderrReceipt?.let { append(" · stderrReceipt=").append(it) }
        stdoutReaderError?.let { append(" · stdoutReaderError=").append(it) }
        stderrReaderError?.let { append(" · stderrReaderError=").append(it) }
        if (stderrTail.isNotBlank()) append(" · stderr: ").append(stderrTail.take(200))
        if (stdoutTail.isNotBlank()) append(" · stdout: ").append(stdoutTail.take(200))
        // r1399 — the cmd now embeds the argv-delivered init script (the
        // decisive evidence on stdin-hostile devices): widen the window.
        if (commandSanitized.isNotBlank()) append(" · cmd=").append(commandSanitized.take(600))
        append(" · rootfs=").append(rootfsPath)
        append(" · serviceUid=").append(serviceUid ?: "?")
        append(" · domain=").append(serviceDomain ?: "?")
        append(" · proot=").append(prootPath)
        prootVersion?.let { append(" (").append(it).append(")") }
    }
}

/** ADB port resolution shared by the engine and sessions. */
object TerminalNet {
    /** Resolve the ADB wireless port (saved first, then mDNS) — honest, with a timeout. */
    suspend fun resolveAdbPort(context: Context): Int? {
        ShizukuSettings.getShellPort().takeIf { it > 0 }?.let { return it }
        return withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                var mdns: AdbMdns? = null
                mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { pair ->
                    if (pair.second > 0 && cont.isActive) {
                        mdns?.stop()
                        cont.resume(pair.second)
                    }
                }
                mdns.start()
                cont.invokeOnCancellation { mdns?.stop() }
            }
        }
    }
}
