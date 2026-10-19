package io.localplayer.core

import java.io.IOException

/** Android-compatible replacement for upstream RPC marshal/unmarshal failures. */
class RpcProtocolException : IOException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Exception) : super(message, cause)
}
