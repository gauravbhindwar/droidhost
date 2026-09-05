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
  suspend fun removeContainer(id: String, force: Boolean = false)
  suspend fun images(): List<ImageInfo>
  suspend fun volumes(): List<VolumeInfo>
  suspend fun networks(): List<NetworkInfo>
 }

 class HttpAgentRepository(baseUrl: String, private val token: String) : AgentRepository {
  private val http = HttpClient(OkHttp) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
  private val base = baseUrl.trimEnd('/')
  private suspend inline fun <reified T> get(path: String): T = http.get(base + path) { header(HttpHeaders.Authorization, "Bearer $token") }.body()
  override suspend fun checkHealth(): Boolean = runCatching { http.get("$base/health").status == HttpStatusCode.OK }.getOrDefault(false)
  override suspend fun metrics() = get<Metrics>("/v1/metrics")
  override suspend fun containers(all: Boolean) = get<List<Container>>("/v1/containers?all=${if (all) 1 else 0}")
  override suspend fun container(id: String) = get<ContainerDetail>("/v1/containers/$id/inspect")
  override suspend fun stats(id: String) = get<ContainerStats>("/v1/containers/$id/stats")
  override suspend fun logs(id: String): String { val result = get<Map<String, String>>("/v1/containers/$id/logs"); return result["logs"].orEmpty() }
  override suspend fun action(id: String, action: String) { http.post(base + "/v1/containers/$id/$action") { header(HttpHeaders.Authorization, "Bearer $token") } }
  override suspend fun removeContainer(id: String, force: Boolean) { http.post(base + "/v1/containers/$id/remove?force=${if (force) 1 else 0}") { header(HttpHeaders.Authorization, "Bearer $token") } }
  override suspend fun images() = get<List<ImageInfo>>("/v1/images")
  override suspend fun volumes() = get<List<VolumeInfo>>("/v1/volumes")
  override suspend fun networks() = get<List<NetworkInfo>>("/v1/networks")
 }
