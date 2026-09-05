package com.droidhost.service

import android.content.Context
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

class MockAgentServer(
    val context: Context? = null,
    val port: Int = 8899,
    private val agentPort: Int = port,
    private val webPort: Int = 8080,
    private val coolifyPort: Int = 8000,
    private val host: String = "127.0.0.1"
) {
    private val tag = "MockAgentServer"
    private val running = AtomicBoolean(false)

    private var agentServerSocket: ServerSocket? = null
    private var webServerSocket: ServerSocket? = null
    private var coolifyServerSocket: ServerSocket? = null
    private var embeddedSshServer: DroidHostSshServer? = null
    val sshPort: Int = 2222

    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val startTime = System.currentTimeMillis()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val containers = ConcurrentHashMap<String, Container>().apply {
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

    private val images = mutableListOf(
        ImageInfo(id = "sha256:9a61b8f108d4", repoTags = listOf("nginx:alpine", "nginx:latest"), size = 41943040L, created = 1709000000L),
        ImageInfo(id = "sha256:7f481a5a01cb", repoTags = listOf("redis:7-alpine"), size = 33554432L, created = 1709100000L),
        ImageInfo(id = "sha256:4d8123fa9012", repoTags = listOf("postgres:16-alpine"), size = 125829120L, created = 1709200000L)
    )

    private val volumes = mutableListOf(
        VolumeInfo(name = "droidhost_web_data", driver = "local", mountpoint = "/var/lib/docker/volumes/droidhost_web_data/_data"),
        VolumeInfo(name = "redis_cache_data", driver = "local", mountpoint = "/var/lib/docker/volumes/redis_cache_data/_data"),
        VolumeInfo(name = "pg_db_data", driver = "local", mountpoint = "/var/lib/docker/volumes/pg_db_data/_data")
    )

    private val networks = mutableListOf(
        NetworkInfo(id = "net-bridge-1", name = "bridge", driver = "bridge", scope = "local"),
        NetworkInfo(id = "net-host-1", name = "host", driver = "host", scope = "local"),
        NetworkInfo(id = "net-droid-1", name = "droidhost-net", driver = "bridge", scope = "local")
    )

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.d(tag, "MockAgentServer is already running")
            return
        }

        // 1. Bind Agent Server on 8899
        try {
            val agentSocket = ServerSocket()
            agentSocket.reuseAddress = true
            agentSocket.bind(InetSocketAddress(host, agentPort), 50)
            agentServerSocket = agentSocket
            Log.i(tag, "MockAgentServer agent listening on http://$host:$agentPort")

            serverJob = scope.launch {
                while (running.get() && !agentSocket.isClosed) {
                    try {
                        val client = agentSocket.accept()
                        launch { handleAgentClient(client) }
                    } catch (e: Exception) {
                        if (!running.get() || agentSocket.isClosed) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to bind agent socket on $host:$agentPort: ${e.message}")
        }

        // 2. Bind droid-web Web Server on 0.0.0.0:8080
        try {
            val webSocket = ServerSocket()
            webSocket.reuseAddress = true
            webSocket.bind(InetSocketAddress("0.0.0.0", webPort), 50)
            webServerSocket = webSocket
            Log.i(tag, "MockAgentServer web listening on http://localhost:$webPort")

            scope.launch {
                while (running.get() && !webSocket.isClosed) {
                    try {
                        val client = webSocket.accept()
                        launch { handleWebClient(client, isCoolify = false) }
                    } catch (_: Exception) {
                        if (!running.get() || webSocket.isClosed) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to bind web socket on port $webPort: ${e.message}")
        }

        // 3. Bind Coolify Web Server on 0.0.0.0:8000
        try {
            val coolifySocket = ServerSocket()
            coolifySocket.reuseAddress = true
            coolifySocket.bind(InetSocketAddress("0.0.0.0", coolifyPort), 50)
            coolifyServerSocket = coolifySocket
            Log.i(tag, "MockAgentServer coolify listening on http://localhost:$coolifyPort")

            scope.launch {
                while (running.get() && !coolifySocket.isClosed) {
                    try {
                        val client = coolifySocket.accept()
                        launch { handleWebClient(client, isCoolify = true) }
                    } catch (_: Exception) {
                        if (!running.get() || coolifySocket.isClosed) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to bind coolify socket on port $coolifyPort: ${e.message}")
        }

        // 4. Start Embedded Interactive SSH Server on port 2222
        try {
            if (context != null) {
                embeddedSshServer = DroidHostSshServer(context, sshPort).apply { start() }
                Log.i(tag, "Embedded interactive SSH Server active on port $sshPort")
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to start embedded SSH server on port $sshPort: ${t.message}")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(tag, "Stopping MockAgentServer...")
        try { agentServerSocket?.close() } catch (_: Exception) {}
        try { webServerSocket?.close() } catch (_: Exception) {}
        try { coolifyServerSocket?.close() } catch (_: Exception) {}
        try { embeddedSshServer?.stop() } catch (_: Exception) {}
        embeddedSshServer = null
        serverJob?.cancel()
        scope.cancel()
    }

    private fun readHttpLine(input: InputStream): String? {
        val baos = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString("UTF-8")
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                baos.write(b)
            }
        }
        return baos.toString("UTF-8")
    }

    private suspend fun handleAgentClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readHttpLine(input) ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = fullPath.substringBefore('?')

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
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
            try { if (!socket.isClosed) socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun handleWebClient(socket: Socket, isCoolify: Boolean) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            reader.readLine() // Read request line

            val html = if (isCoolify) getCoolifyHtml() else getDroidWebHtml()
            val bytes = html.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun getDroidWebHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>DroidHost &middot; nginx:alpine</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                        background: linear-gradient(135deg, #090d16 0%, #0f172a 50%, #1e1b4b 100%);
                        color: #f8fafc;
                        min-height: 100vh;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        padding: 24px;
                    }
                    .container {
                        max-width: 580px;
                        width: 100%;
                        background: rgba(30, 41, 59, 0.75);
                        backdrop-filter: blur(16px);
                        -webkit-backdrop-filter: blur(16px);
                        border: 1px solid rgba(255, 255, 255, 0.12);
                        border-radius: 20px;
                        padding: 32px 28px;
                        box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
                    }
                    .badge {
                        display: inline-flex;
                        align-items: center;
                        gap: 8px;
                        padding: 5px 14px;
                        border-radius: 9999px;
                        background: rgba(34, 197, 94, 0.15);
                        color: #4ade80;
                        font-size: 13px;
                        font-weight: 600;
                        margin-bottom: 18px;
                    }
                    .dot {
                        width: 8px;
                        height: 8px;
                        border-radius: 50%;
                        background: #22c55e;
                        box-shadow: 0 0 10px #22c55e;
                    }
                    h1 { font-size: 26px; font-weight: 800; margin-bottom: 8px; color: #ffffff; letter-spacing: -0.02em; }
                    p.subtitle { color: #94a3b8; font-size: 14px; margin-bottom: 24px; line-height: 1.5; }
                    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 24px; }
                    .card {
                        background: rgba(15, 23, 42, 0.65);
                        border: 1px solid rgba(255, 255, 255, 0.08);
                        border-radius: 12px;
                        padding: 14px 16px;
                    }
                    .card-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em; color: #64748b; margin-bottom: 4px; font-weight: 700; }
                    .card-val { font-size: 15px; font-weight: 600; color: #e2e8f0; }
                    .links { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 8px; }
                    .link-btn {
                        display: inline-flex;
                        align-items: center;
                        padding: 8px 16px;
                        background: #0d5c63;
                        color: white;
                        text-decoration: none;
                        border-radius: 8px;
                        font-size: 13px;
                        font-weight: 600;
                    }
                    .link-btn:hover { background: #13757e; }
                    .footer { text-align: center; color: #64748b; font-size: 12px; margin-top: 24px; border-top: 1px solid rgba(255,255,255,0.06); padding-top: 16px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="badge"><span class="dot"></span> Online &middot; Container Serving</div>
                    <h1>DroidHost Web Server</h1>
                    <p class="subtitle">This page is served live from the <strong>droid-web</strong> Nginx container on Android loopback port <strong>8080</strong>.</p>
                    <div class="grid">
                        <div class="card">
                            <div class="card-label">Container</div>
                            <div class="card-val">droid-web</div>
                        </div>
                        <div class="card">
                            <div class="card-label">Image</div>
                            <div class="card-val">nginx:alpine</div>
                        </div>
                        <div class="card">
                            <div class="card-label">Port Forwarding</div>
                            <div class="card-val">0.0.0.0:8080 &rarr; 80/tcp</div>
                        </div>
                        <div class="card">
                            <div class="card-label">Architecture</div>
                            <div class="card-val">ARM64 Linux VM</div>
                        </div>
                    </div>
                    <div class="links">
                        <a href="http://localhost:8899/v1/metrics" target="_blank" class="link-btn">View Metrics API</a>
                        <a href="http://localhost:8000" target="_blank" class="link-btn" style="background: #e07a5f;">Coolify Console (8000)</a>
                    </div>
                    <div class="footer">DroidHost &middot; Your phone. Your server.</div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getCoolifyHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Coolify &middot; Self-Hosting on DroidHost</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: #0f172a;
                        color: #f8fafc;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 24px;
                    }
                    .container {
                        max-width: 580px;
                        width: 100%;
                        background: #1e293b;
                        border: 1px solid rgba(255,255,255,0.1);
                        border-radius: 16px;
                        padding: 32px;
                    }
                    .badge { display: inline-block; padding: 4px 12px; border-radius: 999px; background: #6366f1; color: white; font-size: 12px; font-weight: 700; margin-bottom: 12px; }
                    h1 { font-size: 24px; margin-bottom: 8px; }
                    p { color: #94a3b8; font-size: 14px; line-height: 1.5; margin-bottom: 20px; }
                    .card { background: #0f172a; border-radius: 10px; padding: 14px; margin-bottom: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="badge">Coolify on DroidHost</div>
                    <h1>Coolify Dashboard</h1>
                    <p>Self-hosting applications, Git repositories, and databases running on Docker on Android.</p>
                    <div class="card">
                        <strong>Deployment Endpoint:</strong> http://localhost:8000
                    </div>
                    <div class="card">
                        <strong>Status:</strong> Ready for Git repository deployment
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
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
                    "Type 'docker ps', 'sudo apt update', 'clear', or 'help'\r\n\r\n" +
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

                when (opcode) {
                    0x8 -> { // Close
                        try {
                            synchronized(output) {
                                output.write(byteArrayOf(0x88.toByte(), 0x00))
                                output.flush()
                            }
                        } catch (_: Exception) {}
                        break
                    }
                    0x9 -> { // Ping -> Pong
                        try {
                            synchronized(output) {
                                output.write(byteArrayOf(0x8A.toByte(), 0x00))
                                output.flush()
                            }
                        } catch (_: Exception) {}
                    }
                    0xA -> { // Pong -> ignore
                    }
                    0x1, 0x2 -> { // Text or Binary frame
                        val rawText = String(payload, Charsets.UTF_8).trim()
                        val reply = processShellCommand(rawText)
                        sendWsTextFrame(output, reply)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun processShellCommand(rawText: String): String {
        if (rawText.isEmpty()) return "\r\ndroidhost:~$ "

        // Control characters
        if (rawText == "\u0003") return "^C\r\ndroidhost:~$ "
        if (rawText == "\u0004") return "exit\r\ndroidhost:~$ "
        if (rawText == "\u001a") return "^Z\r\n[1]+ Stopped\r\ndroidhost:~$ "

        // Normalize command
        var cmd = rawText.trim()
        if (cmd.startsWith("sudo", ignoreCase = true)) {
            cmd = cmd.substring(4).trim()
        }

        val tokens = cmd.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val first = tokens.firstOrNull() ?: ""
        val second = if (tokens.size > 1) tokens[1] else ""
        val lower = cmd.lowercase()
        val lowerCmd = lower

        return when {
            // 1. Clear command
            first in listOf("clear", "cls", "clea", "clr") || lowerCmd.endsWith("clear") || lowerCmd.endsWith("clea") -> {
                "\u001b[2J\u001b[H\u001bcdroidhost:~$ "
            }

            // 2. apt update / sudo apt update (including typos like updaet, udpate)
            first in listOf("apt", "apt-get") && (second.startsWith("upd") || second == "update" || second == "updaet") -> {
                buildString {
                    appendLine("\r\nHit:1 http://deb.debian.org/debian bookworm InRelease")
                    appendLine("Get:2 http://deb.debian.org/debian-security bookworm-security InRelease [48.0 kB]")
                    appendLine("Get:3 http://deb.debian.org/debian bookworm-updates InRelease [55.4 kB]")
                    appendLine("Fetched 103 kB in 1s (85.2 kB/s)")
                    appendLine("Reading package lists... Done")
                    appendLine("Building dependency tree... Done")
                    appendLine("Reading state information... Done")
                    appendLine("All packages are up to date.")
                    append("droidhost:~$ ")
                }
            }

            // 3. apt install ...
            lower.startsWith("apt install") || lower.startsWith("apt-get install") -> {
                val pkg = cmd.split(" ").drop(2).joinToString(" ")
                buildString {
                    appendLine("\r\nReading package lists... Done")
                    appendLine("Building dependency tree... Done")
                    appendLine("Reading state information... Done")
                    appendLine("The following NEW packages will be installed:")
                    appendLine("  ${if (pkg.isNotBlank()) pkg else "build-essential curl git"}")
                    appendLine("0 upgraded, 1 newly installed, 0 to remove.")
                    appendLine("Setting up packages... Done.")
                    append("droidhost:~$ ")
                }
            }

            // 4. Coolify installation script
            lower.contains("coolify") && (lower.contains("install.sh") || lower.contains("curl") || lower.contains("bash")) -> {
                // Ensure Coolify is in the container list
                containers["coolify-app"] = Container(
                    id = "coolify-app",
                    names = listOf("/coolify"),
                    image = "ghcr.io/coollabsio/coolify:latest",
                    imageId = "sha256:8899aabbccdd",
                    command = "coolify-server",
                    created = System.currentTimeMillis() / 1000,
                    state = "running",
                    status = "Up just now",
                    ports = listOf(Port(ip = "0.0.0.0", privatePort = 8000, publicPort = 8000, type = "tcp")),
                    labels = mapOf("app" to "coolify")
                )
                buildString {
                    appendLine("\r\n--------------------------------------------------")
                    appendLine("Welcome to Coolify self-hosting installation!")
                    appendLine("--------------------------------------------------")
                    appendLine("[+] Host environment: aarch64 (ARM64 Linux VM)")
                    appendLine("[+] Docker daemon detected: unix:///var/run/docker.sock")
                    appendLine("[+] Generating encryption keys in /data/coolify/source/.env")
                    appendLine("[+] Pulling Coolify images from ghcr.io:")
                    appendLine("    - ghcr.io/coollabsio/coolify:latest")
                    appendLine("    - ghcr.io/coollabsio/coolify-helper:latest")
                    appendLine("    - redis:alpine")
                    appendLine("    - postgres:16-alpine")
                    appendLine("[+] Starting Coolify Docker compose stack...")
                    appendLine("[+] Healthcheck passed on port 8000")
                    appendLine("--------------------------------------------------")
                    appendLine("SUCCESS: Coolify is now running!")
                    appendLine("Open your Android Chrome browser at:")
                    appendLine("  -> http://localhost:8000")
                    appendLine("  -> http://127.0.0.1:8000")
                    appendLine("--------------------------------------------------")
                    append("droidhost:~$ ")
                }
            }

            // 5. Git clone ...
            lower.startsWith("git clone") -> {
                val repo = cmd.removePrefix("git clone").trim()
                val repoName = repo.substringAfterLast('/').removeSuffix(".git").ifBlank { "app" }
                buildString {
                    appendLine("\r\nCloning into '$repoName'...")
                    appendLine("remote: Enumerating objects: 382, done.")
                    appendLine("remote: Counting objects: 100% (382/382), done.")
                    appendLine("remote: Compressing objects: 100% (194/194), done.")
                    appendLine("Receiving objects: 100% (382/382), 2.45 MiB | 5.20 MiB/s, done.")
                    appendLine("Resolving deltas: 100% (180/180), done.")
                    append("droidhost:~$ ")
                }
            }

            // 6. Docker commands
            lower.startsWith("docker ps") -> {
                val showAll = lower.contains("-a")
                buildString {
                    appendLine("\r\nCONTAINER ID   IMAGE                               COMMAND                  CREATED         STATUS         PORTS                    NAMES")
                    val list = if (showAll) containers.values else containers.values.filter { it.state.equals("running", ignoreCase = true) }
                    list.forEach { c ->
                        val portStr = c.ports.firstOrNull()?.let { "0.0.0.0:${it.publicPort}->${it.privatePort}/tcp" } ?: ""
                        appendLine("%-14s %-35s %-24s %-15s %-14s %-24s %s".format(
                            c.id.take(12),
                            c.image.take(34),
                            c.command.take(22),
                            "recently",
                            c.status,
                            portStr,
                            c.names.firstOrNull() ?: ""
                        ))
                    }
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("docker images") -> {
                buildString {
                    appendLine("\r\nREPOSITORY                    TAG       IMAGE ID       CREATED         SIZE")
                    appendLine("nginx                         alpine    9a61b8f108d4   2 days ago      41.9MB")
                    appendLine("redis                         7-alpine  7f481a5a01cb   3 days ago      33.5MB")
                    appendLine("postgres                      16-alpine 4d8123fa9012   1 week ago      125MB")
                    appendLine("ghcr.io/coollabsio/coolify    latest    8899aabbccdd   5 hours ago     240MB")
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("docker version") || lower.startsWith("docker -v") -> {
                buildString {
                    appendLine("\r\nDocker version 26.0.0, build 2ae903e")
                    appendLine("Server: Docker Engine - Community")
                    appendLine(" Engine Version: 26.0.0 (API: 1.45, Arch: arm64)")
                    append("droidhost:~$ ")
                }
            }

            // 7. System info
            lower.startsWith("uname") -> {
                "\r\nLinux droidhost 6.6.0-arm64 #1 SMP PREEMPT Sun Sep 5 2026 aarch64 GNU/Linux\r\ndroidhost:~$ "
            }

            lower == "whoami" -> {
                "\r\nroot\r\ndroidhost:~$ "
            }

            lower == "pwd" -> {
                "\r\n/root\r\ndroidhost:~$ "
            }

            lower == "id" -> {
                "\r\nuid=0(root) gid=0(root) groups=0(root)\r\ndroidhost:~$ "
            }

            lower == "ls" || lower == "ll" || lower.startsWith("ls ") -> {
                buildString {
                    appendLine("\r\ntotal 24")
                    appendLine("drwx------ 4 root root 4096 Sep  5 22:00 .")
                    appendLine("drwxr-xr-x 1 root root 4096 Sep  5 20:00 ..")
                    appendLine("-rw-r--r-- 1 root root  220 Sep  5 20:00 .bashrc")
                    appendLine("drwxr-xr-x 3 root root 4096 Sep  5 22:10 data")
                    appendLine("-rw-r--r-- 1 root root  450 Sep  5 22:15 docker-compose.yml")
                    appendLine("drwxr-xr-x 2 root root 4096 Sep  5 22:20 projects")
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("uptime") -> {
                val sec = (System.currentTimeMillis() - startTime) / 1000
                "\r\n 22:45:00 up ${sec / 60} min, 1 user, load average: 0.14, 0.08, 0.05\r\ndroidhost:~$ "
            }

            lower.startsWith("free") -> {
                "\r\n              total        used        free      shared  buff/cache   available\r\nMem:           2048         712        1120          16         216        1336\r\nSwap:             0           0           0\r\ndroidhost:~$ "
            }

            lower == "ip a" || lower == "ifconfig" || lower.startsWith("ip addr") -> {
                buildString {
                    appendLine("\r\n1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN")
                    appendLine("    inet 127.0.0.1/8 scope host lo")
                    appendLine("2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP")
                    appendLine("    inet 10.0.2.15/24 brd 10.0.2.255 scope global eth0")
                    appendLine("3: docker0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP")
                    appendLine("    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0")
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("curl") || lower.startsWith("wget") -> {
                buildString {
                    appendLine("\r\nHTTP/1.1 200 OK")
                    appendLine("Server: DroidHost Linux Gateway")
                    appendLine("Content-Type: text/plain; charset=utf-8")
                    appendLine("Content-Length: 18")
                    appendLine("")
                    appendLine("Connection success")
                    append("droidhost:~$ ")
                }
            }

            lower in listOf("cloudflared version", "cloudflared -v", "cloudflared --version") -> {
                buildString {
                    appendLine("\r\ncloudflared version 2026.8.3 (built 2026-08-31-10:05 UTC)")
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("cloudflared") -> {
                buildString {
                    appendLine("\r\n2026-09-06T00:16:00Z INF Starting tunnel")
                    appendLine("2026-09-06T00:16:01Z INF Version 2026.8.3 (built 2026-08-31-10:05 UTC) (linux-arm64)")
                    appendLine("2026-09-06T00:16:02Z INF Initializing connection with Cloudflare edge network...")
                    appendLine("2026-09-06T00:16:03Z INF Registered tunnel connection connIndex=0 connection=cf-edge-ord location=ORD")
                    appendLine("2026-09-06T00:16:04Z INF Registered tunnel connection connIndex=1 connection=cf-edge-fra location=FRA")
                    appendLine("2026-09-06T00:16:05Z INF Tunnel is live and active! Ingress forwarding: ssh -> localhost:2222")
                    append("droidhost:~$ ")
                }
            }

            lower.contains("cloudflared") && (lower.startsWith("kill") || lower.startsWith("pkill")) -> {
                buildString {
                    appendLine("\r\n2026-09-06T00:16:10Z INF Tunnel disconnection initiated")
                    appendLine("2026-09-06T00:16:11Z INF Cloudflare Tunnel stopped successfully")
                    append("droidhost:~$ ")
                }
            }

            lower == "help" -> {
                buildString {
                    appendLine("\r\nDroidHost ARM64 Linux VM Shell")
                    appendLine("Available commands:")
                    appendLine("  sudo apt update               - Update package repositories")
                    appendLine("  sudo apt install <pkg>        - Install software packages")
                    appendLine("  clear                         - Clear terminal screen")
                    appendLine("  docker ps [-a]                - List active Docker containers")
                    appendLine("  docker images                 - List local images")
                    appendLine("  curl -fsSL https://cdn.coollabs.io/coolify/install.sh | bash")
                    appendLine("                                - Install Coolify self-hosting")
                    appendLine("  git clone <url>               - Clone any Git repository")
                    appendLine("  uname -a, uptime, free -m     - System diagnostics")
                    appendLine("  ip a, ls, pwd, whoami         - Navigation & network")
                    appendLine("")
                    appendLine("Access web services in Android Chrome:")
                    appendLine("  - http://localhost:8080 (droid-web Nginx)")
                    appendLine("  - http://localhost:8000 (Coolify Console)")
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("tailscale") -> {
                buildString {
                    appendLine("\r\n100.83.94.110   droidhost-phone      gaurav@      linux   -")
                    appendLine("100.75.120.45   nanoengineer-linux   gaurav@      linux   active; direct 192.168.1.45:41641, tx 5120 rx 4890")
                    appendLine("Tailscale status: Connected. MagicDNS active: droidhost-phone.tailnet.ts.net")
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("systemctl") || lower.startsWith("service") -> {
                buildString {
                    appendLine("\r\n● ssh.service - OpenBSD Secure Shell server")
                    appendLine("     Loaded: loaded (/lib/systemd/system/ssh.service; enabled; vendor preset: enabled)")
                    appendLine("     Active: active (running) since Sun 2026-09-06 00:00:01 IST; 45min ago")
                    appendLine("       Docs: man:sshd(8)")
                    appendLine("   Main PID: 2222 (sshd)")
                    appendLine("      Tasks: 2 (limit: 4915)")
                    appendLine("     Memory: 4.8M")
                    appendLine("     CGroup: /system.slice/ssh.service")
                    appendLine("             └─2222 sshd: /usr/sbin/sshd -D [listener] port 2222")
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("docker run") || lower.startsWith("docker pull") -> {
                val image = cmd.split(" ").lastOrNull()?.trim() ?: "ubuntu"
                buildString {
                    appendLine("\r\nUsing default tag: latest")
                    appendLine("latest: Pulling from library/$image")
                    appendLine("7a23c34d8e5f: Pulling fs layer")
                    appendLine("b2c3d4e5f6a1: Downloading [====================>          ] 18.2MB/32.5MB")
                    appendLine("b2c3d4e5f6a1: Verifying Checksum")
                    appendLine("b2c3d4e5f6a1: Download complete")
                    appendLine("7a23c34d8e5f: Extracting [================================>] 32.5MB/32.5MB")
                    appendLine("Digest: sha256:7f8e9d0c1b2a34567890abcdef1234567890abcdef1234567890abcdef123456")
                    appendLine("Status: Downloaded newer image for $image:latest")
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("ping") -> {
                val host = cmd.split(" ").lastOrNull()?.trim() ?: "google.com"
                buildString {
                    appendLine("\r\nPING $host (142.250.192.46) 56(84) bytes of data.")
                    appendLine("64 bytes from $host: icmp_seq=1 ttl=118 time=14.2 ms")
                    appendLine("64 bytes from $host: icmp_seq=2 ttl=118 time=13.8 ms")
                    appendLine("64 bytes from $host: icmp_seq=3 ttl=118 time=14.5 ms")
                    appendLine("--- $host ping statistics ---")
                    appendLine("3 packets transmitted, 3 received, 0% packet loss, time 2002ms")
                    appendLine("rtt min/avg/max/mdev = 13.812/14.170/14.512/0.286 ms")
                    append("droidhost:~$ ")
                }
            }

            lower.startsWith("python") || lower == "python3" -> {
                buildString {
                    appendLine("\r\nPython 3.11.2 (main, Sep  5 2026, 20:15:00) [GCC 12.2.0] on linux")
                    appendLine("Type \"help\", \"copyright\", \"credits\" or \"license\" for more information.")
                    append(">>> ")
                }
            }

            lower.startsWith("echo ") -> {
                val text = cmd.substring(5).trim().trim('"', '\'')
                "\r\n$text\r\ndroidhost:~$ "
            }

            lower.startsWith("mkdir ") || lower.startsWith("touch ") || lower.startsWith("rm ") || lower.startsWith("cd ") -> {
                try {
                    val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                    if (context != null) pb.directory(context.filesDir)
                    pb.start().waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) {}
                "\r\ndroidhost:~$ "
            }

            lower.startsWith("cat ") -> {
                val path = cmd.substring(4).trim()
                val content = when {
                    path.contains("os-release") -> "PRETTY_NAME=\"Debian GNU/Linux 12 (bookworm)\"\nNAME=\"Debian GNU/Linux\"\nVERSION_ID=\"12\"\nVERSION=\"12 (bookworm)\"\nID=debian\nHOME_URL=\"https://www.debian.org/\""
                    path.contains(".bashrc") -> "# ~/.bashrc: executed by bash(1) for non-login shells.\nexport PS1='droidhost:\\w\\$ '\nalias ll='ls -la'\nalias la='ls -A'"
                    path.contains("docker-compose") -> "version: '3.8'\nservices:\n  droid-web:\n    image: nginx:alpine\n    ports:\n      - '8080:80'"
                    else -> {
                        try {
                            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                            if (context != null) pb.directory(context.filesDir)
                            val out = pb.start().inputStream.bufferedReader().readText()
                            out.ifBlank { "No such file or directory: $path" }
                        } catch (_: Exception) {
                            "No such file or directory: $path"
                        }
                    }
                }
                "\r\n${content.trimEnd()}\r\ndroidhost:~$ "
            }

            else -> {
                val output = try {
                    val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                    if (context != null) pb.directory(context.filesDir)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    val text = proc.inputStream.bufferedReader().readText()
                    proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    text
                } catch (_: Exception) {
                    ""
                }
                if (output.isNotBlank()) {
                    "\r\n${output.trimEnd()}\r\ndroidhost:~$ "
                } else {
                    "\r\ndroidhost:~$ "
                }
            }
        }
    }

    private fun sendWsTextFrame(output: OutputStream, text: String) {
        synchronized(output) {
            try {
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
            } catch (e: Exception) {
                Log.d(tag, "sendWsTextFrame failed: ${e.message}")
            }
        }
    }
}
