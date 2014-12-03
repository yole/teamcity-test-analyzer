package org.jetbrains.teamCityTestAnalyzer

import retrofit.http.GET
import retrofit.http.Query
import retrofit.http.Headers
import java.util.ArrayList
import retrofit.client.Response

class BuildList() {
    var build: List<Build> = ArrayList()
}

class Build() {
    var id: String = ""
    var href: String = ""
}

trait TeamCityService {
    GET("/guestAuth/app/rest/builds")
    Headers("Accept: application/json")
    fun listBuilds(Query("locator") locator: String): BuildList

    GET("/downloadBuildLog.html")
    fun downloadBuildLog(Query("buildId") buildId: String): Response
}
