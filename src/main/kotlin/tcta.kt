package org.jetbrains.teamCityTestAnalyzer

import retrofit.RestAdapter
import retrofit.RetrofitError
import java.util.HashMap
import java.text.SimpleDateFormat
import org.joda.time.Duration
import org.joda.time.format.PeriodFormat
import java.util.ArrayList
import java.util.Formatter
import java.util.TreeSet
import retrofit.client.UrlConnectionClient
import retrofit.client.Request
import java.net.HttpURLConnection
import java.io.OutputStreamWriter
import java.io.FileOutputStream
import org.joda.time.format.PeriodFormatterBuilder

val SOURCES_UPDATE_PHASE = "Sources update time"
val COMPILATION_PHASE = "Compilation time"
val TEST_EXECUTION_PHASE = "Test execution time"
val SETUP_PHASE = "Time of setUp()"
val TEARDOWN_PHASE = "Time of tearDown()"
val GC_PHASE = "Time of GC"

val periodFormatter = PeriodFormatterBuilder().printZeroAlways()
        .appendHours().appendLiteral(":")
        .minimumPrintedDigits(2)
        .appendMinutes().appendLiteral(":")
        .appendSeconds().toFormatter()

fun formatDuration(timeInMS: Int, includeExactTime: Boolean): String {
    val duration = Duration.millis(timeInMS.toLong())
    val formattedTime = periodFormatter.print(duration.toPeriod())
    if (!includeExactTime) {
        return formattedTime
    }
    return "${timeInMS} ms (" + formattedTime + ")"
}

class TimeDistribution(val name: String, val totalDuration: Int, val sampleCount: Int = 1) {
    data class Phase(val name: String,
                     val duration: Int,
                     val minDuration: Int = duration,
                     val maxDuration: Int = duration)

    var unaccountedTime: Int = totalDuration
    val phases: MutableList<Phase> = ArrayList()
    var missingPhases = false

    fun addPhase(name: String, duration: Int?) {
        if (duration == null) {
            missingPhases = true
            return
        }
        phases.add(Phase(name, duration))
        unaccountedTime -= duration
    }

    fun getPhaseDuration(name: String) = phases.firstOrNull { it.name == name }?.duration

    fun report() {
        println("${name} (total): ${formatDuration(totalDuration, true)}")
        phases.forEach {
            if (sampleCount == 1) {
                println("${it.name} ${formatPercentage(it.duration, true)}")
            }
            else {
                println("${it.name} (average): ${formatPercentage(it.duration, true)}")
                println("${it.name} (minimum): ${formatDuration(it.minDuration, true)}")
                println("${it.name} (maximum): ${formatDuration(it.maxDuration, true)}")
            }
        }
    }

    fun reportUnaccounted() {
        println("${name} (unaccounted) ${formatPercentage(unaccountedTime, false)}")
    }

    fun formatPercentage(duration: Int?, includeExactTime: Boolean): String {
        if (duration == null) return "-"
        val percent = duration.toDouble() / totalDuration.toDouble() * 100;
        val builder = StringBuilder()
        val formatter = Formatter(builder)
        formatter.format("%s (%.2f%%)", formatDuration(duration / sampleCount, includeExactTime), percent)
        return builder.toString()
    }

    fun formatPhaseDuration(name: String) = formatPercentage(getPhaseDuration(name), false)

    fun merge(other: TimeDistribution): TimeDistribution {
        val result = TimeDistribution(name, totalDuration + other.totalDuration, sampleCount + other.sampleCount)
        phases.forEach { phase ->
            val otherPhase = other.phases.firstOrNull { it.name == phase.name }
            result.phases.add(if (otherPhase != null) mergePhase(phase, otherPhase) else phase)
        }
        return result
    }

    fun mergePhase(p1: Phase, p2: Phase): Phase = Phase(p1.name, p1.duration + p2.duration,
            Math.min(p1.minDuration, p2.minDuration), Math.max(p1.maxDuration, p2.maxDuration))
}

class BuildStatistics(val buildTypeName: String,
                      val buildTimeDistribution: TimeDistribution,
                      val testTimeDistribution: TimeDistribution,
                      val testCount: Int,
                      val testOccurrences: List<TestOccurrence>) {
    fun report() {
        buildTimeDistribution.report()
        buildTimeDistribution.reportUnaccounted()
        testTimeDistribution.report()
        val testTime = testExecutionTime()
        if (testCount > 0 && testTime != null) {
            println("Average time of test execution: ${testTime / testCount} ms")
        }
    }

    fun testExecutionTime() = buildTimeDistribution.getPhaseDuration(TEST_EXECUTION_PHASE)

    fun hasMissingPhases() = buildTimeDistribution.missingPhases || testTimeDistribution.missingPhases

    fun suggestTestSplit() {
        val testTime = testExecutionTime()
        if (testTime == null || testCount == 0) return
        val threshold = testTime / testCount
        val slowTestClasses = TreeSet<String>()
        var totalSlowTestTime: Int = 0
        var totalFastTestTime: Int = 0
        var slowTestCount = 0
        var fastTestCount = 0
        testOccurrences.forEach {
            val testClass = extractTestClassName(it.name)
            if (it.duration > threshold || slowTestClasses.contains(testClass)) {
                slowTestClasses.add(testClass)
                totalSlowTestTime += it.duration
                slowTestCount++
            }
            else {
                totalFastTestTime += it.duration
                fastTestCount++
            }
        }

        if (slowTestCount == 0) {
            println("All tests are faster than ${threshold}")
        }
        else if (fastTestCount == 0) {
            println("All tests are slower than ${threshold}")
        }
        else {
            println("Slow test count: ${slowTestCount}")
            println("Fast test count: ${fastTestCount}")
            println("Total duration of slow tests: ${formatDuration(totalSlowTestTime, false)}")
            println("Total duration of fast tests: ${formatDuration(totalFastTestTime, false)}")
            println("Average duration of slow test: ${totalSlowTestTime/slowTestCount} ms")
            println("Average duration of fast test: ${totalFastTestTime/fastTestCount} ms")

            val testDuration = testExecutionTime()
            if (testDuration != null) {
                val timeWithoutTests = buildTimeDistribution.totalDuration - testDuration
                println("Time of build with slow tests: ${formatDuration(timeWithoutTests + totalSlowTestTime, false)}")
                println("Time of build with fast tests: ${formatDuration(timeWithoutTests + totalFastTestTime, false)}")
            }
        }
    }

    fun extractTestClassName(name: String): String {
        var testName = name
        val colon = name.indexOf(':')
        if (colon >= 0) {
            testName = name.substring(colon+1).trim()
        }
        val lastDot = testName.lastIndexOf('.')
        if (lastDot >= 0) {
            return testName.substring(0, lastDot)
        }
        return testName
    }
}

class BuildTypeStatistics(val name: String, val testCount: Int,
                          val aggregateBuildTimeDistribution: TimeDistribution,
                          val aggregateTestTimeDistribution: TimeDistribution) {
    fun report() {
        println("---- Aggregate statistics for ${name} ---")
        aggregateBuildTimeDistribution.report()
        aggregateTestTimeDistribution.report()
    }
}

class Analyzer(serverAddress: String) {
    val client = object : UrlConnectionClient() {
        override fun openConnection(request: Request?): HttpURLConnection? {
            val result = super.openConnection(request)
            result.setReadTimeout(120000)
            return result
        }
    }

    val teamcity = RestAdapter.Builder()
            .setEndpoint(serverAddress)
            .setClient(client)
            .build()
            .create(javaClass<TeamCityService>())

    val revisionsCompiled = HashMap<String, Int>()

    fun processBuildType(buildTypeId: String, suggestTestSplit: Boolean): BuildTypeStatistics? {
        val locator = "buildType:(id:${buildTypeId}),running:false,personal:false"

        val buildList = teamcity.listBuilds(locator)
        val statistics = processBuild(buildList.build.first!!, true)
        if (statistics == null) {
            return null
        }
        var testCount = statistics.testCount
        statistics.report()
        if (suggestTestSplit) {
            statistics.suggestTestSplit()
        }
        var aggregateBuildTimeDistribution = statistics.buildTimeDistribution
        var aggregateTestTimeDistribution = statistics.testTimeDistribution
        buildList.build.drop(1).take(5).forEach {
            val nextStatistics = processBuild(it, false)
            if (nextStatistics != null && !nextStatistics.hasMissingPhases()) {
                aggregateBuildTimeDistribution = aggregateBuildTimeDistribution.merge(nextStatistics.buildTimeDistribution)
                aggregateTestTimeDistribution = aggregateTestTimeDistribution.merge(nextStatistics.testTimeDistribution)
                testCount = Math.max(testCount, nextStatistics.testCount)
            }
        }
        return BuildTypeStatistics(statistics.buildTypeName, testCount,
                aggregateBuildTimeDistribution, aggregateTestTimeDistribution)
    }

    fun processBuild(build: Build, loadTestOccurrences: Boolean): BuildStatistics? {
        val testOccurrences = if (loadTestOccurrences)
            downloadTestOccurrences(build.id).sortDescendingBy { it.duration }
        else
            listOf()
        val statistics = downloadStatistics(build.id)
        val details = teamcity.loadBuildDetails("id:" + build.id)
        recordRevisionsCompiled(details.lastChanges.change)
        val totalTestExecutionTime = testOccurrences.fold(0, { (time, test) -> time + test.duration })
        val testOccurrencesSummary = details.testOccurrences
        val totalBuildTime = statistics["BuildDuration"]
        if (totalBuildTime == null || testOccurrencesSummary == null) {
            return null
        }
        val testCount = testOccurrencesSummary.count
        val testExecutionTime = statistics["ideaTests.totalTimeMs"] ?: totalTestExecutionTime
        val buildTimeDistribution = TimeDistribution("Build time", totalBuildTime)
        buildTimeDistribution.addPhase(SOURCES_UPDATE_PHASE, statistics["buildStageDuration:sourcesUpdate"])
        buildTimeDistribution.addPhase(COMPILATION_PHASE, statistics["Compilation time, ms"])
        buildTimeDistribution.addPhase("Test execution time", testExecutionTime)
        buildTimeDistribution.addPhase("Artifacts publishing time", statistics["BuildArtifactsPublishingTime"])

        val testTimeDistribution = TimeDistribution("Test execution time", testExecutionTime)
        if (testExecutionTime > 0) {
            testTimeDistribution.addPhase("Time of individual test execution", totalTestExecutionTime)
        }
        testTimeDistribution.addPhase(SETUP_PHASE, statistics["ideaTests.totalSetupMs"])
        testTimeDistribution.addPhase(TEARDOWN_PHASE, statistics["ideaTests.totalTeardownMs"])
        testTimeDistribution.addPhase(GC_PHASE, statistics["ideaTests.gcTimeMs"])
        return BuildStatistics(calculateBuildTypeName(details.buildType), buildTimeDistribution, testTimeDistribution,
                testCount, testOccurrences)
    }

    fun downloadTestOccurrences(id: String): List<TestOccurrence> {
        val locator = "build:(id:${id})"
        var start: Int = 0
        val result = ArrayList<TestOccurrence>()
        while(true) {
            val page = teamcity.listTestOccurrences(locator + ",start:" + start)
            if (page.count == 0) {
                break
            }
            result.addAll(page.testOccurrence)
            start += page.count
            if (page.nextHref == null) {
                break
            }
        }
        return result
    }

    fun downloadStatistics(id: String): Map<String, Int> {
        val locator = "id:${id}"
        val statistics = teamcity.listStatistics(locator).property
        val result = HashMap<String, Int>()
        statistics.forEach { result.put(it.name, it.value) }
        return result
    }

    fun calculateBuildTypeName(buildType: BuildTypeSummary): String {
        val doubleColon = buildType.projectName.lastIndexOf("::");
        val projectName = if (doubleColon > 0)
            buildType.projectName.substring(doubleColon + 2).trim()
        else
            buildType.projectName
        return projectName + " :: " + buildType.name
    }

    fun recordRevisionsCompiled(lastChanges: List<LastChange>) {
        lastChanges.forEach {
            val oldCount = revisionsCompiled.get(it.version) ?: 0
            revisionsCompiled.put(it.version, oldCount + 1)
        }
    }

    fun reportDuplicateCompilations() {
        revisionsCompiled.forEach {
            if (it.getValue() > 1) {
                println("Revision ${it.key} compiled ${it.value} times")
            }
        }
    }
}

fun generateHtmlReport(results: List<BuildTypeStatistics?>) {
    val f = OutputStreamWriter(FileOutputStream("report.html"))
    try {
        f.write("<table><thead><tr><td>Name</td><td>Tests</td><td>Update</td><td>Compile</td><td>setUp()</td><td>tearDown()</td><td>GC</td></tr></thead>")
        results.filterNotNull().forEach {
            f.write("<tr><td>${it.name}</td><td>${it.testCount}")
            f.write("<td>${it.aggregateBuildTimeDistribution.formatPhaseDuration(SOURCES_UPDATE_PHASE)}</td>")
            f.write("<td>${it.aggregateBuildTimeDistribution.formatPhaseDuration(COMPILATION_PHASE)}</td>")
            f.write("<td>${it.aggregateTestTimeDistribution.formatPhaseDuration(SETUP_PHASE)}</td>")
            f.write("<td>${it.aggregateTestTimeDistribution.formatPhaseDuration(TEARDOWN_PHASE)}</td>")
            f.write("<td>${it.aggregateTestTimeDistribution.formatPhaseDuration(GC_PHASE)}</td>")
            f.write("</tr>")
        }
        f.write("</table>")
    } finally {
        f.close()
    }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: teamCityTestAnalyzer <server> <build type ID>...")
        return
    }
    var serverAddress = args[0]
    if (!serverAddress.contains("://")) {
        serverAddress = "http://" + serverAddress
    }
    val analyzer = Analyzer(serverAddress)
    val results = args.drop(1).map { analyzer.processBuildType(it.trim("", "+"), it.endsWith("+")) }
    generateHtmlReport(results)
    results.forEach { it?.report() }
    analyzer.reportDuplicateCompilations()
}
