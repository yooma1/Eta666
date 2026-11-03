package io.github.mangi.eta.agent.image

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.File
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
        val payload = JSONObject()
            .put("model", config.model)
            .put("prompt", prompt)
            .put("n", count.coerceIn(1, MAX_IMAGES))
            .put("response_format", "b64_json")
        size.trim().takeIf { it.isNotEmpty() }?.let { payload.put("size", it) }

        val request = Request.Builder()
            .url(config.generationsUrl())
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        val body = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return ImageGenOutcome.Failure(
                        code = "IMAGE_GEN_HTTP_${response.code}",
                        message = text.take(600).ifBlank { "HTTP ${response.code}" },
                    )
                }
                text
            }
        } catch (e: IOException) {
            // 网络类错误单独给 code：超时和 DNS 失败在中转站场景很常见。
            return ImageGenOutcome.Failure(
                code = "IMAGE_GEN_NETWORK",
                message = "${e.javaClass.simpleName}: ${e.message ?: "请求失败"}",
            )
        }

        return parseAndStore(context, body, config.model)
    }

    private fun parseAndStore(context: Context, raw: String, model: String): ImageGenOutcome {
        val root = JSONObject(raw)
        val data = root.optJSONArray("data")
            ?: return ImageGenOutcome.Failure("IMAGE_GEN_BAD_RESPONSE", raw.take(600))
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
            ImageGenOutcome.Success(ImageGenResult(files = files, model = model))
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
