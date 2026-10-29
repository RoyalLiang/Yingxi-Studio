package io.localplayer.app

/** Exact quoted-text mappings verified against bundled Alpha 32, 50, 51 and 52 sources. */
internal object LegacyQuoteAliases {
    private val known=mapOf(
        "quote:axi:0" to "quote_axi_4af777fe83",
        "quote:axi:1" to "quote_axi_14caf5cef2",
        "quote:axi:10" to "quote_axi_26d8fe186e",
        "quote:axi:11" to "quote_axi_cdd2ad7ae2",
        "quote:axi:12" to "quote_axi_874a956560",
        "quote:axi:13" to "quote_axi_2f18c998dc",
        "quote:axi:2" to "quote_axi_c4d9dc4c9f",
        "quote:axi:3" to "quote_axi_470fcf9220",
        "quote:axi:4" to "quote_axi_3f33249c50",
        "quote:axi:5" to "quote_axi_e36345e967",
        "quote:axi:6" to "quote_axi_f66684691c",
        "quote:axi:7" to "quote_axi_91b302aef5",
        "quote:axi:8" to "quote_axi_b9c88d5b58",
        "quote:axi:9" to "quote_axi_9788d620a3",
        "quote:chideng:0" to "quote_chideng_d66bf31aab",
        "quote:chideng:1" to "quote_chideng_7540b1c4f5",
        "quote:chideng:10" to "quote_chideng_8951350430",
        "quote:chideng:11" to "quote_chideng_4b9b92e2d4",
        "quote:chideng:12" to "quote_chideng_ea14f1fee4",
        "quote:chideng:13" to "quote_chideng_14f48cf125",
        "quote:chideng:14" to "quote_chideng_58eb7f9c74",
        "quote:chideng:15" to "quote_chideng_cc2a38683c",
        "quote:chideng:16" to "quote_chideng_264790db42",
        "quote:chideng:2" to "quote_chideng_a405d5ffe0",
        "quote:chideng:3" to "quote_chideng_71d540b347",
        "quote:chideng:4" to "quote_chideng_c830b5a21b",
        "quote:chideng:5" to "quote_chideng_b0cd5ab19a",
        "quote:chideng:6" to "quote_chideng_07c1f2381e",
        "quote:chideng:7" to "quote_chideng_877d05d8a6",
        "quote:chideng:8" to "quote_chideng_7d705ef112",
        "quote:chideng:9" to "quote_chideng_24d96b0f3f",
        "quote:mitu:0" to "quote_mitu_ad4588a02f",
        "quote:mitu:1" to "quote_mitu_55599c7890",
        "quote:mitu:2" to "quote_mitu_7fd9162031",
        "quote:mitu:3" to "quote_mitu_b3dbe3f11d",
        "quote:mitu:4" to "quote_mitu_c26f8f9540",
        "quote:mitu:5" to "quote_mitu_0e5e369a82"
    )
    fun resolve(value:String)=known[value]
}
