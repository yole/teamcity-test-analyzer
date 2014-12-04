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

fun formatDuration(timeInMS: Int): String {
    val duration = Duration.millis(timeInMS.toLong())
    return "${timeInMS} ms (" + PeriodFormat.getDefault().print(duration.toPeriod()) + ")"
}

class TimeDistribution(val name: String, val totalDuration: Int) {
    data class Phase(val name: String, val duration: Int)

    var unaccountedTime: Int = totalDuration
    val phases: MutableList<Phase> = ArrayList()

    fun addPhase(name: String, duration: Int?) {
        if (duration == null) return
        phases.add(Phase(name, duration))
        unaccountedTime -= duration
    }

    fun getPhaseDuration(name: String): Int? {
        return phases.firstOrNull { it.name == name }?.duration
    }

    fun report() {
        println("${name} (total): ${formatDuration(totalDuration)}")
        phases.forEach {
            reportTimePercent(it.name, it.duration)
        }
    }

    fun reportUnaccounted() {
        reportTimePercent("${name} (unaccounted)", unaccountedTime)
    }

    private fun reportTimePercent(name: String, duration: Int) {
        val percent = duration.toDouble() / totalDuration.toDouble() * 100;
        val builder = StringBuilder()
        val formatter = Formatter(builder)
        formatter.format("%s %s (%.2f%%)", name, formatDuration(duration), percent)
        println(builder)
    }
}

class BuildStatistics(val buildTimeDistribution: TimeDistribution,
                      val testTimeDistribution: TimeDistribution,
                      val avgTestDuration: Int,
                      val testOccurrences: List<TestOccurrence>) {
    fun report() {
        buildTimeDistribution.report()
        buildTimeDistribution.reportUnaccounted()
        testTimeDistribution.report()
        println("Average time of test execution: ${avgTestDuration} ms")
    }

    fun suggestTestSplit(threshold: Int) {
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

            val testDuration = buildTimeDistribution.getPhaseDuration("Test execution time")
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

class Analyzer(serverAddress: String, val buildTypeId: String) {
    val teamcity = RestAdapter.Builder()
            .setEndpoint(serverAddress)
            .build()
            .create(javaClass<TeamCityService>())

    fun run() {
        val locator = "buildType:(id:${buildTypeId})"

        val buildList = teamcity.listBuilds(locator)
        buildList.build.take(1).forEach {
            val statistics = processBuild(it)
            statistics?.report()
            statistics?.suggestTestSplit(500)
        }
    }

    fun processBuild(build: Build): BuildStatistics? {
        val testOccurrences = downloadTestOccurrences(build.id).sortDescendingBy { it.duration }
        val statistics = downloadStatistics(build.id)
        val totalTestExecutionTime = testOccurrences.fold(0, { (time, test) -> time + test.duration })
        val testCount = testOccurrences.size
        val totalBuildTime = statistics["BuildDuration"]
        if (totalBuildTime == null) {
            return null
        }
        val testExecutionTime = statistics["ideaTests.totalTimeMs"]
        val buildTimeDistribution = TimeDistribution("Build time", totalBuildTime)
        buildTimeDistribution.addPhase("Sources update time", statistics["buildStageDuration:sourcesUpdate"])
        buildTimeDistribution.addPhase("Compilation time", statistics["Compilation time, ms"])
        buildTimeDistribution.addPhase("Test execution time", testExecutionTime)
        buildTimeDistribution.addPhase("Artifacts publishing time", statistics["BuildArtifactsPublishingTime"])

        val testTimeDistribution = TimeDistribution("Test execution time", testExecutionTime ?: totalTestExecutionTime)
        if (testExecutionTime != null) {
            testTimeDistribution.addPhase("Time of individual test execution", totalTestExecutionTime)
        }
        testTimeDistribution.addPhase("Time of setUp()", statistics["ideaTests.totalSetupMs"])
        testTimeDistribution.addPhase("Time of tearDown()", statistics["ideaTests.totalTeardownMs"])
        testTimeDistribution.addPhase("Time of GC", statistics["ideaTests.gcTimeMs"])
        val avgTestExecutionTime = if (testCount == 0) 0 else totalTestExecutionTime / testCount
        return BuildStatistics(buildTimeDistribution, testTimeDistribution, avgTestExecutionTime, testOccurrences)
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
    if (args.size != 2) {
        println("Usage: teamCityTestAnalyzer <server> <build type ID>")
        return
    }
    var serverAddress = args[0]
    if (!serverAddress.contains("://")) {
        serverAddress = "http://" + serverAddress
    }
    val analyzer = Analyzer(serverAddress, args[1])
    analyzer.run()
}
