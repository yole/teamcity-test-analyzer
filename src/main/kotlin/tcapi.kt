package org.jetbrains.teamCityTestAnalyzer

import retrofit.http.GET
import retrofit.http.Query
import retrofit.http.Headers
import java.util.ArrayList
import retrofit.client.Response
import retrofit.http.Path

class BuildList() {
    var build: List<Build> = ArrayList()
}

class Build(val id: String, val href: String)

class TestOccurrenceList() {
    var testOccurrence: List<TestOccurrence> = ArrayList()
}

class TestOccurrence(val id: String, val name: String, val duration: Int)

class PropertyList() {
    var property: List<Property> = ArrayList()
}

class Property(val name: String, val value: Int)

trait TeamCityService {
    GET("/guestAuth/app/rest/builds")
    Headers("Accept: application/json")
    fun listBuilds(Query("locator") locator: String): BuildList

    GET("/guestAuth/app/rest/testOccurrences")
    Headers("Accept: application/json")
    fun listTestOccurrences(Query("locator") locator: String): TestOccurrenceList

    GET("/guestAuth/app/rest/builds/{locator}/statistics")
    Headers("Accept: application/json")
    fun listStatistics(Path("locator") locator: String): PropertyList

    GET("/guestAuth/downloadBuildLog.html")
    fun downloadBuildLog(Query("buildId") buildId: String): Response
}
