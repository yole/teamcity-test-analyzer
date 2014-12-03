package org.jetbrains.teamCityTestAnalyzer

import retrofit.RestAdapter

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
        downloadBuildLog(build.id)
    }

    fun downloadBuildLog(id: String) {
        val response = teamcity.downloadBuildLog(id)
        println(response.getStatus())
    }
}

fun main(args: Array<String>) {
    if (args.size() != 2) {
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
