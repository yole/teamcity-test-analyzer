package org.jetbrains.teamCityTestAnalyzer

import retrofit.http.GET
import retrofit.http.Query
import retrofit.http.Headers
import java.util.ArrayList
import retrofit.client.Response
import retrofit.http.Path

open class Page(val count: Int, val nextHref: String?)

class BuildsPage(count: Int, nextHref: String?, val build: List<Build>): Page(count, nextHref)

class BuildTypeSummary(val name: String, val projectName: String)

class TestOccurrencesSummary(val count: Int)

class LastChange(val version: String)

class LastChangesSummary(val count: Int, val change: List<LastChange>)

class BuildDetails(val buildType: BuildTypeSummary, val testOccurrences: TestOccurrencesSummary?,
                   val lastChanges: LastChangesSummary)

class Build(val id: String, val href: String)

class TestOccurrencesPage(nextHref: String?, count: Int, val testOccurrence: List<TestOccurrence>)
    : Page(count, nextHref)

class TestOccurrence(val id: String, val name: String, val duration: Int)

class PropertyList(val property: List<Property>)

class Property(val name: String, val value: Int)

trait TeamCityService {
    GET("/guestAuth/app/rest/builds")
    Headers("Accept: application/json")
    fun listBuilds(Query("locator") locator: String): BuildsPage

    GET("/guestAuth/app/rest/builds/{locator}")
    Headers("Accept: application/json")
    fun loadBuildDetails(Path("locator") locator: String): BuildDetails

    GET("/guestAuth/app/rest/testOccurrences")
    Headers("Accept: application/json")
    fun listTestOccurrences(Query("locator") locator: String): TestOccurrencesPage

    GET("/guestAuth/app/rest/builds/{locator}/statistics")
    Headers("Accept: application/json")
    fun listStatistics(Path("locator") locator: String): PropertyList

    GET("/guestAuth/downloadBuildLog.html")
    fun downloadBuildLog(Query("buildId") buildId: String): Response
}
