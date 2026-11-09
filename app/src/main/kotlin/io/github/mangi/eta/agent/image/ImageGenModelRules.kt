package io.github.mangi.eta.agent.image

/**
 * 按模型名修正请求参数的兼容层。
 *
 * 各家图像 API 的参数约束并不统一，而中转站通常只做透传：把 `n=3` 发给 dall-e-3、
 * 把 `response_format` 发给 gpt-image 系列，都会直接 400。这里在发请求前按模型名
 * 把参数掰回合法区间，并把「改了什么」原样回给模型，避免它以为拿到了 3 张。
 *
 * 刻意**不**按模型名拦截请求：很多中转站把异步任务制模型包装成了同步返回，
 * 硬拦会误伤。异步只在响应侧识别（见 [ImageGenClient.parseAndStore]）。
 */
internal object ImageGenModelRules {

    /** 单次请求的参数修正结果。 */
    internal data class Adjustments(
        val n: Int,
        val size: String,
        /** 修正后的画质参数；空串表示不带该参数（由服务端按默认处理）。 */
        val quality: String,
        /** 修正后的背景参数（opaque / transparent）；空串表示不带该参数。 */
        val background: String,
        /** gpt-image 系列不接受 response_format，带了会 400。 */
        val omitResponseFormat: Boolean,
        /** 人话说明改了什么；非空时回传，让模型知道结果与预期不同。 */
        val notes: List<String>,
    )

    /** dall-e-3 只认这三档。 */
    private val DALLE3_SIZES = setOf("1024x1024", "1792x1024", "1024x1792")

    /** dall-e-2 只认方形三档。 */
    private val DALLE2_SIZES = setOf("256x256", "512x512", "1024x1024")

    private val GPT_IMAGE_QUALITIES = setOf("auto", "low", "medium", "high")
    private val DALLE3_QUALITIES = setOf("standard", "hd")
    private val VALID_BACKGROUNDS = setOf("auto", "opaque", "transparent")

    /** 已知采用「异步任务 + 轮询」协议的模型族；仅用于给出可读的诊断。 */
    private val ASYNC_FAMILIES = listOf(
        "seedream", "seededit", "seedance",
        "kling", "kolors", "wanx", "wan2", "vidu",
        "hunyuan-image", "step-image", "cogview",
    )

    fun adjust(
        model: String,
        size: String,
        requested: Int,
        quality: String = "auto",
        background: String = "auto",
    ): Adjustments {
        val id = model.lowercase()
        val notes = mutableListOf<String>()

        val isDalle3 = id.contains("dall-e-3") || id.contains("dalle-3")
        val isDalle2 = id.contains("dall-e-2") || id.contains("dalle-2")
        val isGptImage = id.contains("gpt-image")

        val (cap, capName) = when {
            isDalle3 -> 1 to "dall-e-3"
            isGptImage -> 10 to "gpt-image"
            isDalle2 -> 10 to "dall-e-2"
            else -> MAX_N to model
        }
        val n = requested.coerceIn(1, cap)
        if (n != requested) {
            notes += "$capName 只支持 n=$n（请求 $requested），已按 $n 张生成"
        }

        val normalizedSize = when {
            isDalle3 ->
                if (size in DALLE3_SIZES) size else {
                    notes += "dall-e-3 只支持 1024x1024 / 1792x1024 / 1024x1792，已改用 ${nearestDalle3(size)}"
                    nearestDalle3(size)
                }
            isDalle2 ->
                if (size in DALLE2_SIZES) size else {
                    notes += "dall-e-2 只支持 256x256 / 512x512 / 1024x1024，已改用 1024x1024"
                    "1024x1024"
                }
            else -> size
        }

        val q = quality.trim().lowercase()
        val normalizedQuality = when {
            q.isEmpty() || q == "auto" -> if (isGptImage) "auto" else ""
            isGptImage -> when (q) {
                in GPT_IMAGE_QUALITIES -> q
                "hd" -> "high".also { notes += "gpt-image 将 quality=hd 映射为 high" }
                "standard" -> "medium".also { notes += "gpt-image 将 quality=standard 映射为 medium" }
                else -> "auto".also { notes += "gpt-image 不支持 quality=$q，已改用 auto" }
            }
            isDalle3 -> when (q) {
                in DALLE3_QUALITIES -> q
                "high" -> "hd".also { notes += "dall-e-3 将 quality=high 映射为 hd" }
                "low", "medium" -> "standard".also { notes += "dall-e-3 将 quality=$q 映射为 standard" }
                else -> ""
            }
            isDalle2 -> "".also {
                notes += "dall-e-2 不支持 quality 参数，已忽略 quality=$q"
            }
            else -> q
        }

        val bg = background.trim().lowercase()
        val normalizedBackground = when {
            bg.isEmpty() || bg == "auto" -> if (isGptImage) "auto" else ""
            bg !in VALID_BACKGROUNDS -> "".also {
                notes += "不支持的 background=$bg（仅支持 auto/opaque/transparent），已忽略"
            }
            isDalle3 || isDalle2 -> "".also {
                notes += "${if (isDalle3) "dall-e-3" else "dall-e-2"} 不支持透明背景参数 background=$bg，已忽略"
            }
            else -> bg
        }

        // gpt-image-1/2 把 response_format 当成未知参数直接拒绝；它本来也只回 b64_json。
        val omitResponseFormat = isGptImage

        return Adjustments(
            n = n,
            size = normalizedSize,
            quality = normalizedQuality,
            background = normalizedBackground,
            omitResponseFormat = omitResponseFormat,
            notes = notes,
        )
    }

    /** 响应里出现任务 id 而不是图片数据时，判断是不是这套模型族，好把原因说清楚。 */
    fun isAsyncFamily(model: String): Boolean {
        val id = model.lowercase()
        return ASYNC_FAMILIES.any { id.contains(it) }
    }

    private fun nearestDalle3(size: String): String {
        val parts = size.lowercase().split("x").mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 2) return "1024x1024"
        val (w, h) = parts
        return when {
            w > h -> "1792x1024"
            h > w -> "1024x1792"
            else -> "1024x1024"
        }
    }

    private const val MAX_N = 4
}
