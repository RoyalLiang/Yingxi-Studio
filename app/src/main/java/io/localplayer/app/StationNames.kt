package io.localplayer.app

import io.localplayer.core.Protocol
import io.localplayer.core.Source

/** Connection categories are application labels, not new places on the story map. */
internal val Source.stationKind: String
    get() = when (protocol) {
        Protocol.WEBDAV, Protocol.ALIYUN -> "信号塔"
        Protocol.SMB, Protocol.SFTP -> "据点"
    }
