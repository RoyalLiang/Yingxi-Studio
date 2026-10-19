package io.localplayer.core

import java.net.URI

data class SmbAddress(val host: String, val port: Int, val share: String, val path: String) {
    companion object {
        fun parse(input: String): SmbAddress {
            val raw = input.trim().replace('\\', '/')
            require(raw.isNotBlank()) { "请填写服务器地址" }
            require(!raw.contains("://") || raw.startsWith("smb://", true)) { "请填写 SMB 地址" }
            val uri = try { URI((if (raw.startsWith("smb://", true)) raw else "smb://" + raw.removePrefix("//")).replace(" ", "%20")) }
            catch (e: java.net.URISyntaxException) { throw IllegalArgumentException("服务器地址格式无效，请核对转义字符与路径", e) }
            require(uri.scheme.equals("smb", true) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "请使用 SMB 地址；账户和密码请填在独立输入框中"
            }
            val host = uri.host?.removeSurrounding("[", "]") ?: throw IllegalArgumentException("服务器地址格式无效")
            val port = if (uri.port == -1) 445 else uri.port
            require(port in 1..65535) { "端口应为 1–65535" }
            val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            val share = parts.firstOrNull().orEmpty()
            require(share != "." && share != ".." && share.none { it == '\\' || it == '\u0000' }) { "共享名格式无效" }
            return SmbAddress(host, port, share, Paths.clean(parts.drop(1).joinToString("/")))
        }
    }
}
