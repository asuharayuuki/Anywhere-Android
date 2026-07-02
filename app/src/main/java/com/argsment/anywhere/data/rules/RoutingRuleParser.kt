package com.argsment.anywhere.data.rules

import com.argsment.anywhere.data.model.DomainRule
import com.argsment.anywhere.data.model.DomainRuleType

enum class RuleSetImportRoute(val value: Int) {
    DEFAULT(0),
    DIRECT(1),
    REJECT(2);

    companion object {
        fun fromValue(value: Int): RuleSetImportRoute? = entries.firstOrNull { it.value == value }
    }

    val assignmentId: String?
        get() = when (this) {
            DEFAULT -> null
            DIRECT -> "DIRECT"
            REJECT -> "REJECT"
        }
}

object RoutingRuleParser {
    data class ParseResult(
        val name: String,
        val rules: List<DomainRule>,
        val routing: RuleSetImportRoute
    )

    private val recognizedHeaders = setOf("name", "routing")

    fun parse(text: String): ParseResult {
        var name = ""
        val rules = mutableListOf<DomainRule>()
        var routing = RuleSetImportRoute.DEFAULT

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach

            val header = parseHeader(line)
            if (header != null) {
                when (header.first) {
                    "name" -> name = header.second
                    "routing" -> {
                        header.second.toIntOrNull()?.let { code ->
                            RuleSetImportRoute.fromValue(code)?.let { routing = it }
                        }
                    }
                }
            } else {
                val rule = parseRuleLine(line)
                if (rule != null) {
                    rules.add(rule)
                }
            }
        }

        return ParseResult(name, rules, routing)
    }

    private fun parseHeader(line: String): Pair<String, String>? {
        val equalIndex = line.indexOf('=')
        if (equalIndex < 0) return null
        val key = line.substring(0, equalIndex).trim().lowercase()
        if (key !in recognizedHeaders) return null
        val value = line.substring(equalIndex + 1).trim()
        return key to value
    }

    private fun parseRuleLine(trimmed: String): DomainRule? {
        val commaIndex = trimmed.indexOf(',')
        if (commaIndex < 0) return null
        val prefix = trimmed.substring(0, commaIndex).trim()
        val value = trimmed.substring(commaIndex + 1).trim()
        if (value.isEmpty()) return null

        val typeInt = prefix.toIntOrNull() ?: return null
        val type = DomainRuleType.fromRawValue(typeInt) ?: return null
        return DomainRule(type, normalize(value, type))
    }

    private fun normalize(value: String, type: DomainRuleType): String = when (type) {
        DomainRuleType.IP_CIDR -> if ("/" in value) value else "$value/32"
        DomainRuleType.IP_CIDR6 -> if ("/" in value) value else "$value/128"
        DomainRuleType.DOMAIN_SUFFIX,
        DomainRuleType.DOMAIN_KEYWORD -> value
    }
}
