package com.droidhost.service

import android.util.Base64
import android.util.Log
import com.droidhost.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight in-process mock HTTP + WebSocket server listening on 127.0.0.1:8899.
 * Emulates the guest vm-agent endpoints when running in pre-setup or emulator testing mode.
 */
class MockAgentServer(
    private val port: Int = 8899,
    private val host: String = "127.0.0.1"
) {
    private val tag = "MockAgentServer"
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val startTime = System.currentTimeMillis()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val containers = ConcurrentHashMap<String, Container>().apply {
        put(
            "c1a2b3c4d5e6",
            Container(
                id = "c1a2b3c4d5e6",
                names = listOf("/droid-web"),
                image = "nginx:alpine",
                imageId = "sha256:9a61b8f108d4",
                command = "nginx -g 'daemon off;'",
                created = 1710000000L,
                state = "running",
                status = "Up 45 minutes",
                ports = listOf(Port(ip = "0.0.0.0", privatePort = 80, publicPort = 8080, type = "tcp")),
                labels = mapOf("app" to "droid-web", "env" to "production")
            )
        )
        put(
            "f7e8d9c0b1a2",
            Container(
                id = "f7e8d9c0b1a2",
                names = listOf("/redis-cache"),
                image = "redis:7-alpine",
                imageId = "sha256:7f481a5a01cb",
                command = "docker-entrypoint.sh redis-server",
                created = 1710000500L,
                state = "running",
                status = "Up 1 hour",
                ports = listOf(Port(ip = "0.0.0.0", privatePort = 6379, publicPort = 6379, type = "tcp")),
                labels = mapOf("app" to "redis-cache", "env" to "production")
            )
        )
        put(
            "d3e4f5a6b7c8",
            Container(
                id = "d3e4f5a6b7c8",
                names = listOf("/pg-database"),
                image = "postgres:16-alpine",
                imageId = "sha256:4d8123fa9012",
                command = "docker-entrypoint.sh postgres",
                created = 1710001000L,
                state = "exited",
                status = "Exited (0) 15 minutes ago",
                ports = listOf(Port(ip = "0.0.0.0", privatePort = 5432, publicPort = 5432, type = "tcp")),
                labels = mapOf("app" to "pg-database", "env" to "production")
            )
        )
    }

    private val images = listOf(
        ImageInfo(id = "sha256:9a61b8f108d4", repoTags = listOf("nginx:alpine", "nginx:latest"), size = 41943040L, created = 1709000000L),
        ImageInfo(id = "sha256:7f481a5a01cb", repoTags = listOf("redis:7-alpine"), size = 33554432L, created = 1709100000L),
        ImageInfo(id = "sha256:4d8123fa9012", repoTags = listOf("postgres:16-alpine"), size = 125829120L, created = 1709200000L)
    )

    private val volumes = listOf(
        VolumeInfo(name = "droidhost_web_data", driver = "local", mountpoint = "/var/lib/docker/volumes/droidhost_web_data/_data"),
        VolumeInfo(name = "redis_cache_data", driver = "local", mountpoint = "/var/lib/docker/volumes/redis_cache_data/_data"),
        VolumeInfo(name = "pg_db_data", driver = "local", mountpoint = "/var/lib/docker/volumes/pg_db_data/_data")
    )

    private val networks = listOf(
        NetworkInfo(id = "net-bridge-1", name = "bridge", driver = "bridge", scope = "local"),
        NetworkInfo(id = "net-host-1", name = "host", driver = "host", scope = "local"),
        NetworkInfo(id = "net-droid-1", name = "droidhost-net", driver = "bridge", scope = "local")
    )

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.d(tag, "MockAgentServer is already running")
            return
        }

        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(host, port), 50)
            serverSocket = s
            Log.i(tag, "MockAgentServer listening on http://$host:$port")

            serverJob = scope.launch {
                while (running.get() && !s.isClosed) {
                    try {
                        val client = s.accept()
                        launch {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        if (!running.get() || s.isClosed) break
                        Log.w(tag, "Error accepting client: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to bind MockAgentServer on $host:$port: ${e.message}")
            running.set(false)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(tag, "Stopping MockAgentServer...")
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = fullPath.substringBefore('?')

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                }
            }

            // Check if WebSocket upgrade
            if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                handleWebSocket(socket, input, output, headers)
                return@withContext
            }

            // Regular HTTP routing
            when {
                path == "/health" && method == "GET" -> {
                    sendJsonResponse(output, 200, """{"status":"ok"}""")
                }
                path == "/v1/metrics" && method == "GET" -> {
                    val uptimeSec = (System.currentTimeMillis() - startTime) / 1000.0
                    val cpu = (6.0 + (System.currentTimeMillis() % 12000) / 1000.0).coerceIn(1.0, 99.0)
                    val memUsed = 734003200L + (System.currentTimeMillis() % 60000000L)
                    val metrics = Metrics(
                        online = true,
                        uptimeSeconds = uptimeSec,
                        cpuPercent = Math.round(cpu * 10.0) / 10.0,
                        memoryTotalBytes = 2147483648L,
                        memoryUsedBytes = memUsed,
                        storageTotalBytes = 17179869184L,
                        storageUsedBytes = 3435973836L,
                        networkRxBytes = 1048576L + (uptimeSec.toLong() * 1024L),
                        networkTxBytes = 524288L + (uptimeSec.toLong() * 512L)
                    )
                    sendJsonResponse(output, 200, json.encodeToString(metrics))
                }
                path == "/v1/containers" && method == "GET" -> {
                    val showAll = fullPath.contains("all=1") || !fullPath.contains("all=0")
                    val list = if (showAll) {
                        containers.values.toList()
                    } else {
                        containers.values.filter { it.state.equals("running", ignoreCase = true) }
                    }
                    sendJsonResponse(output, 200, json.encodeToString(list))
                }
                path.startsWith("/v1/containers/") && path.endsWith("/inspect") && method == "GET" -> {
                    val id = path.removePrefix("/v1/containers/").removeSuffix("/inspect")
                    val container = containers[id] ?: containers.values.firstOrNull { it.id.startsWith(id) }
                    if (container != null) {
                        val detail = ContainerDetail(
                            id = container.id,
                            name = container.names.firstOrNull()?.removePrefix("/") ?: container.id,
                            image = container.image,
                            state = container.state,
                            status = container.status,
                            created = "2026-09-01T12:00:00Z",
                            startedAt = "2026-09-05T20:00:00Z",
                            restartCount = 0,
                            command = listOf(container.command),
                            ports = container.ports,
                            mounts = listOf(Mount(type = "volume", source = "droidhost_data", destination = "/app/data", mode = "rw", rw = true)),
                            networks = mapOf("bridge" to NetworkEndpoint(ipAddress = "172.17.0.2", gateway = "172.17.0.1", macAddress = "02:42:ac:11:00:02")),
                            envKeys = listOf("PATH", "NODE_ENV", "PORT"),
                            labels = container.labels
                        )
                        sendJsonResponse(output, 200, json.encodeToString(detail))
                    } else {
                        sendJsonResponse(output, 404, """{"error":"No such container: $id"}""")
                    }
                }
                path.startsWith("/v1/containers/") && path.endsWith("/stats") && method == "GET" -> {
                    val id = path.removePrefix("/v1/containers/").removeSuffix("/stats")
                    val stats = ContainerStats(
                        cpuPercent = 2.4,
                        memoryUsedBytes = 36700160L,
                        memoryLimitBytes = 2147483648L,
                        memoryPercent = 1.7,
                        networkRxBytes = 142000L,
                        networkTxBytes = 85000L
                    )
                    sendJsonResponse(output, 200, json.encodeToString(stats))
                }
                path.startsWith("/v1/containers/") && path.endsWith("/logs") && method == "GET" -> {
                    val logsText = buildString {
                        appendLine("[2026-09-05 22:15:00] DroidHost service initialized")
                        appendLine("[2026-09-05 22:15:01] Container network binding complete")
                        appendLine("[2026-09-05 22:15:02] Listening on configured endpoints")
                        appendLine("[2026-09-05 22:20:00] System health check passed: OK")
                    }
                    sendJsonResponse(output, 200, json.encodeToString(mapOf("logs" to logsText)))
                }
                path.startsWith("/v1/containers/") && (path.endsWith("/start") || path.endsWith("/stop") || path.endsWith("/restart")) && method == "POST" -> {
                    val action = path.substringAfterLast('/')
                    val id = path.removePrefix("/v1/containers/").substringBefore('/')
                    val container = containers[id] ?: containers.values.firstOrNull { it.id.startsWith(id) }
                    if (container != null) {
                        val newState = when (action) {
                            "start" -> "running" to "Up just now"
                            "stop" -> "exited" to "Exited (0) just now"
                            "restart" -> "running" to "Up just now"
                            else -> container.state to container.status
                        }
                        containers[container.id] = container.copy(state = newState.first, status = newState.second)
                        sendJsonResponse(output, 200, """{"success":true}""")
                    } else {
                        sendJsonResponse(output, 404, """{"error":"No such container: $id"}""")
                    }
                }
                path.startsWith("/v1/containers/") && (path.endsWith("/remove") || path.contains("/remove?")) && method == "POST" -> {
                    val id = path.removePrefix("/v1/containers/").substringBefore('/')
                    containers.remove(id)
                    sendJsonResponse(output, 200, """{"success":true}""")
                }
                path == "/v1/images" && method == "GET" -> {
                    sendJsonResponse(output, 200, json.encodeToString(images))
                }
                path == "/v1/volumes" && method == "GET" -> {
                    sendJsonResponse(output, 200, json.encodeToString(volumes))
                }
                path == "/v1/networks" && method == "GET" -> {
                    sendJsonResponse(output, 200, json.encodeToString(networks))
                }
                else -> {
                    sendJsonResponse(output, 404, """{"error":"Not found: $path"}""")
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                if (!socket.isClosed) socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendJsonResponse(output: OutputStream, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun handleWebSocket(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val secKey = headers["sec-websocket-key"] ?: run {
            socket.close()
            return
        }

        val acceptKey = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                (secKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8)
            ),
            Base64.NO_WRAP
        )

        val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
        output.write(handshake.toByteArray(Charsets.UTF_8))
        output.flush()

        sendWsTextFrame(
            output,
            "\u001b[1;32m[DroidHost ARM64 Linux VM Terminal - Active]\u001b[0m\r\n" +
                    "Linux droidhost 6.6.0-arm64 #1 SMP aarch64\r\n" +
                    "Type 'docker ps', 'uname -a', 'free', or 'help'\r\n\r\n" +
                    "droidhost:~$ "
        )

        // Read frames loop
        try {
            while (running.get() && !socket.isClosed) {
                val b0 = input.read()
                if (b0 == -1) break
                val opcode = b0 and 0x0F
                val b1 = input.read()
                if (b1 == -1) break
                val isMasked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()

                if (len == 126L) {
                    val byte1 = input.read()
                    val byte2 = input.read()
                    len = ((byte1 shl 8) or byte2).toLong()
                } else if (len == 127L) {
                    var acc = 0L
                    for (i in 0..7) {
                        acc = (acc shl 8) or (input.read().toLong() and 0xFF)
                    }
                    len = acc
                }

                val mask = ByteArray(4)
                if (isMasked) {
                    var read = 0
                    while (read < 4) {
                        val r = input.read(mask, read, 4 - read)
                        if (r == -1) return
                        read += r
                    }
                }

                val payload = ByteArray(len.toInt())
                var readBytes = 0
                while (readBytes < payload.size) {
                    val r = input.read(payload, readBytes, payload.size - readBytes)
                    if (r == -1) return
                    readBytes += r
                }

                if (isMasked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }

                if (opcode == 0x8) { // Close
                    break
                } else if (opcode == 0x9) { // Ping
                    // Send Pong (opcode 0xA)
                    output.write(0x8A)
                    output.write(0)
                    output.flush()
                } else if (opcode == 0x1) { // Text
                    val text = String(payload, Charsets.UTF_8).trim()
                    val reply = when {
                        text.isEmpty() -> "\r\ndroidhost:~$ "
                        text.equals("help", ignoreCase = true) -> "\r\nDroidHost VM Shell commands:\r\n  docker ps\r\n  uname -a\r\n  uptime\r\n  free -m\r\n  exit\r\ndroidhost:~$ "
                        text.startsWith("docker ps", ignoreCase = true) -> {
                            buildString {
                                appendLine("\r\nCONTAINER ID   IMAGE          COMMAND                  CREATED         STATUS         PORTS                    NAMES")
                                containers.values.forEach { c ->
                                    val portStr = c.ports.firstOrNull()?.let { "0.0.0.0:${it.publicPort}->${it.privatePort}/tcp" } ?: ""
                                    appendLine("%-14s %-14s %-24s %-15s %-14s %-24s %s".format(c.id, c.image, c.command.take(22), "recently", c.status, portStr, c.names.firstOrNull() ?: ""))
                                }
                                append("droidhost:~$ ")
                            }
                        }
                        text.startsWith("uname", ignoreCase = true) -> "\r\nLinux droidhost 6.6.0-arm64 #1 SMP PREEMPT aarch64 GNU/Linux\r\ndroidhost:~$ "
                        text.startsWith("uptime", ignoreCase = true) -> {
                            val sec = (System.currentTimeMillis() - startTime) / 1000
                            "\r\n 22:30:00 up ${sec / 60} min, 1 user, load average: 0.12, 0.08, 0.04\r\ndroidhost:~$ "
                        }
                        text.startsWith("free", ignoreCase = true) -> {
                            "\r\n              total        used        free      shared  buff/cache   available\r\nMem:           2048         712        1120          16         216        1336\r\nSwap:             0           0           0\r\ndroidhost:~$ "
                        }
                        else -> "\r\n$text: command executed (emulated)\r\ndroidhost:~$ "
                    }
                    sendWsTextFrame(output, reply)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendWsTextFrame(output: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.write(0x81) // FIN + text opcode
        if (bytes.size <= 125) {
            output.write(bytes.size)
        } else if (bytes.size <= 65535) {
            output.write(126)
            output.write((bytes.size shr 8) and 0xFF)
            output.write(bytes.size and 0xFF)
        } else {
            output.write(127)
            for (i in 7 downTo 0) {
                output.write(((bytes.size.toLong() shr (i * 8)) and 0xFF).toInt())
            }
        }
        output.write(bytes)
        output.flush()
    }
}
