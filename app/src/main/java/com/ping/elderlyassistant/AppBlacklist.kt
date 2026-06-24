package com.ping.elderlyassistant

/**
 * Package-name blacklist for apps that use FLAG_SECURE or handle sensitive data.
 * The voice assistant refuses to automate these apps to prevent accidental exposure
 * of credentials, OTPs, or banking data.
 */
object AppBlacklist {

    const val BLOCKED_MESSAGE =
        "此應用程式受安全保護，語音助理無法自動操作。請手動完成此操作。"

    private val SECURE_PACKAGES = setOf(
        // 2FA / authenticator apps
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.lastpass.authenticator",
        "org.fedorahosted.freeotp",

        // Password managers
        "com.lastpass.lpandroid",
        "com.agilebits.onepassword",
        "com.bitwarden.mobile",
        "keepass2android.keepass2android",

        // Banking / payment (common Taiwan banks)
        "com.cathay.bank.cbank",
        "com.esunbank.ib",
        "com.taishinbank.stmobilebanking",
        "tw.com.ctbcbank.mobile",
        "com.mega.bank",
        "com.bot.mobile",
        "com.firstbank.firstmobile",
        "com.landbank.mobile",
        "com.hncb.mobile",
        "tw.com.entrust.smartbank",
        "com.samsung.android.pay",
        "com.google.android.apps.walletnfcrel",
        "com.linepay.app",
        "tw.com.jkopay.app",

        // System lock screen / keyguard
        "com.android.systemui",
        "com.android.keyguard",
        "com.oneplus.keyguard",
        "com.samsung.android.incallui",
        "com.samsung.android.knox.containercore",
        "com.samsung.android.secureui",
    )

    fun isSecure(packageName: String?): Boolean =
        packageName != null && packageName in SECURE_PACKAGES
}
