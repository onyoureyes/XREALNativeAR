package com.xreal.nativear.tools

import com.xreal.nativear.ISearchService
import com.xreal.nativear.IWeatherService
import com.xreal.nativear.INavigationService

class WebToolExecutor(
    private val searchService: ISearchService,
    private val weatherService: IWeatherService,
    private val navigationService: INavigationService
) : IToolExecutor {

    override val supportedTools = setOf("searchWeb", "getWeather", "get_directions")

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "searchWeb" -> {
                val query = args["query"] as? String ?: ""
                ToolResult(true, searchService.searchWeb(query))
            }
            "getWeather" -> {
                val location = args["location"] as? String ?: ""
                ToolResult(true, weatherService.getWeather(location))
            }
            "get_directions" -> {
                val dest = args["destination"] as? String ?: ""
                val origin = args["origin"] as? String ?: "current location"
                ToolResult(true, navigationService.getDirections(origin, dest))
            }
            else -> ToolResult(false, "Unsupported tool: $name")
        }
    }
}
