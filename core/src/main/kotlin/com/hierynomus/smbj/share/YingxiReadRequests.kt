package com.hierynomus.smbj.share

import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import java.util.concurrent.Future

/** Compile-time adapter for SMBJ 0.13's package-scoped async read, also used by
 * its own FileInputStream. No reflection, transport bypass or signing changes. */
internal fun File.yingxiReadAsync(position:Long,length:Int):Future<SMB2ReadResponse> = readAsync(position,length)
