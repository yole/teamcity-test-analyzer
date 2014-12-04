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

fun formatDuration(timeInMS: Int): String {
    val duration = Duration.millis(timeInMS.toLong())
    return "${timeInMS} ms (" + PeriodFormat.getDefault().print(duration.toPeriod()) + ")"
}

class TimeDistribution(val name: String, val totalDuration: Int, val sampleCount: Int = 1) {
    data class Phase(val name: String,
                     val duration: Int,
                     val minDuration: Int = duration,
                     val maxDuration: Int = duration)

    var unaccountedTime: Int = totalDuration
    val phases: MutableList<Phase> = ArrayList()

    fun addPhase(name: String, duration: Int?) {
        if (duration == null) return
        phases.add(Phase(name, duration))
        unaccountedTime -= duration
    }

    fun getPhaseDuration(name: String) = phases.firstOrNull { it.name == name }?.duration

    fun report() {
        println("${name} (total): ${formatDuration(totalDuration)}")
        phases.forEach {
            if (sampleCount == 1) {
                reportTimePercent(it.name, it.duration)
            }
            else {
                reportTimePercent(it.name + " (average)", it.duration)
                println("${it.name} (minimum): ${formatDuration(it.minDuration)}")
                println("${it.name} (maximum): ${formatDuration(it.maxDuration)}")
            }
        }
    }

    fun reportUnaccounted() {
        reportTimePercent("${name} (unaccounted)", unaccountedTime)
    }

    private fun reportTimePercent(name: String, duration: Int) {
        val percent = duration.toDouble() / totalDuration.toDouble() * 100;
        val builder = StringBuilder()
        val formatter = Formatter(builder)
        formatter.format("%s %s (%.2f%%)", name, formatDuration(duration / sampleCount), percent)
        println(builder)
    }

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

    fun testExecutionTime() = buildTimeDistribution.getPhaseDuration("Test execution time")

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
            println("Total duration of slow tests: ${formatDuration(totalSlowTestTime)}")
            println("Total duration of fast tests: ${formatDuration(totalFastTestTime)}")
            println("Average duration of slow test: ${totalSlowTestTime/slowTestCount} ms")
            println("Average duration of fast test: ${totalFastTestTime/fastTestCount} ms")

            val testDuration = testExecutionTime()
            if (testDuration != null) {
                val timeWithoutTests = buildTimeDistribution.totalDuration - testDuration
                println("Time of build with slow tests: ${formatDuration(timeWithoutTests + totalSlowTestTime)}")
                println("Time of build with fast tests: ${formatDuration(timeWithoutTests + totalFastTestTime)}")
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

class BuildTypeStatistics(val name: String,
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
            result.setReadTimeout(60000)
            return result
        }
    }

    val teamcity = RestAdapter.Builder()
            .setEndpoint(serverAddress)
            .setClient(client)
            .build()
            .create(javaClass<TeamCityService>())

    fun processBuildType(buildTypeId: String): BuildTypeStatistics? {
        val locator = "buildType:(id:${buildTypeId})"

        val buildList = teamcity.listBuilds(locator)
        val statistics = processBuild(buildList.build.first!!, true)
        if (statistics == null) {
            return null
        }
        statistics.report()
        statistics.suggestTestSplit()
        var aggregateBuildTimeDistribution = statistics.buildTimeDistribution
        var aggregateTestTimeDistribution = statistics.testTimeDistribution
        buildList.build.drop(1).take(5).forEach {
            val nextStatistics = processBuild(it, false)
            if (nextStatistics != null) {
                aggregateBuildTimeDistribution = aggregateBuildTimeDistribution.merge(nextStatistics.buildTimeDistribution)
                aggregateTestTimeDistribution = aggregateTestTimeDistribution.merge(nextStatistics.testTimeDistribution)
            }
        }
        return BuildTypeStatistics(statistics.buildTypeName,
                aggregateBuildTimeDistribution, aggregateTestTimeDistribution)
    }

    fun processBuild(build: Build, loadTestOccurrences: Boolean): BuildStatistics? {
        val testOccurrences = if (loadTestOccurrences)
            downloadTestOccurrences(build.id).sortDescendingBy { it.duration }
        else
            listOf()
        val statistics = downloadStatistics(build.id)
        val details = teamcity.loadBuildDetails("id:" + build.id)
        val totalTestExecutionTime = testOccurrences.fold(0, { (time, test) -> time + test.duration })
        val testOccurrencesSummary = details.testOccurrences
        val totalBuildTime = statistics["BuildDuration"]
        if (totalBuildTime == null || testOccurrencesSummary == null) {
            return null
        }
        val testCount = testOccurrencesSummary.count
        val testExecutionTime = statistics["ideaTests.totalTimeMs"] ?: totalTestExecutionTime
        val buildTimeDistribution = TimeDistribution("Build time", totalBuildTime)
        buildTimeDistribution.addPhase("Sources update time", statistics["buildStageDuration:sourcesUpdate"])
        buildTimeDistribution.addPhase("Compilation time", statistics["Compilation time, ms"])
        buildTimeDistribution.addPhase("Test execution time", testExecutionTime)
        buildTimeDistribution.addPhase("Artifacts publishing time", statistics["BuildArtifactsPublishingTime"])

        val testTimeDistribution = TimeDistribution("Test execution time", testExecutionTime)
        if (testExecutionTime > 0) {
            testTimeDistribution.addPhase("Time of individual test execution", totalTestExecutionTime)
        }
        testTimeDistribution.addPhase("Time of setUp()", statistics["ideaTests.totalSetupMs"])
        testTimeDistribution.addPhase("Time of tearDown()", statistics["ideaTests.totalTeardownMs"])
        testTimeDistribution.addPhase("Time of GC", statistics["ideaTests.gcTimeMs"])
        return BuildStatistics(details.buildType.name, buildTimeDistribution, testTimeDistribution,
                testCount, testOccurrences)
    }

    fun downloadTestOccurrences(id: String): List<TestOccurrence> {
        val locator = "build:(id:${id})"
        var start: Int = 0
        val result = ArrayList<TestOccurrence>()
        while(true) {
            val page = teamcity.listTestOccurrences(locator + ",start:" + start)
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

    fun downloadBuildLog(id: String) {
        val response = teamcity.downloadBuildLog(id)
        println(response.getStatus())
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
    val results = args.drop(1).map { analyzer.processBuildType(it) }
    results.forEach { it?.report() }
}
