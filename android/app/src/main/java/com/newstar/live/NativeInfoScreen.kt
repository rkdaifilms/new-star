package com.newstar.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class NativeInfoSection(val heading: String, val body: String)

@Composable
fun NativeInfoScreen(page: String) {
    val title: String
    val intro: String
    val sections: List<NativeInfoSection>
    when (page) {
        "About" -> {
            title = "About New Star Creator Hub"
            intro = "New Star is a creator-focused platform intended to help people build profiles, publish videos and connect with an audience."
            sections = listOf(
                NativeInfoSection("What you can do", "Create and manage a creator profile. Publish original videos and choose available visibility settings. Discover creators and interact through supported social features. Use creator tools and monetization features when they are available and approved."),
                NativeInfoSection("Content ownership", "New Star does not claim ownership of user-uploaded content. Uploaders must have the rights and permissions needed to publish their material."),
                NativeInfoSection("Help and policies", "Use the in-app Support Inbox for account, privacy, copyright, safety or technical questions. Review the Privacy Policy and Terms & Content Rules before using the service.")
            )
        }
        "Contact" -> {
            title = "Contact & Support"
            intro = "For account, privacy, copyright, safety or technical support, sign in and open the New Star Support Inbox."
            sections = listOf(
                NativeInfoSection("Support Inbox", "Open the Inbox from the main navigation to contact support or report an issue."),
                NativeInfoSection("Privacy and account requests", "Include only the information needed to identify the issue. Never send passwords, one-time codes or payment credentials in a support message."),
                NativeInfoSection("Copyright and content complaints", "Identify the specific content and explain the rights or safety concern. Do not submit false reports.")
            )
        }
        "Terms" -> {
            title = "Terms & Content Rules"
            intro = "Use New Star lawfully and respectfully. These rules apply alongside applicable laws and feature-specific requirements."
            sections = listOf(
                NativeInfoSection("Accounts and safety", "Keep account credentials private. Do not impersonate others, harass users, threaten people, spam, scam or interfere with service security."),
                NativeInfoSection("Content ownership and permissions", "Upload only content you created or have permission to use, including video, audio, music, images and artwork. Do not upload pirated or infringing material."),
                NativeInfoSection("Moderation and reports", "Users are responsible for their uploads and comments. New Star may review reports, restrict visibility, remove content or suspend accounts where required by law, safety or platform rules."),
                NativeInfoSection("Advertising rules", "Do not click your own ads, ask others to click ads, generate artificial impressions, use bots or paid traffic schemes, or offer rewards for ad clicks."),
                NativeInfoSection("Availability and monetization", "Features may change or be unavailable. Eligibility, estimated earnings, gifts and commissions are subject to published rules and verification. An estimate is not a promise of payment. Payouts require supported verification and applicable approvals."),
                NativeInfoSection("Prohibited use", "Do not use New Star for illegal activity, fraud, malware, spam, copyright infringement, deceptive downloads or content that violates applicable platform policies.")
            )
        }
        else -> {
            title = "Privacy Policy"
            intro = "This summary describes categories of information New Star may process when you use creator profiles, video publishing, messaging and related features."
            sections = listOf(
                NativeInfoSection("Information you provide", "Depending on features used, this may include account and profile details, uploaded videos, captions, comments, messages, reports and support requests."),
                NativeInfoSection("Information during use", "Service infrastructure may process technical information such as device details, log data, security events, approximate network information and feature usage. Location is used only when you grant device permission to a location feature."),
                NativeInfoSection("Uploads and public content", "Content you choose to publish publicly may be visible to other users and may be copied or shared by them. Use visibility controls carefully and do not upload personal information without a valid reason."),
                NativeInfoSection("Service providers", "Hosted infrastructure may process account, database and storage data. Depending on enabled features, video delivery and AI requests may be processed by configured providers."),
                NativeInfoSection("Advertising and cookies", "If advertising is enabled, providers may use cookies, device identifiers or similar technologies as described in their policies and applicable law."),
                NativeInfoSection("Security and retention", "Access controls and hosted security features are intended to protect information, but no internet service can guarantee absolute security. Retention depends on data type and legal or operational requirements."),
                NativeInfoSection("Children and age-appropriate use", "New Star must apply appropriate age safeguards and must not knowingly collect children's information in ways prohibited by applicable law. Parents or guardians can contact support with privacy concerns."),
                NativeInfoSection("Your choices", "Use available profile and privacy settings to update information. For access, correction or deletion requests, contact support. Some records may need to be retained for security, legal or transaction purposes.")
            )
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(intro, style = MaterialTheme.typography.bodyLarge)
        sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(section.heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(section.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (page == "Terms" || page == "Privacy") {
            Text("Policy publication note: The operator must keep these disclosures aligned with actual service providers, data flows, moderation procedures and applicable local law. This in-app summary is not legal advice or a legal compliance certification.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
