package io.github.mangi.eta.agent.image

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * OpenAI 兼容的 `/v1/images/generations` 客户端。
 *
 * 只做单次请求：国产服务的签名与异步任务轮询不在这里（它们的协议各不相同，
 * 塞进来会让这类「一个 base_url + 一个 key」的中转站走不通）。
 */
internal object ImageGenClient {
    private const val CONNECT_TIMEOUT_MS = 15_000L

    /** 生图比对话慢得多：实测中转站普遍 20–90s，给足 180s 但不无限等。 */
    private const val READ_TIMEOUT_MS = 180_000L

    private val client by lazy {
        AgentHttpClient.client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(READ_TIMEOUT_MS + 20_000L, TimeUnit.MILLISECONDS)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private data class PostResult(
        val ok: Boolean,
        val status: Int,
        val body: String,
        val networkError: String?,
    )

    /**
     * @param size 形如 `1024x1024`；留空则由服务端决定。
     * @return 已落盘的文件列表（失败时为空）与错误说明。
     */
    fun generate(
        context: Context,
        config: ImageGenConfig,
        prompt: String,
        size: String,
        count: Int,
    ): ImageGenOutcome {
        if (!config.usable) {
            return ImageGenOutcome.Failure(
                code = "IMAGE_GEN_NOT_CONFIGURED",
                message = "生图未配置：需要在设置里填写中转站 base_url、API Key 与模型名",
            )
        }
        if (prompt.isBlank()) {
            return ImageGenOutcome.Failure("IMAGE_GEN_EMPTY_PROMPT", "prompt 不能为空")
        }
        // 先按模型名把参数掰到合法区间：dall-e-3 只认 n=1、gpt-image 不接受 response_format。
        val rules = ImageGenModelRules.adjust(config.model, size.trim(), count)
        val payload = JSONObject()
            .put("model", config.model)
            .put("prompt", prompt)
            .put("n", rules.n)
        if (!rules.omitResponseFormat) payload.put("response_format", "b64_json")
        rules.size.takeIf { it.isNotEmpty() }?.let { payload.put("size", it) }

        val notes = rules.notes.toMutableList()

        fun post(p: JSONObject): PostResult {
            val request = Request.Builder()
                .url(config.generationsUrl())
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(p.toString().toRequestBody(jsonMedia))
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    PostResult(
                        ok = response.isSuccessful,
                        status = response.code,
                        body = response.body?.string().orEmpty(),
                        networkError = null,
                    )
                }
            } catch (e: IOException) {
                PostResult(false, 0, "", "${e.javaClass.simpleName}: ${e.message ?: "请求失败"}")
            }
        }

        var result = post(payload)
        // 400 且明确指向某个可选参数时，摘掉它再试一次：不同中转站的参数白名单差异很大，
        // 与其让用户去猜，不如自己降级并把降级原因回传。
        if (!result.ok && result.status == 400) {
            val text = result.body.lowercase()
            if (payload.has("response_format") &&
                ("response_format" in text || "unknown" in text || "unrecognized" in text)
            ) {
                payload.remove("response_format")
                notes += "服务端不接受 response_format，已省略该参数重试"
                result = post(payload)
            }
            if (!result.ok && result.status == 400 && payload.has("size") && "size" in text) {
                payload.remove("size")
                notes += "服务端不接受该 size，已省略让服务端决定"
                result = post(payload)
            }
        }

        if (result.networkError != null) {
            // 网络类错误单独给 code：超时和 DNS 失败在中转站场景很常见。
            return ImageGenOutcome.Failure("IMAGE_GEN_NETWORK", result.networkError)
        }
        if (!result.ok) {
            return ImageGenOutcome.Failure(
                code = "IMAGE_GEN_HTTP_${result.status}",
                message = result.body.take(600).ifBlank { "HTTP ${result.status}" },
            )
        }

        return parseAndStore(context, result.body, config.model, notes)
    }

    /**
     * 拉中转站的 `/v1/models`，供设置页直接选模型。
     *
     * suspend + 内部切 IO：这里常被 Compose 的 rememberCoroutineScope（Main）调用，
     * 直接 http 会 NetworkOnMainThreadException 闪退；把调度决定收在 client 内部，调用方不必当心。
     * 不校验 model 字段——用户往往先有 url+key 才去挑模型名。
     */
    suspend fun fetchModels(config: ImageGenConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank() || config.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("先填 base_url 和 API Key"))
        }
        val request = Request.Builder()
            .url(config.modelsUrl())
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}：${text.take(200)}"),
                    )
                }
                val data = JSONObject(text).optJSONArray("data")
                    ?: return@withContext Result.failure(
                        IllegalStateException("响应里没有 data 数组"),
                    )
                val ids = buildList {
                    for (i in 0 until data.length()) {
                        data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.distinct().sorted()
                if (ids.isEmpty()) Result.failure(IllegalStateException("模型列表为空"))
                else Result.success(ids)
            }
        } catch (e: IOException) {
            Result.failure(IllegalStateException("${e.javaClass.simpleName}: ${e.message ?: "请求失败"}"))
        } catch (e: org.json.JSONException) {
            Result.failure(IllegalStateException("返回不是合法 JSON：${e.message}"))
        }
    }

    private fun parseAndStore(
        context: Context,
        raw: String,
        model: String,
        notes: List<String>,
    ): ImageGenOutcome {
        val root = JSONObject(raw)
        val data = root.optJSONArray("data")
        if (data == null) {
            // 异步任务制服务会回 task id 而不是图：明确告诉模型这条路走不通，
            // 而不是丢一个 "BAD_RESPONSE" 让它反复重试。
            val taskId = root.optString("task_id").ifBlank { root.optString("id") }
                .ifBlank { root.optJSONObject("output")?.optString("task_id").orEmpty() }
            if (taskId.isNotBlank() || root.has("status") || root.has("task_status")) {
                val hint = if (ImageGenModelRules.isAsyncFamily(model)) {
                    "该模型是中转站上的异步任务制（seedream/可灵一类），当前只支持同步返回。"
                } else {
                    "服务端返回了任务 id 而非图片。"
                }
                return ImageGenOutcome.Failure(
                    code = "IMAGE_GEN_ASYNC_TASK",
                    message = hint + "需要轮询接口，当前实现不支持；换支持同步返回的模型" +
                        "（gpt-image / nano-banana / flux 系列）。原始响应：" + raw.take(300),
                )
            }
            return ImageGenOutcome.Failure("IMAGE_GEN_BAD_RESPONSE", raw.take(600))
        }
        if (data.length() == 0) {
            return ImageGenOutcome.Failure("IMAGE_GEN_EMPTY_RESPONSE", raw.take(600))
        }
        val dir = File(context.filesDir, "generated-images").apply { mkdirs() }
        val files = mutableListOf<File>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val b64 = item.optString("b64_json").takeIf { it.isNotBlank() }
            val bytes = if (b64 != null) {
                runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            } else {
                // 部分中转站忽略 response_format，仍回 url：再拉一次。
                item.optString("url").takeIf { it.isNotBlank() }?.let(::download)
            }
            if (bytes == null || bytes.isEmpty()) continue
            val file = File(dir, "gen-${System.currentTimeMillis()}-$index.${extensionOf(bytes)}")
            runCatching { file.writeBytes(bytes) }.getOrElse { continue }
            files += file
        }
        return if (files.isEmpty()) {
            ImageGenOutcome.Failure("IMAGE_GEN_DECODE_FAILED", "返回内容里没有可解析的图片数据")
        } else {
            ImageGenOutcome.Success(ImageGenResult(files = files, model = model, notes = notes))
        }
    }

    private fun download(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }.getOrNull()

    /** 按魔数判扩展名，别信 Content-Type：决定后续 BitmapFactory 能否识别。 */
    private fun extensionOf(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "webp"
        else -> "png"
    }

    private const val MAX_IMAGES = 4
}
