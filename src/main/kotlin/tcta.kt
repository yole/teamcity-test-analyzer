package org.jetbrains.teamCityTestAnalyzer

import retrofit.RestAdapter
import retrofit.RetrofitError
import java.util.HashMap
import java.text.SimpleDateFormat
import org.joda.time.Duration
import org.joda.time.format.PeriodFormat

class Analyzer(serverAddress: String, val buildTypeId: String) {
    val teamcity = RestAdapter.Builder()
            .setEndpoint(serverAddress)
            .build()
            .create(javaClass<TeamCityService>())

    fun run() {
        val locator = "buildType:(id:${buildTypeId})"

        val buildList = teamcity.listBuilds(locator)
        buildList.build.take(1).forEach {
            processBuild(it)
        }
    }

    fun processBuild(build: Build) {
        val testOccurrences = downloadTestOccurrences(build.id).sortDescendingBy { it.duration }
        val statistics = downloadStatistics(build.id)
        val totalTestExecutionTime = testOccurrences.fold(0, { (time, test) -> time + test.duration })
        val testCount = testOccurrences.size
        val totalBuildTime = statistics["BuildDuration"]
        if (totalBuildTime == null) {
            println("Missing total build time")
            return
        }
        println("Total build time ${formatTime(totalBuildTime)}")

        reportTime("Sources update time", statistics["buildStageDuration:sourcesUpdate"], totalBuildTime)
        reportTime("Compilation time", statistics["Compilation time, ms"], totalBuildTime)
        reportTime("Test execution time", totalTestExecutionTime, totalBuildTime)
    }

    fun reportTime(title: String, timeInMS: Int?, totalTimeInMS: Int) {
        if (timeInMS == null) return
        val percent = timeInMS.toDouble() / totalTimeInMS.toDouble() * 100;
        println("${title} ${formatTime(timeInMS)} (${percent}%)")
    }

    fun formatTime(timeInMS: Int): String {
        val duration = Duration.millis(timeInMS.toLong())
        return "${timeInMS} ms (" + PeriodFormat.getDefault().print(duration.toPeriod()) + ")"
    }

    fun downloadTestOccurrences(id: String): List<TestOccurrence> {
        val locator = "count:30000,build:(id:${id})"
        return teamcity.listTestOccurrences(locator).testOccurrence
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
