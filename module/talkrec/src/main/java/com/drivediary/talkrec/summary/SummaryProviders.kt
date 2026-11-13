package com.drivediary.talkrec.summary

object SummaryDefaults {
    const val CLOUDFLARE_MODEL = "@cf/google/gemma-4-26b-a4b-it"
    const val FAKE_MODEL = "fake-summary-v1"
    fun modelName(provider: SummaryProviderType): String = SummaryBuildProviders.modelName(provider)
}

internal fun checkedModelName(value: String): String {
    if (value.length > 192 || !Regex("@?[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*").matches(value))
        throw SummaryException(SummaryErrorCode.INVALID_SETTINGS)
    return value
}

internal fun checkedCloudflareAccountId(value: String): String {
    if (!Regex("[A-Fa-f0-9]{32}").matches(value))
        throw SummaryException(SummaryErrorCode.INVALID_SETTINGS)
    return value
}

class FakeSummaryProvider(
    private val validator: SummaryValidator,
    override val modelName: String = SummaryDefaults.FAKE_MODEL,
) : SummaryProvider {
    override suspend fun storySplit(segments: List<String>): StorySplitResult {
        checkedSegments(segments)
        return StorySplitResult(listOf(SplitStory((1..segments.size).toList(), "테스트 이야기",
            validator.allowedCategories.first(), emptyList(), "story", true, "")))
    }
    override suspend fun summarize(transcript: String): SummaryResult =
        validator.validate(SummaryResult("테스트 대화", validator.allowedCategories.first(), emptyList(), "story"))
}
