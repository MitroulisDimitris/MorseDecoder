package com.morsedecoder.core

import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Marks a test that asserts **behaviour we want but do not yet have**.
 *
 * The problem this solves: a suite where every known defect has been written as a test of
 * *current* behaviour is green while the product is broken, so the green tells you
 * nothing. A suite where known defects fail outright is red permanently, so a real
 * regression hides among the noise. Both throw away the signal.
 *
 * So the two signals are separated. A `@KnownFailure` test states the behaviour that
 * *should* hold. When it fails — as it does today — the build stays green for regression
 * purposes, and the failure is counted and printed in a summary at the end of the run.
 * The count is the honest answer to "how are we doing"; the green is the honest answer to
 * "did this change break something that used to work".
 *
 * The important half is the other direction: **if a `@KnownFailure` test passes, the build
 * fails.** Someone has fixed the defect, and the annotation must come off or the list goes
 * stale and starts lying. A known-failure list nobody prunes is worse than no list.
 *
 * Usage:
 * ```
 * @Test
 * @KnownFailure("D-9", "a tone change strands the lock")
 * fun `a tone jump is recovered`() { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(KnownFailureExtension::class)
annotation class KnownFailure(
    /** Identifier in known-issues.md, e.g. "D-9". */
    val issue: String,
    /** One line, for the end-of-run summary. */
    val summary: String = "",
)

class KnownFailureExtension : TestExecutionExceptionHandler, AfterTestExecutionCallback {

    companion object {
        private const val FAILED = "knownFailureThrew"
        private val recorded = ConcurrentLinkedQueue<Triple<String, String, String>>()
        private val hookInstalled = AtomicBoolean(false)

        private fun installSummaryHook() {
            if (!hookInstalled.compareAndSet(false, true)) return
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    if (recorded.isEmpty()) return@Thread
                    val byIssue = recorded.sortedBy { it.first }
                    val issues = byIssue.map { it.first }.distinct()
                    System.out.println()
                    System.out.println("=".repeat(78))
                    System.out.println(
                        "KNOWN FAILURES — ${recorded.size} test(s) across ${issues.size} open defect(s)"
                    )
                    System.out.println(
                        "These assert behaviour the decoder does not yet have. The build is green"
                    )
                    System.out.println(
                        "for regression purposes only; see known-issues.md for each."
                    )
                    System.out.println("-".repeat(78))
                    for ((issue, summary, where) in byIssue) {
                        System.out.println("  %-5s %-46s %s".format(issue, summary.take(46), where))
                    }
                    System.out.println("=".repeat(78))
                    System.out.flush()
                }
            )
        }

        fun record(issue: String, summary: String, where: String) {
            installSummaryHook()
            recorded.add(Triple(issue, summary, where))
        }
    }

    private fun annotation(context: ExtensionContext): KnownFailure? =
        context.testMethod.orElse(null)?.getAnnotation(KnownFailure::class.java)

    private fun store(context: ExtensionContext) =
        context.getStore(ExtensionContext.Namespace.create(KnownFailureExtension::class.java))

    /** Swallow the failure, but count it. */
    override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
        val ann = annotation(context) ?: throw throwable
        store(context).put(FAILED, true)
        val where = "${context.requiredTestClass.simpleName}.${context.requiredTestMethod.name}"
        record(ann.issue, ann.summary.ifEmpty { throwable.message?.take(46) ?: "" }, where)
    }

    /** A known failure that stops failing is a fix nobody recorded. Make it loud. */
    override fun afterTestExecution(context: ExtensionContext) {
        val ann = annotation(context) ?: return
        val failed = store(context).get(FAILED) as? Boolean ?: false
        if (!failed) {
            throw AssertionError(
                "@KnownFailure(\"${ann.issue}\") now PASSES. The defect looks fixed — remove the " +
                    "annotation so this becomes a normal regression test, and close ${ann.issue} " +
                    "in known-issues.md."
            )
        }
    }
}
