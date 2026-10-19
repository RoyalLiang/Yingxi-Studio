plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("com.rapid7.client:dcerpc:0.12.0") { exclude(group = "org.bouncycastle") }
    implementation("com.hierynomus:sshj:0.40.0")
    testImplementation(kotlin("test"))
    testImplementation("org.apache.sshd:sshd-sftp:2.14.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}
tasks.test {
    inputs.property("smbFixturePort", System.getenv("PLAYER_SMB_FIXTURE_PORT") ?: "")
    outputs.upToDateWhen { System.getenv("PLAYER_SMB_FIXTURE_PORT").isNullOrBlank() }
    testLogging { events("passed", "failed", "skipped") }
}
tasks.register<JavaExec>("sftpFixture") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("io.localplayer.core.SftpFixture")
}
tasks.register("fixtureClasspath") {
    dependsOn(tasks.testClasses)
    doLast { layout.buildDirectory.file("fixture-classpath.txt").get().asFile.writeText(sourceSets.test.get().runtimeClasspath.asPath) }
}
