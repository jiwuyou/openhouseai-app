package com.ai.assistance.operit.ui.features.packages.market

import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.GitHubIssue
import com.ai.assistance.operit.data.api.GitHubLabel

const val MCP_MARKET_VISIBILITY_LABEL = "mcp-plugin"
const val SKILL_MARKET_VISIBILITY_LABEL = "skill-plugin"
const val REVIEW_CHANGES_REQUESTED_LABEL = "review:changes-requested"
const val REVIEW_REJECTED_LABEL = "review:rejected"

val ARTIFACT_MARKET_VISIBILITY_LABELS: Set<String> =
    PublishArtifactType.entries.map { it.marketLabel }.toSet()

val ALL_MARKET_VISIBILITY_LABELS: Set<String> =
    buildSet {
        add(MCP_MARKET_VISIBILITY_LABEL)
        add(SKILL_MARKET_VISIBILITY_LABEL)
        addAll(ARTIFACT_MARKET_VISIBILITY_LABELS)
    }

val MARKET_REVIEW_STATUS_LABELS: Set<String> =
    setOf(
        REVIEW_CHANGES_REQUESTED_LABEL,
        REVIEW_REJECTED_LABEL
    )

val MARKET_REVIEW_REASON_LABELS: Set<String> =
    MarketReviewReason.entries.map { it.labelName }.toSet()

enum class MarketReviewState {
    PENDING,
    APPROVED,
    CHANGES_REQUESTED,
    REJECTED
}

enum class MarketReviewReason(
    val code: String,
    val labelName: String
) {
    METADATA_INCOMPLETE(
        code = "metadata-incomplete",
        labelName = "reason:metadata-incomplete"
    ),
    INSTALL_CONFIG_INVALID(
        code = "install-config-invalid",
        labelName = "reason:install-config-invalid"
    ),
    REPOSITORY_UNREACHABLE(
        code = "repository-unreachable",
        labelName = "reason:repository-unreachable"
    ),
    REPOSITORY_CONTENT_INVALID(
        code = "repository-content-invalid",
        labelName = "reason:repository-content-invalid"
    ),
    ENTRY_UNUSABLE(
        code = "entry-unusable",
        labelName = "reason:entry-unusable"
    ),
    QUALITY_TOO_LOW(
        code = "quality-too-low",
        labelName = "reason:quality-too-low"
    ),
    AI_HALLUCINATION(
        code = "ai-hallucination",
        labelName = "reason:ai-hallucination"
    ),
    SECURITY_RISK(
        code = "security-risk",
        labelName = "reason:security-risk"
    ),
    DUPLICATE_SUBMISSION(
        code = "duplicate-submission",
        labelName = "reason:duplicate-submission"
    ),
    POLICY_VIOLATION(
        code = "policy-violation",
        labelName = "reason:policy-violation"
    );

    companion object {
        private val byLabelName = entries.associateBy { it.labelName.lowercase() }

        fun fromLabelName(labelName: String): MarketReviewReason? {
            return byLabelName[labelName.trim().lowercase()]
        }
    }
}

data class MarketReviewSnapshot(
    val state: MarketReviewState,
    val reasons: List<MarketReviewReason>,
    val isPubliclyApproved: Boolean
)

fun MarketReviewState.labelResId(): Int {
    return when (this) {
        MarketReviewState.PENDING -> R.string.market_review_pending
        MarketReviewState.APPROVED -> R.string.market_review_approved
        MarketReviewState.CHANGES_REQUESTED -> R.string.market_review_changes_requested
        MarketReviewState.REJECTED -> R.string.market_review_rejected
    }
}

fun MarketReviewReason.labelResId(): Int {
    return when (this) {
        MarketReviewReason.METADATA_INCOMPLETE -> R.string.market_review_reason_metadata_incomplete
        MarketReviewReason.INSTALL_CONFIG_INVALID -> R.string.market_review_reason_install_config_invalid
        MarketReviewReason.REPOSITORY_UNREACHABLE -> R.string.market_review_reason_repository_unreachable
        MarketReviewReason.REPOSITORY_CONTENT_INVALID -> R.string.market_review_reason_repository_content_invalid
        MarketReviewReason.ENTRY_UNUSABLE -> R.string.market_review_reason_entry_unusable
        MarketReviewReason.QUALITY_TOO_LOW -> R.string.market_review_reason_quality_too_low
        MarketReviewReason.AI_HALLUCINATION -> R.string.market_review_reason_ai_hallucination
        MarketReviewReason.SECURITY_RISK -> R.string.market_review_reason_security_risk
        MarketReviewReason.DUPLICATE_SUBMISSION -> R.string.market_review_reason_duplicate_submission
        MarketReviewReason.POLICY_VIOLATION -> R.string.market_review_reason_policy_violation
    }
}

fun GitHubIssue.hasAnyLabelName(labelNames: Set<String>): Boolean {
    return labels.any { label ->
        labelNames.any { expected -> expected.equals(label.name, ignoreCase = true) }
    }
}

fun List<GitHubLabel>.withoutLabelNames(labelNames: Set<String>): List<GitHubLabel> {
    return filterNot { label ->
        labelNames.any { excluded -> excluded.equals(label.name, ignoreCase = true) }
    }
}

fun GitHubIssue.resolveMarketReviewSnapshot(publicLabelNames: Set<String>): MarketReviewSnapshot {
    val labelNames = labels.map { it.name.trim() }
    val normalizedLabelNames = labelNames.map { it.lowercase() }.toSet()
    val reasons =
        labels.mapNotNull { label ->
            MarketReviewReason.fromLabelName(label.name)
        }

    val state =
        when {
            normalizedLabelNames.contains(REVIEW_REJECTED_LABEL.lowercase()) ->
                MarketReviewState.REJECTED

            normalizedLabelNames.contains(REVIEW_CHANGES_REQUESTED_LABEL.lowercase()) ->
                MarketReviewState.CHANGES_REQUESTED

            publicLabelNames.any { publicLabel ->
                normalizedLabelNames.contains(publicLabel.lowercase())
            } -> MarketReviewState.APPROVED

            else -> MarketReviewState.PENDING
        }

    return MarketReviewSnapshot(
        state = state,
        reasons = reasons.distinct(),
        isPubliclyApproved = state == MarketReviewState.APPROVED
    )
}

fun GitHubIssue.resolveMcpReviewSnapshot(): MarketReviewSnapshot {
    return resolveMarketReviewSnapshot(setOf(MCP_MARKET_VISIBILITY_LABEL))
}

fun GitHubIssue.resolveSkillReviewSnapshot(): MarketReviewSnapshot {
    return resolveMarketReviewSnapshot(setOf(SKILL_MARKET_VISIBILITY_LABEL))
}

fun GitHubIssue.resolveArtifactReviewSnapshot(): MarketReviewSnapshot {
    return resolveMarketReviewSnapshot(ARTIFACT_MARKET_VISIBILITY_LABELS)
}
