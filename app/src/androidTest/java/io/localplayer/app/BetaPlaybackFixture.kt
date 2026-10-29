package io.localplayer.app

internal fun betaPlaybackFixture(context:android.content.Context):java.io.File = java.io.File(context.cacheDir,"beta-control.mp4").apply {
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("upgrade-resume.mp4").use{input->outputStream().use{input.copyTo(it)}}
}
