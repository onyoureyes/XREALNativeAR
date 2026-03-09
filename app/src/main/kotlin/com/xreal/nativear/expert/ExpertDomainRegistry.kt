package com.xreal.nativear.expert

import android.util.Log
import com.xreal.nativear.context.LifeSituation

/**
 * ExpertDomainRegistry: Central registry for all expert domains.
 *
 * Manages domain registration and lookup by situation.
 * Initialized with 8 built-in domains from ExpertDomainTemplates.
 */
class ExpertDomainRegistry {

    companion object {
        private const val TAG = "ExpertDomainRegistry"
    }

    private val domains = mutableMapOf<String, ExpertDomain>()

    init {
        // Register all built-in domains
        ExpertDomainTemplates.getAllDomains().forEach { domain ->
            registerDomain(domain)
        }
        Log.i(TAG, "Initialized with ${domains.size} built-in domains")
    }

    /**
     * Register a new expert domain.
     */
    fun registerDomain(domain: ExpertDomain) {
        domains[domain.id] = domain
        Log.d(TAG, "Registered domain: ${domain.id} (${domain.name})")
    }

    /**
     * Get all domains that should activate for a given situation.
     * Returns domains sorted by priority (highest first).
     * Always-active domains are always included.
     */
    fun getDomainsForSituation(situation: LifeSituation): List<ExpertDomain> {
        return domains.values.filter { domain ->
            domain.isAlwaysActive || situation in domain.triggerSituations
        }.sortedByDescending { it.priority }
    }

    /**
     * Get a specific domain by ID.
     */
    fun getDomain(id: String): ExpertDomain? = domains[id]

    /**
     * Get all registered domains.
     */
    fun getAllDomains(): List<ExpertDomain> = domains.values.toList()

    /**
     * Get always-active domains.
     */
    fun getAlwaysActiveDomains(): List<ExpertDomain> {
        return domains.values.filter { it.isAlwaysActive }
    }

    /**
     * Get all unique expert profiles across all domains.
     */
    fun getAllExperts(): List<ExpertProfile> {
        return domains.values.flatMap { it.experts }.distinctBy { it.id }
    }

    /**
     * Find which domain an expert belongs to.
     */
    fun getDomainForExpert(expertId: String): ExpertDomain? {
        return domains.values.find { domain ->
            domain.experts.any { it.id == expertId }
        }
    }
}
