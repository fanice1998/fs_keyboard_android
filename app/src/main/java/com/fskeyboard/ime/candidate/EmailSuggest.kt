package com.fskeyboard.ime.candidate

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal class EmailSuggest {
    private val userDomainFrequency = HashMap<String, Int>()
    private val prefixDomainFrequency = HashMap<String, HashMap<String, Int>>()

    fun isEmailField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val clazz = info.inputType and InputType.TYPE_MASK_CLASS
        if (clazz != InputType.TYPE_CLASS_TEXT) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    fun hasEmailContext(beforeCursor: String, info: EditorInfo?): Boolean {
        if (isEmailField(info)) return true
        val at = beforeCursor.lastIndexOf('@')
        if (at < 0) return false
        val afterAt = beforeCursor.substring(at + 1)
        return !afterAt.contains(' ') && !afterAt.contains('\n')
    }

    fun loadHistory(lines: Iterable<String>) {
        for (line in lines) {
            val parts = line.split('\t')
            if (parts.size >= 3) {
                val username = parts[0].trim().lowercase()
                val domain = parts[1].trim().lowercase()
                val count = parts[2].trim().toIntOrNull() ?: 1
                if (domain.isNotEmpty() && count > 0) {
                    userDomainFrequency[domain] = (userDomainFrequency[domain] ?: 0) + count
                    val forPrefix = prefixDomainFrequency.getOrPut(username) { HashMap() }
                    forPrefix[domain] = (forPrefix[domain] ?: 0) + count
                }
            } else if (parts.size == 2) {
                val domain = parts[0].trim().lowercase()
                val count = parts[1].trim().toIntOrNull() ?: 1
                if (domain.isNotEmpty() && count > 0) {
                    userDomainFrequency[domain] = (userDomainFrequency[domain] ?: 0) + count
                }
            }
        }
    }

    fun recordChoice(fullOrDomain: String, username: String? = null) {
        val domain: String
        val user: String
        if (fullOrDomain.contains('@')) {
            val parts = fullOrDomain.split('@')
            user = parts[0].trim().lowercase()
            domain = parts.getOrNull(1)?.trim()?.lowercase().orEmpty()
        } else {
            user = username?.trim()?.lowercase().orEmpty()
            domain = fullOrDomain.trim().lowercase()
        }
        if (domain.isEmpty()) return
        userDomainFrequency[domain] = (userDomainFrequency[domain] ?: 0) + 1
        if (user.isNotEmpty()) {
            val forPrefix = prefixDomainFrequency.getOrPut(user) { HashMap() }
            forPrefix[domain] = (forPrefix[domain] ?: 0) + 1
        }
    }

    fun serializeHistory(): List<String> {
        val lines = ArrayList<String>()
        for ((user, domains) in prefixDomainFrequency) {
            for ((domain, count) in domains) {
                lines.add("$user\t$domain\t$count")
            }
        }
        return lines
    }

    /**
     * Ranks domains for [username] (前段詞) and [domainPrefix].
     */
    fun rankDomains(username: String, domainPrefix: String): List<String> {
        val prefix = domainPrefix.lowercase()
        val user = username.lowercase()

        val allDomains = LinkedHashSet<String>()
        allDomains.addAll(DEFAULT_DOMAINS)
        allDomains.addAll(userDomainFrequency.keys)

        return allDomains
            .filter { domain -> prefix.isEmpty() || domain.startsWith(prefix) }
            .sortedByDescending { domain -> scoreDomain(domain, user, prefix) }
    }

    /**
     * Scores a domain based on default rank, domain prefix match, global usage,
     * and username history (前段詞權重加成).
     */
    fun scoreDomain(domain: String, username: String, domainPrefix: String): Float {
        var score = 0f
        val defaultIndex = DEFAULT_DOMAINS.indexOf(domain)
        if (defaultIndex >= 0) {
            score += (DEFAULT_DOMAINS.size - defaultIndex) * 50f
        }

        // Domain prefix match boost (highest priority when user types domain letters)
        if (domainPrefix.isNotEmpty() && domain.startsWith(domainPrefix)) {
            score += 2000f
        }

        // Global user frequency boost
        val globalCount = userDomainFrequency[domain] ?: 0
        score += globalCount * 5f

        // 前段詞 (username) 歷史權重加成: if used with this username before, boost heavily!
        val prefixCount = prefixDomainFrequency[username]?.get(domain) ?: 0
        score += prefixCount * 500f

        return score
    }

    /**
     * Generates candidates when typing in email context.
     * When after '@', returns matching domains.
     * When before '@', returns typed username and username + "@" + domain for top domains.
     */
    fun candidates(
        typed: String,
        beforeCursor: String,
        limit: Int = 8,
    ): List<String> {
        val atIndex = beforeCursor.lastIndexOf('@')
        if (atIndex >= 0) {
            val textBeforeAt = beforeCursor.substring(0, atIndex)
            val username = textBeforeAt.substringAfterLast(' ', textBeforeAt).substringAfterLast('\n', textBeforeAt)
            val domainPrefix = beforeCursor.substring(atIndex + 1)
            val domains = rankDomains(username, domainPrefix)
            return domains.take(limit)
        } else {
            if (typed.isEmpty()) return emptyList()
            val domains = rankDomains(typed, "")
            return buildList {
                add(typed)
                for (d in domains) {
                    add("$typed@$d")
                }
            }.take(limit)
        }
    }

    companion object {
        val DEFAULT_DOMAINS = listOf(
            "gmail.com",
            "yahoo.com.tw",
            "outlook.com",
            "icloud.com",
            "hotmail.com",
            "yahoo.com",
            "msn.com",
        )
    }
}
