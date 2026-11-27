package com.titanmapper.core.model

/**
 * Honest outcome of one self-test item (Architecture: diagnostics self-test suite).
 *
 * [PASS]/[FAIL] are only used when the check actually ran against real runtime state and got a
 * definite answer. [UNSUPPORTED] means the device/configuration makes the capability impossible
 * (e.g. no Shizuku app installed at all), not a failure of Titan Mapper. [NOT_TESTED] means the
 * check requires something this process cannot observe by itself -- most commonly a human
 * physically pressing a key/button within a time window -- and no such input arrived; it is
 * never used to quietly stand in for a fabricated PASS.
 */
enum class SelfTestVerdict { PASS, FAIL, UNSUPPORTED, NOT_TESTED }

/**
 * Result of one self-test item. [detail] always explains WHY that verdict was reached in terms
 * of the concrete state that was checked (e.g. "Settings.Secure lists the Accessibility service
 * as enabled" / "No connected keyboard or mouse found"), never a generic "ok"/"not ok" string --
 * this is what keeps the suite from becoming a row of unexplained green checkmarks.
 */
data class SelfTestResult(
    val id: String,
    val title: String,
    val verdict: SelfTestVerdict,
    val detail: String
)
