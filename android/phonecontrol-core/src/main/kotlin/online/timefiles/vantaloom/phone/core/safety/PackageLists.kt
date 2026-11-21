package online.timefiles.vantaloom.phone.core.safety

/**
 * 内置默认包名表（协议 §6 硬拒绝与「每步确认」）。
 *
 * 为什么内置一份而不是全靠用户配：agent 一旦误入支付/银行类 App，后果不可逆，
 * 默认就得是「拒绝」；用户真需要时再在设置里逐条解除（SafetyPolicy.userAllowedRules）
 * 或从名单里删掉某个包。名单宁可偏宽：误拒只是多一步用户操作，漏拒可能是一笔转账。
 *
 * 包名来自各应用公开的应用商店页面；厂商会改包名，所以另有前缀表（[DENY_PREFIXES]）兜底。
 */
object PackageLists {

    /** 支付与钱包。 */
    val PAYMENT: Set<String> = setOf(
        "com.eg.android.AlipayGphone",          // 支付宝
        "hk.alipay.wallet",                     // AlipayHK
        "com.unionpay",                         // 云闪付
        "com.unionpay.tsmservice",              // 银联可信服务
        "com.jd.jrapp",                         // 京东金融
        "com.wallet.crypto.trustapp",           // Trust Wallet
        "io.metamask",                          // MetaMask
        "com.paypal.android.p2pmobile",         // PayPal
        "com.venmo",                            // Venmo
        "com.squareup.cash",                    // Cash App
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "com.samsung.android.spay",             // Samsung Pay / Wallet
        "com.samsung.android.samsungpay.gear",
        "com.huawei.wallet",                    // 华为钱包
        "com.mipay.wallet",                     // 小米钱包
        "com.miui.tsmclient",                   // 小米智能卡
        "com.coloros.wallet",                   // OPPO 钱包
        "com.finshell.wallet",                  // OPPO/一加 钱包
        "com.vivo.wallet",                      // vivo 钱包
        "com.honor.wallet",                     // 荣耀钱包
        "com.sankuai.meituan.meituanwaimaibusiness", // 美团商家（含收款）
        "com.revolut.revolut",                  // Revolut
        "com.transferwise.android",             // Wise
        "com.coinbase.android",                 // Coinbase
        "com.binance.dev",                      // Binance
        "com.okinc.okex.gp",                    // OKX
        "com.bitkeep.wallet",                   // Bitget Wallet
        "net.one97.paytm",                      // Paytm
        "com.phonepe.app",                      // PhonePe
        "com.klarna.android",                   // Klarna
        "com.alipay.mobile.client",             // 支付宝国际
        "com.tencent.wetype.pay",               // 预留：微信支付独立组件（若出现）
    )

    /** 银行（国内主要银行 + 部分境外）。 */
    val BANKING: Set<String> = setOf(
        "com.icbc",                             // 工商银行
        "com.icbc.elife",
        "com.chinamworld.main",                 // 建设银行
        "com.ccb.longjiLife",
        "com.android.bankabc",                  // 农业银行
        "com.chinamworld.bocmbci",              // 中国银行
        "com.bankcomm.Bankcomm",                // 交通银行
        "com.bankcomm.maidanba",                // 交行买单吧
        "cmb.pb",                               // 招商银行
        "com.cmbchina.ccd.pluto.cmbActivity",   // 掌上生活
        "com.yitong.mbank.psbc",                // 邮储银行
        "com.ecitic.bank.mobile",               // 中信银行
        "com.citiccard.mobilebank",             // 中信动卡空间
        "com.cebbank.mobile.cemb",              // 光大银行
        "com.hxb.mobile.client",                // 华夏银行
        "cn.com.cmbc.newmbank",                 // 民生银行
        "com.cib.cibmb",                        // 兴业银行
        "com.cib.xiaofei",
        "cn.com.spdb.mobilebank.per",           // 浦发银行
        "com.spdbccc.app",                      // 浦大喜奔
        "com.pingan.paces.ccms",                // 平安口袋银行
        "com.pingan.lifeinsurance",             // 平安金管家
        "com.cgbchina.xpt",                     // 广发银行
        "com.cgbchina.mobile",
        "com.bankofbeijing.mobilebanking",      // 北京银行
        "cn.com.shbank.mper",                   // 上海银行
        "com.webank.wemoney",                   // 微众银行
        "com.mybank.android.phone",             // 网商银行
        "com.czbank.mbank",                     // 浙商银行
        "com.nbbank",                           // 宁波银行
        "com.chase.sig.android",                // Chase
        "com.bankofamerica.cashpromobile",
        "com.infonow.bofa",                     // Bank of America
        "com.wf.wellsfargomobile",              // Wells Fargo
        "com.citi.citimobile",                  // Citi
        "com.konylabs.capitalone",              // Capital One
        "com.htsu.hsbcpersonalbanking",         // HSBC
        "com.hsbc.hsbcnet",
        "uk.co.hsbc.hsbcukmobilebanking",
        "com.barclays.android.barclaysmobilebanking",
        "com.grppl.android.shell.CMBlloydsTSB73", // Lloyds
        "com.starlingbank.android",
        "com.monzo.android",
        "de.number26.android",                  // N26
        "com.dbs.sg.dbsmbanking",               // DBS
        "com.ocbc.mobile",
        "jp.co.smbc.direct",                    // 三井住友
        "jp.co.rakuten_bank.rakutenbank",
        "com.kakaobank.channel",                // KakaoBank
        "com.sc.mobilebanking.hk",              // 渣打香港
        "com.boc.hk.mobilebanking",             // 中银香港
    )

    /** 券商与基金。 */
    val BROKERAGE: Set<String> = setOf(
        "com.hexin.plat.android",               // 同花顺
        "com.eastmoney.android.berlin",         // 东方财富
        "com.eastmoney.android.fund",           // 天天基金
        "com.android.dazhihui",                 // 大智慧
        "com.thinkive.android.app_gtja",        // 国泰君安君弘
        "com.htsc.zlt",                         // 华泰涨乐财富通
        "com.zszq.mobile",                      // 招商证券
        "com.gf.client",                        // 广发易淘金
        "com.citics.mstock",                    // 中信证券
        "com.pingan.stock",                     // 平安证券
        "com.guosen.android",                   // 国信证券
        "com.foundersc.app.xf",                 // 方正证券
        "com.xueqiu.android",                   // 雪球
        "cn.futu.trader",                       // 富途牛牛
        "com.webull.trade",                     // 微牛
        "com.tigerbrokers.stock",               // 老虎证券
        "com.longbridge.app",                   // 长桥
        "com.robinhood.android",                // Robinhood
        "com.etrade.mobilepro.activity",        // E*TRADE
        "com.schwab.mobile",                    // Schwab
        "com.fidelity.android",                 // Fidelity
        "com.interactivebrokers.mobile",        // IBKR
        "com.td.ameritrade.mobile",
        "com.ant.fortune",                      // 蚂蚁财富（预留）
        "com.lufax.android",                    // 陆金所
    )

    /** 密码管理器与身份验证器。 */
    val PASSWORD_MANAGERS: Set<String> = setOf(
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre",
        "com.keepersecurity.passwordmanager",
        "com.nordpass.android.app.password.manager",
        "proton.android.pass",
        "com.enpass.android",
        "com.callpod.android_apps.keeper",
        "com.google.android.apps.authenticator2", // Google Authenticator
        "com.azure.authenticator",               // Microsoft Authenticator
        "com.authy.authy",
        "org.fedorahosted.freeotp",
        "com.beemdevelopment.aegis",
        "com.tencent.token",                     // QQ 安全中心（令牌）
        "com.netease.mkey",                      // 网易将军令
        "com.huawei.hwid",                       // 华为帐号（含安全验证）
    )

    /** 手机管家/安全中心：整体拒绝动作（协议 §6「安全中心、手机管家类包」）。 */
    val SECURITY_CENTERS: Set<String> = setOf(
        "com.miui.securitycenter",               // MIUI 手机管家
        "com.miui.securityadd",
        "com.miui.guardprovider",
        "com.coloros.safecenter",                // ColorOS 手机管家
        "com.oplus.safecenter",
        "com.coloros.securitypermission",
        "com.oppo.safe",
        "com.iqoo.secure",                       // vivo i 管家
        "com.vivo.permissionmanager",
        "com.huawei.systemmanager",              // 华为手机管家
        "com.hihonor.systemmanager",             // 荣耀手机管家
        "com.samsung.android.lool",              // 三星 设备维护
        "com.samsung.android.sm",
        "com.samsung.android.sm.devicesecurity",
        "com.meizu.safe",                        // 魅族手机管家
        "com.oneplus.security",
        "com.tencent.qqpimsecure",               // 腾讯手机管家
        "com.qihoo360.mobilesafe",               // 360 手机卫士
        "com.lbe.security",
        "com.google.android.gms.supervision",
        "com.android.permissioncontroller",      // 系统权限控制器（授权弹窗）
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",          // 安装/卸载确认
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.android.certinstaller",
        "com.android.keychain",
        "com.android.managedprovisioning",
        "com.android.vpndialogs",
        "com.android.systemui.accessibility.accessibilitymenu",
    )

    /** 系统设置包（受保护页面只能看不能动；其余设置页按普通包处理）。 */
    val SETTINGS: Set<String> = setOf(
        "com.android.settings",                  // AOSP
        "com.google.android.settings.intelligence",
        "com.miui.settings",                     // MIUI / HyperOS 子模块
        "com.xiaomi.misettings",
        "com.android.settings.intelligence",
        "com.coloros.settings",                  // ColorOS 旧版
        "com.oplus.settings",
        "com.oneplus.settings",
        "com.vivo.settings",                     // OriginOS / FuntouchOS
        "com.bbk.settings",
        "com.huawei.settings",                   // EMUI / HarmonyOS 子模块（主包仍是 com.android.settings）
        "com.hihonor.settings",                  // 荣耀 MagicOS
        "com.samsung.android.settings",          // One UI
        "com.sec.android.app.settings",
        "com.samsung.accessibility",             // 三星无障碍设置
        "com.meizu.settings",
        "com.android.tv.settings",
    )

    /** 默认「每步确认」的包（协议 §6：微信、QQ、短信、电话）。 */
    val CONFIRM_EVERY_WRITE: Set<String> = setOf(
        "com.tencent.mm",                        // 微信
        "com.tencent.mobileqq",                  // QQ
        "com.tencent.tim",                       // TIM
        "com.tencent.wework",                    // 企业微信
        "com.alibaba.android.rimet",             // 钉钉
        "com.ss.android.lark",                   // 飞书
        "com.google.android.apps.messaging",     // Google 信息
        "com.android.mms",                       // AOSP 短信
        "com.android.messaging",
        "com.samsung.android.messaging",
        "com.android.dialer",                    // 电话
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.contacts",
        "com.android.incallui",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.google.android.gm",                 // Gmail
    )

    /** 包名前缀拒绝：厂商/银行常用多个变体包名，前缀能兜住未列出的那些。 */
    val DENY_PREFIXES: List<String> = listOf(
        "com.icbc.",
        "com.chinamworld.",
        "cmb.",
        "com.cmbchina.",
        "com.bankcomm.",
        "com.yitong.mbank.",
        "cn.com.spdb.",
        "com.cgbchina.",
        "com.citiccard.",
        "com.ecitic.",
        "com.cebbank.",
        "com.unionpay.",
        "com.eg.android.Alipay",
        "com.chase.",
        "com.wf.",
        "com.citi.",
        "com.x8bit.",
        "com.onepassword.",
        "com.agilebits.",
        "keepass2android.",
        "com.kunzisoft.keepass.",
    )

    /** 默认硬拒绝包名的并集（不含安全中心，安全中心走单独的规则名 security_package）。 */
    val DENY_ALL: Set<String> by lazy { PAYMENT + BANKING + BROKERAGE + PASSWORD_MANAGERS }

    /** 包所属的类别（给用户看的拒绝原因）。不在任何内置表里返回 null。 */
    fun categoryOf(pkg: String): String? = when {
        pkg in PAYMENT -> "支付"
        pkg in BANKING -> "银行"
        pkg in BROKERAGE -> "券商"
        pkg in PASSWORD_MANAGERS -> "密码管理"
        pkg in SECURITY_CENTERS -> "安全中心"
        pkg in SETTINGS -> "系统设置"
        DENY_PREFIXES.any { pkg.startsWith(it) } -> "金融/密码类"
        else -> null
    }
}
