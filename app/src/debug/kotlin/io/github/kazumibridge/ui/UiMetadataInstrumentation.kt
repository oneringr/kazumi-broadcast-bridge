package io.github.kazumibridge.ui

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException

/** Offline contracts evaluated against Android's real org.json implementation. */
class UiMetadataInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        Thread({
            val started = SystemClock.elapsedRealtime()
            val result = Bundle()
            var passed = 0
            var currentCase = ""
            fun verify(name: String, block: () -> Unit) {
                currentCase = name
                block()
                passed++
                result.putString(name, "PASS")
            }
            try {
                verify("stable_release") {
                    val release = GitHubReleases.parse(stableJson().toString())
                    check(release.tag == "v1.1.0" && release.name == "四页界面更新")
                    check(release.notes == "What's new?\n\n- 四页导航")
                    check(release.publishedDate == "2026-10-09")
                    check(release.pageUrl == "$REPOSITORY/releases/tag/v1.1.0")
                }
                verify("nullable_optional_fields") {
                    val release = GitHubReleases.parse(stableJson().put("name", JSONObject.NULL).put("body", JSONObject.NULL).toString())
                    check(release.name == "v1.1.0")
                    check(release.notes.isNotBlank() && release.notes != "null")
                }
                verify("draft_rejected") { rejects(stableJson().put("draft", true)) }
                verify("prerelease_rejected") { rejects(stableJson().put("prerelease", true)) }
                verify("missing_stability_flags_rejected") {
                    val json = stableJson()
                    json.remove("draft")
                    json.remove("prerelease")
                    rejects(json)
                }
                val invalidUrls = listOf(
                    "http://github.com/oneringr/kazumi-broadcast-bridge/releases/tag/v1.1.0",
                    "https://github.com.example.org/oneringr/kazumi-broadcast-bridge/releases/tag/v1.1.0",
                    "https://github.com/another-owner/another-repo/releases/tag/v1.1.0",
                    "https://someone@github.com/oneringr/kazumi-broadcast-bridge/releases/tag/v1.1.0",
                    "$REPOSITORY/releases/tag/../latest",
                )
                invalidUrls.forEachIndexed { index, url ->
                    verify("invalid_release_url_${index + 1}") { rejects(stableJson().put("html_url", url)) }
                }
                verify("newer_minor_release") { check(compareReleaseVersions("1.1.0", "1.0.12")?.let { it > 0 } == true) }
                verify("numeric_patch_order") { check(compareReleaseVersions("1.0.12", "1.0.9")?.let { it > 0 } == true) }
                verify("equivalent_zero_segments") { check(compareReleaseVersions("1.1", "1.1.0") == 0) }
                verify("version_prefix_and_metadata") { check(compareReleaseVersions("v1.1.0+build.14", "1.1.0") == 0) }
                listOf("", "1..1", "release-1.1.0", "1.1.0-rc.1").forEachIndexed { index, version ->
                    verify("unsupported_version_${index + 1}") {
                        check(compareReleaseVersions(version, "1.1.0") == null)
                        check(compareReleaseVersions("1.1.0", version) == null)
                    }
                }
                result.putString("status", "PASS")
                result.putInt("tests_passed", passed)
                result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
                finish(Activity.RESULT_OK, result)
            } catch (failure: Throwable) {
                result.putString("status", "FAIL")
                result.putString("failed_case", currentCase)
                result.putInt("tests_passed", passed)
                result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
                result.putString("failure_type", failure.javaClass.simpleName)
                result.putString("failure", failure.message.orEmpty().take(1200))
                finish(Activity.RESULT_CANCELED, result)
            }
        }, "KazumiBridge-ui-metadata-validation").start()
    }

    private fun rejects(json: JSONObject) {
        val failure = runCatching { GitHubReleases.parse(json.toString()) }.exceptionOrNull()
        check(failure is IOException) { "Unexpected stable-release acceptance or rejection type" }
    }

    private fun stableJson() = JSONObject()
        .put("draft", false)
        .put("prerelease", false)
        .put("tag_name", "v1.1.0")
        .put("name", "四页界面更新")
        .put("body", "What's new?\n\n- 四页导航")
        .put("published_at", "2026-10-09T13:00:00Z")
        .put("html_url", "$REPOSITORY/releases/tag/v1.1.0")

    private companion object {
        const val REPOSITORY = "https://github.com/oneringr/kazumi-broadcast-bridge"
    }
}
