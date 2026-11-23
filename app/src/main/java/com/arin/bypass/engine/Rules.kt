package com.arin.bypass.engine

import com.arin.bypass.model.HitCategory

class Rule(
    val id: String,
    val title: String,
    val detail: String,
    val category: HitCategory,
    val weight: Int,
    val pattern: Regex
)

object Rules {

    val all: List<Rule> = listOf(
        Rule("pin.okhttp", "OkHttp certificate pinning", "The app pins certificates with OkHttp, so a re-signed build is rejected until this is relaxed.", HitCategory.PINNING, 80, Regex("CertificatePinner|certificatePinner")),
        Rule("pin.digest", "Pin digest", "A pinned certificate fingerprint was found next to the code that enforces it.", HitCategory.PINNING, 70, Regex("<pin-digest|sha256/[A-Za-z0-9+/=]{20,}")),
        Rule("pin.trust", "Custom trust manager", "A hand written trust manager decides which certificates are accepted.", HitCategory.PINNING, 65, Regex("checkServerTrusted|X509TrustManager")),
        Rule("pin.netsec", "Network security config", "A network security configuration file controls which certificate authorities are trusted.", HitCategory.PINNING, 60, Regex("network_security_config|networkSecurityConfig")),
        Rule("pin.mitm", "apk-mitm marker", "This class is one that the pinning remover usually rewrites.", HitCategory.PINNING, 55, Regex("CertificatePinner\\$Builder|TrustManagerFactory")),

        Rule("net.okhttp", "OkHttp call", "Network traffic goes through OkHttp.", HitCategory.NETWORK, 50, Regex("Lokhttp3/|okhttp3\\.(OkHttpClient|Request)")),
        Rule("net.retrofit", "Retrofit call", "The app builds its API calls with Retrofit.", HitCategory.NETWORK, 50, Regex("Lretrofit2/|retrofit2\\.Retrofit")),
        Rule("net.urlconn", "Raw HTTP connection", "Plain HttpURLConnection is used for at least one request.", HitCategory.NETWORK, 45, Regex("HttpURLConnection|openConnection")),
        Rule("net.volley", "Volley call", "Requests are made through Volley.", HitCategory.NETWORK, 45, Regex("com/android/volley|Volley\\.newRequestQueue")),

        Rule("sig.check", "Own signature check", "The app compares its own signing certificate, which changes after a clone is signed.", HitCategory.SIGNATURE, 75, Regex("getPackageInfo|GET_SIGNATURES|signatures\\[")),
        Rule("sig.bytes", "Signature byte compare", "Signature bytes are compared against a stored value.", HitCategory.SIGNATURE, 70, Regex("getSignature|Signature;")),
        Rule("sig.installer", "Installer check", "The app checks which installer installed it.", HitCategory.SIGNATURE, 50, Regex("getInstallSourceInfo|getInstallerPackageName")),

        Rule("auth.bearer", "Bearer header", "A bearer token is attached to requests.", HitCategory.AUTH, 60, Regex("Bearer |Authorization")),
        Rule("auth.basic", "Basic auth", "Basic authentication is used for at least one call.", HitCategory.AUTH, 50, Regex("setBasicAuth|Basic ")),
        Rule("auth.login", "Login call", "A sign-in or session endpoint is called.", HitCategory.AUTH, 45, Regex("/login|/auth|authenticate")),
        Rule("auth.valid", "Auth validator", "A method decides whether the current user is authenticated.", HitCategory.AUTH, 65, Regex("isLoggedIn|isAuthenticated|isSignedIn|validateSession")),

        Rule("tok.access", "Access token", "An access token field feeds the requests.", HitCategory.TOKEN, 60, Regex("access_token|accessToken")),
        Rule("tok.refresh", "Refresh token", "A refresh token is stored and used.", HitCategory.TOKEN, 60, Regex("refresh_token|refreshToken")),
        Rule("tok.jwt", "JWT handling", "A JSON web token is parsed locally.", HitCategory.TOKEN, 55, Regex("jwt|JWT|split\\(\\\"\\\\.\\\"\\)")),
        Rule("tok.valid", "Token validator", "A method decides whether the stored token is still good.", HitCategory.TOKEN, 65, Regex("isTokenValid|hasValidToken|tokenExpired|isExpired")),

        Rule("flag.check", "Feature flag check", "A boolean feature flag switches a feature on or off.", HitCategory.FLAG, 55, Regex("isEnabled|featureFlag|FeatureFlag|isFeatureOn")),
        Rule("flag.config", "Remote config", "Remote configuration values are read from a server.", HitCategory.REMOTE_CONFIG, 60, Regex("RemoteConfig|remoteConfig|getRemoteValue")),
        Rule("flag.read", "Config reader", "A config value is looked up by key.", HitCategory.REMOTE_CONFIG, 45, Regex("getBoolean\\(|getConfigValue")),

        Rule("nsfw.flag", "NSFW flag", "A content gate decides whether adult content is shown.", HitCategory.NSFW, 70, Regex("isNsfw|isNSFW|nsfwEnabled|adultContent")),
        Rule("nsfw.mod", "Moderation answer", "The server moderation answer is checked.", HitCategory.NSFW, 55, Regex("moderation|safeSearch|isSafe")),

        Rule("upd.force", "Force update check", "The app can block itself until it is updated.", HitCategory.FORCE_UPDATE, 75, Regex("forceUpdate|force_update|updateRequired|mandatoryUpdate")),
        Rule("upd.min", "Minimum version compare", "A minimum supported version is compared with the installed one.", HitCategory.FORCE_UPDATE, 65, Regex("minVersion|minimumVersion|versionCode <|latestVersion")),
        Rule("upd.inapp", "In-app update", "Play in-app update flow is wired in.", HitCategory.FORCE_UPDATE, 55, Regex("AppUpdateManager|inAppUpdate")),

        Rule("bill.play", "Play Billing", "Google Play Billing is used to sell the subscription.", HitCategory.BILLING, 80, Regex("com/android/billingclient|BillingClient")),
        Rule("bill.rc", "RevenueCat", "RevenueCat keeps the entitlement state.", HitCategory.BILLING, 85, Regex("com/revenuecat/purchases|RevenueCat|CustomerInfo")),
        Rule("bill.adapty", "Adapty", "Adapty keeps the entitlement state.", HitCategory.BILLING, 80, Regex("com/adapty|Adapty")),
        Rule("bill.qonv", "Qonversion", "Qonversion keeps the entitlement state.", HitCategory.BILLING, 80, Regex("com/qonversion|Qonversion")),
        Rule("bill.super", "Superwall", "Superwall handles the paywall.", HitCategory.PAYWALL, 80, Regex("com/superwall|Superwall")),
        Rule("bill.homemade", "Hand written premium flag", "A local boolean decides whether the user is premium.", HitCategory.PAYWALL, 70, Regex("isPremium|isPro\\b|hasActiveSub|hasSubscription|isSubscribed")),
        Rule("bill.plan", "Plan comparison", "The current plan name is compared against a paid plan.", HitCategory.PAYWALL, 65, Regex("plan\\s*==|PRO\\\"|premium\\\"|entitlement")),
        Rule("bill.seed", "Premium seed value", "A stored default for the premium flag was found.", HitCategory.PAYWALL, 55, Regex("putBoolean\\(\\\"(is_?premium|premium|is_?pro)|\\\"premium\\\":\\s*(true|false)")),
        Rule("bill.paywall", "Paywall screen", "A paywall or upgrade screen is present.", HitCategory.PAYWALL, 50, Regex("Paywall|paywall|UpgradeActivity|SubscriptionActivity"))
    )

    val endpoints: Regex = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{4,180}")

    val obfuscationClass: Regex = Regex("smali(_classes\\d+)?/[ab]/[ab]/[a-z]{1,2}\\.smali$")
}
