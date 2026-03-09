package com.xreal.nativear.tools

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool executor for structured data CRUD operations.
 * Allows AI agents to freely define, store, query, and delete domain-specific data.
 */
class DataToolExecutor(
    private val database: UnifiedMemoryDatabase
) : IToolExecutor {

    private val TAG = "DataToolExecutor"

    override val supportedTools = setOf(
        "save_structured_data",
        "query_structured_data",
        "list_data_domains",
        "delete_structured_data"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return try {
            when (name) {
                "save_structured_data" -> executeSave(args)
                "query_structured_data" -> executeQuery(args)
                "list_data_domains" -> executeListDomains()
                "delete_structured_data" -> executeDelete(args)
                else -> ToolResult(false, "Unsupported tool: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing $name: ${e.message}", e)
            ToolResult(false, "Error: ${e.message}")
        }
    }

    private fun executeSave(args: Map<String, Any?>): ToolResult {
        val domain = args["domain"] as? String
            ?: return ToolResult(false, "Missing required parameter: domain")
        val dataKey = args["data_key"] as? String
            ?: return ToolResult(false, "Missing required parameter: data_key")
        val value = args["value"] as? String
            ?: return ToolResult(false, "Missing required parameter: value")
        val tags = args["tags"] as? String

        val result = database.upsertStructuredData(domain, dataKey, value, tags)
        val action = if (result == -1L) "updated" else "inserted (id=$result)"
        Log.d(TAG, "save_structured_data: domain=$domain, key=$dataKey → $action")
        return ToolResult(true, "Data $action in domain '$domain' with key '$dataKey'.")
    }

    private fun executeQuery(args: Map<String, Any?>): ToolResult {
        val domain = args["domain"] as? String
            ?: return ToolResult(false, "Missing required parameter: domain")
        val dataKey = args["data_key"] as? String
        val tags = args["tags"] as? String
        val limit = (args["limit"] as? Number)?.toInt() ?: 20

        val records = database.queryStructuredData(domain, dataKey, tags, limit)

        if (records.isEmpty()) {
            return ToolResult(true, "No data found in domain '$domain'.")
        }

        val jsonArray = JSONArray()
        for (record in records) {
            jsonArray.put(JSONObject().apply {
                put("domain", record.domain)
                put("data_key", record.dataKey)
                put("value", record.value)
                if (record.tags != null) put("tags", record.tags)
                put("created_at", record.createdAt)
                put("updated_at", record.updatedAt)
            })
        }
        return ToolResult(true, jsonArray.toString())
    }

    private fun executeListDomains(): ToolResult {
        val domains = database.listDataDomains()
        if (domains.isEmpty()) {
            return ToolResult(true, "No data domains exist yet.")
        }
        val jsonArray = JSONArray()
        for ((domain, count) in domains) {
            jsonArray.put(JSONObject().apply {
                put("domain", domain)
                put("count", count)
            })
        }
        return ToolResult(true, jsonArray.toString())
    }

    private fun executeDelete(args: Map<String, Any?>): ToolResult {
        val domain = args["domain"] as? String
            ?: return ToolResult(false, "Missing required parameter: domain")
        val dataKey = args["data_key"] as? String
            ?: return ToolResult(false, "Missing required parameter: data_key")

        val deleted = database.deleteStructuredData(domain, dataKey)
        return if (deleted > 0) {
            ToolResult(true, "Deleted data with key '$dataKey' from domain '$domain'.")
        } else {
            ToolResult(true, "No data found with key '$dataKey' in domain '$domain'.")
        }
    }
}
