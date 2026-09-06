package com.droidhost.data

import com.droidhost.domain.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

 interface AgentRepository {
  suspend fun checkHealth(): Boolean
  suspend fun metrics(): Metrics
  suspend fun containers(all: Boolean = true): List<Container>
  suspend fun container(id: String): ContainerDetail
  suspend fun stats(id: String): ContainerStats
  suspend fun logs(id: String): String
  suspend fun action(id: String, action: String)
  suspend fun pauseContainer(id: String)
  suspend fun unpauseContainer(id: String)
  suspend fun deployContainer(spec: ContainerDeploySpec): ContainerDetail
  suspend fun removeContainer(id: String, force: Boolean = false)
  suspend fun systemDf(): StorageBreakdown
  suspend fun pruneSystem(type: PruneType): PruneResult
  suspend fun composeProjects(): List<ComposeProject>
  suspend fun deployCompose(name: String, yaml: String): ComposeProject
  suspend fun downCompose(name: String)
  suspend fun images(): List<ImageInfo>
  suspend fun volumes(): List<VolumeInfo>
  suspend fun networks(): List<NetworkInfo>
  suspend fun networkDiagnostics(): NetworkDiagnostics
  suspend fun listTerminalSessions(): List<TerminalSessionInfo>
  suspend fun createTerminalSession(id: String, title: String): TerminalSessionInfo
  suspend fun closeTerminalSession(id: String): Boolean
 }

 class HttpAgentRepository(
    baseUrl: String,
    private val tokenProvider: () -> String
 ) : AgentRepository {
  constructor(baseUrl: String, staticToken: String) : this(baseUrl, { staticToken })

  private val jsonParser = Json { 
    ignoreUnknownKeys = true 
    isLenient = true
    coerceInputValues = true
  }

  private val token: String get() = tokenProvider()
  private val http = HttpClient(OkHttp) { 
    install(ContentNegotiation) { 
      json(jsonParser) 
    } 
    engine {
      config {
        connectTimeout(java.time.Duration.ofSeconds(15))
        readTimeout(java.time.Duration.ofSeconds(60))
        writeTimeout(java.time.Duration.ofSeconds(60))
      }
    }
  }
  private val base = baseUrl.trimEnd('/')
  private suspend inline fun <reified T> get(path: String): T {
    val res = http.get(base + path) { header(HttpHeaders.Authorization, "Bearer $token") }
    if (res.status == HttpStatusCode.Unauthorized) {
      throw IllegalStateException("Authentication failed: invalid agent token")
    }
    if (!res.status.isSuccess()) {
      throw IllegalStateException("Request to $path failed with status ${res.status.value}")
    }
    return res.body()
  }
  private suspend inline fun <reified T, reified B> postBody(path: String, body: B): T {
    val res = http.post(base + path) {
      header(HttpHeaders.Authorization, "Bearer $token")
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    if (res.status == HttpStatusCode.Unauthorized) {
      throw IllegalStateException("Authentication failed: invalid agent token")
    }
    if (!res.status.isSuccess()) {
      throw IllegalStateException("Request to $path failed with status ${res.status.value}")
    }
    return res.body()
  }

  override suspend fun checkHealth(): Boolean = runCatching { http.get("$base/health").status == HttpStatusCode.OK }.getOrDefault(false)
  override suspend fun metrics(): Metrics = runCatching { get<Metrics>("/v1/metrics") }.getOrDefault(Metrics(online = false))
  override suspend fun containers(all: Boolean): List<Container> = runCatching { get<List<Container>>("/v1/containers?all=${if (all) 1 else 0}") }.getOrDefault(emptyList())
  override suspend fun container(id: String) = get<ContainerDetail>("/v1/containers/$id/inspect")
  override suspend fun stats(id: String) = get<ContainerStats>("/v1/containers/$id/stats")
  override suspend fun logs(id: String): String = runCatching { 
    val result = get<Map<String, String>>("/v1/containers/$id/logs")
    result["logs"].orEmpty() 
  }.getOrDefault("")
  override suspend fun action(id: String, action: String) { http.post(base + "/v1/containers/$id/$action") { header(HttpHeaders.Authorization, "Bearer $token") } }
  override suspend fun pauseContainer(id: String) = action(id, "pause")
  override suspend fun unpauseContainer(id: String) = action(id, "unpause")
  override suspend fun deployContainer(spec: ContainerDeploySpec): ContainerDetail = postBody("/v1/containers/deploy", spec)
  override suspend fun removeContainer(id: String, force: Boolean) { http.post(base + "/v1/containers/$id/remove?force=${if (force) 1 else 0}") { header(HttpHeaders.Authorization, "Bearer $token") } }
  override suspend fun systemDf(): StorageBreakdown = runCatching { get<StorageBreakdown>("/v1/system/df") }.getOrDefault(StorageBreakdown())
  override suspend fun pruneSystem(type: PruneType): PruneResult = postBody("/v1/system/prune?type=${type.name.lowercase()}", mapOf<String, String>())
  override suspend fun composeProjects(): List<ComposeProject> = runCatching { get<List<ComposeProject>>("/v1/compose/projects") }.getOrDefault(emptyList())
  override suspend fun deployCompose(name: String, yaml: String): ComposeProject = postBody("/v1/compose/projects", mapOf("name" to name, "yaml" to yaml))
  override suspend fun downCompose(name: String) { http.post(base + "/v1/compose/projects/$name/down") { header(HttpHeaders.Authorization, "Bearer $token") } }
  override suspend fun images(): List<ImageInfo> = runCatching { get<List<ImageInfo>>("/v1/images") }.getOrDefault(emptyList())
  override suspend fun volumes(): List<VolumeInfo> = runCatching { get<List<VolumeInfo>>("/v1/volumes") }.getOrDefault(emptyList())
  override suspend fun networks(): List<NetworkInfo> = runCatching { get<List<NetworkInfo>>("/v1/networks") }.getOrDefault(emptyList())
  override suspend fun networkDiagnostics(): NetworkDiagnostics = runCatching { 
    get<NetworkDiagnostics>("/v1/network/diagnostics") 
  }.onFailure { 
    android.util.Log.e("AgentRepo", "networkDiagnostics failed", it) 
  }.getOrDefault(NetworkDiagnostics(error = "Failed to fetch diagnostics"))

  override suspend fun listTerminalSessions(): List<TerminalSessionInfo> = runCatching {
    get<List<TerminalSessionInfo>>("/v1/terminal/sessions")
  }.getOrDefault(emptyList())

  override suspend fun createTerminalSession(id: String, title: String): TerminalSessionInfo = runCatching {
    postBody<TerminalSessionInfo, Map<String, String>>("/v1/terminal/sessions", mapOf("id" to id, "title" to title))
  }.getOrElse { TerminalSessionInfo(id = id, title = title) }

  override suspend fun closeTerminalSession(id: String): Boolean = runCatching {
    val res = http.delete("$base/v1/terminal/sessions/$id") {
      header(HttpHeaders.Authorization, "Bearer $token")
    }
    res.status.isSuccess()
  }.getOrDefault(false)
 }

