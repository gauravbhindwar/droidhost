package com.droidhost.service

import android.content.Context
import android.util.Log
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.ShellFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

class DroidHostSshServer(
    private val context: Context,
    private val port: Int = 2222
) {
    private val tag = "DroidHostSshServer"
    private var sshd: SshServer? = null

    fun start() {
        try {
            // Android has no OS user-home (no $HOME / user.home), so Apache MINA SSHD
            // throws "No user home folder available" from ServerBuilder.<clinit>
            // via DefaultAuthorizedKeysAuthenticator -> PathUtils.getUserHomeFolder().
            // Point it at our app-private files dir before touching SshServer.
            try {
                PathUtils.setUserHomeFolderResolver(java.util.function.Supplier { context.filesDir.toPath() })
            } catch (_: Throwable) { }

            val server = SshServer.setUpDefaultServer()
            server.port = port

            // Host key in app private files
            val keyFile = File(context.filesDir, "hostkey.ser")
            keyFile.parentFile?.mkdirs()
            server.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile.toPath())

            // Accept any password / root login
            server.passwordAuthenticator = PasswordAuthenticator { username, password, session ->
                Log.i(tag, "SSH login attempt for user: $username")
                true
            }

            // Interactive shell factory
            server.shellFactory = ShellFactory { channel ->
                ShellCommand(context)
            }

            // Single command execution factory (e.g. ssh user@host "uname -a")
            server.commandFactory = CommandFactory { channel, command ->
                ExecCommand(command, context)
            }

            server.start()
            sshd = server
            Log.i(tag, "DroidHost SSH Server started on port $port")
        } catch (t: Throwable) {
            Log.e(tag, "Failed to start SSH Server on port $port: ${t.message}", t)
        }
    }

    fun stop() {
        try {
            sshd?.stop(true)
            sshd = null
            Log.i(tag, "DroidHost SSH Server stopped")
        } catch (e: Exception) {
            Log.w(tag, "Error stopping SSH Server: ${e.message}")
        }
    }

    private class ShellCommand(private val context: Context) : Command, Runnable {
        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null
        private var errorStream: OutputStream? = null
        private var exitCallback: ExitCallback? = null
        private var workerThread: Thread? = null
        private var process: Process? = null

        override fun setInputStream(inp: InputStream) { this.inputStream = inp }
        override fun setOutputStream(out: OutputStream) { this.outputStream = out }
        override fun setErrorStream(err: OutputStream) { this.errorStream = err }
        override fun setExitCallback(callback: ExitCallback) { this.exitCallback = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            workerThread = Thread(this, "SSH-Shell-Worker").apply { start() }
        }

        override fun run() {
            val out = outputStream ?: return
            val inp = inputStream ?: return

            try {
                out.write("\r\n\u001b[1;32m[DroidHost ARM64 Linux Server - Active]\u001b[0m\r\n".toByteArray(Charsets.UTF_8))
                out.write("Linux droidhost 6.6.0-arm64 #1 SMP aarch64\r\n".toByteArray(Charsets.UTF_8))
                out.write("Connected to DroidHost VM on Android over Tailscale/Wi-Fi\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()

                val pb = ProcessBuilder("/system/bin/sh")
                pb.directory(context.filesDir)
                pb.environment()["TERM"] = "xterm-256color"
                pb.environment()["PS1"] = "droidhost:~$ "
                pb.environment()["HOME"] = context.filesDir.absolutePath
                pb.environment()["PATH"] = "${context.filesDir.absolutePath}/vm/bin:/system/bin:/system/xbin"
                pb.redirectErrorStream(true)

                val proc = pb.start()
                process = proc

                val procOut = proc.inputStream
                val procIn = proc.outputStream

                // Pipe procOut -> out
                val outPump = thread(name = "SSH-OutPump") {
                    try {
                        val buf = ByteArray(1024)
                        while (true) {
                            val n = procOut.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            out.flush()
                        }
                    } catch (_: Exception) {}
                }

                // Pipe inp -> procIn
                val inPump = thread(name = "SSH-InPump") {
                    try {
                        val buf = ByteArray(1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            procIn.write(buf, 0, n)
                            procIn.flush()
                        }
                    } catch (_: Exception) {}
                }

                proc.waitFor()
                outPump.join(500)
                inPump.interrupt()
                exitCallback?.onExit(proc.exitValue())
            } catch (e: Exception) {
                exitCallback?.onExit(-1, e.message)
            }
        }

        override fun destroy(channel: ChannelSession) {
            try { process?.destroy() } catch (_: Exception) {}
            workerThread?.interrupt()
        }
    }

    private class ExecCommand(private val command: String, private val context: Context) : Command, Runnable {
        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null
        private var errorStream: OutputStream? = null
        private var exitCallback: ExitCallback? = null
        private var workerThread: Thread? = null

        override fun setInputStream(inp: InputStream) { this.inputStream = inp }
        override fun setOutputStream(out: OutputStream) { this.outputStream = out }
        override fun setErrorStream(err: OutputStream) { this.errorStream = err }
        override fun setExitCallback(callback: ExitCallback) { this.exitCallback = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            workerThread = Thread(this, "SSH-Exec-Worker").apply { start() }
        }

        override fun run() {
            val out = outputStream ?: return
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                pb.directory(context.filesDir)
                pb.environment()["HOME"] = context.filesDir.absolutePath
                pb.environment()["PATH"] = "${context.filesDir.absolutePath}/vm/bin:/system/bin:/system/xbin"
                pb.redirectErrorStream(true)

                val proc = pb.start()
                val procOut = proc.inputStream
                val buf = ByteArray(1024)
                while (true) {
                    val n = procOut.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
                proc.waitFor()
                exitCallback?.onExit(proc.exitValue())
            } catch (e: Exception) {
                exitCallback?.onExit(-1, e.message)
            }
        }

        override fun destroy(channel: ChannelSession) {
            workerThread?.interrupt()
        }
    }
}
