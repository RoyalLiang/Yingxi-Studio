import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }

val configuredVersion = groovy.json.JsonSlurper().parse(rootProject.file("version.json")) as Map<*, *>
require(configuredVersion.keys == setOf("versionName", "versionCode")) { "version.json requires versionName and versionCode only" }
val appVersionName = providers.gradleProperty("yingxiVersionName").getOrElse(configuredVersion["versionName"] as String)
val configuredCode = configuredVersion["versionCode"]
require(configuredCode is Int || configuredCode is Long) { "versionCode must be an integer" }
val appVersionCode = providers.gradleProperty("yingxiVersionCode").orNull?.toLongOrNull()
    ?: if (providers.gradleProperty("yingxiVersionCode").isPresent) error("yingxiVersionCode must be an integer") else (configuredCode as Number).toLong()
val number = "(?:0|[1-9][0-9]*)"
val pre = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
require(Regex("$number\\.$number\\.$number(?:-$pre(?:\\.$pre)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?").matches(appVersionName)) { "versionName must be valid SemVer" }
require(appVersionCode in 1L..2100000000L) { "versionCode must be in 1..2100000000" }
val notesFile = rootProject.file("releases/v$appVersionName.md")
val versionChanges = if (notesFile.isFile) notesFile.readText().substringAfter("## 中文", "")
    .substringBefore("## English").substringBefore("### 验证与限制").lineSequence()
    .filter { it.startsWith("- ") }.joinToString("\n") { it.removePrefix("- ") } else ""

val signingNames = listOf("YINGXI_KEYSTORE_PATH", "YINGXI_KEYSTORE_PASSWORD", "YINGXI_KEY_ALIAS", "YINGXI_KEY_PASSWORD")
val signing = signingNames.associateWith { providers.environmentVariable(it).orNull }
require(signing.values.all { it.isNullOrBlank() } || signing.values.all { !it.isNullOrBlank() }) { "Release signing configuration is incomplete" }
val hasReleaseSigning = signing.values.all { !it.isNullOrBlank() }
val generatedNotices = layout.buildDirectory.dir("generated/notices")
val generateNotices by tasks.registering {
    dependsOn(":core:jar")
    outputs.dir(generatedNotices)
    inputs.dir(rootProject.file("third_party"))
    outputs.upToDateWhen { false }
    doLast {
        val output=generatedNotices.get().asFile.apply { mkdirs() }
        val notices=StringBuilder("Yingxi third-party notices\nDependencies retain their original licenses.\n\n")
        listOf("apache-2.0.txt","sshj-NOTICE.txt","smbj-copyright.txt","bouncycastle-LICENSE.txt","mbassador-LICENSE.txt","slf4j-LICENSE.txt").forEach { name ->
            check(rootProject.file("third_party/$name").let { it.isFile && it.length()>100 }) { "Missing upstream license supplement: $name" }
        }
        rootProject.file("third_party").listFiles()?.filter { it.extension in setOf("txt","json") }?.sortedBy { it.name }?.forEach { file ->
            notices.append("\n=== Supplement: ${file.name} ===\n").append(file.readText()).append('\n')
        }
        val inventory=mutableListOf<Map<String,String>>()
        configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.sortedBy { it.moduleVersion.id.toString() }.forEach { artifact ->
            val file=artifact.file
            if(file.isFile){
                val coordinate=artifact.moduleVersion.id.toString()
                val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
                inventory+=mapOf("module" to coordinate,"sha256" to hash)
                notices.append("\n=== $coordinate ===\n")
                if(file.extension in setOf("jar","aar"))ZipFile(file).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.size in 1L..524288L && it.name.uppercase().let { name -> name.contains("META-INF/") && (name.contains("LICENSE") || name.contains("LICENCE") || name.contains("NOTICE")) } }.forEach { entry ->
                        notices.append("--- ${entry.name} ---\n").append(zip.getInputStream(entry).reader(Charsets.UTF_8).readText()).append('\n')
                    }
                }
            }
        }
        output.resolve("third-party-notices.txt").writeText(notices.toString())
        output.resolve("dependencies.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(inventory)))
    }
}
android {
    namespace = "io.localplayer.app"
    compileSdk = 36
    defaultConfig { applicationId = "io.localplayer.app"; minSdk = 35; targetSdk = 36; versionCode = appVersionCode.toInt(); versionName = appVersionName; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    defaultConfig {
        buildConfigField("String", "VERSION_CHANGES", groovy.json.JsonOutput.toJson(versionChanges))
        mapOf("PLAYER_TEST_PASSWORD" to "fixture_password", "PLAYER_TEST_USERNAME" to "fixture_username", "PLAYER_SMB_FIXTURE_PORT" to "fixture_smb_port", "PLAYER_SFTP_FIXTURE_PORT" to "fixture_sftp_port").forEach { (env, argument) ->
            providers.environmentVariable(env).orNull?.let { testInstrumentationRunnerArguments[argument] = it }
        }
    }
    signingConfigs {
        if(hasReleaseSigning) create("release") {
            storeFile = file(signing.getValue("YINGXI_KEYSTORE_PATH")!!)
            storePassword = signing.getValue("YINGXI_KEYSTORE_PASSWORD")
            keyAlias = signing.getValue("YINGXI_KEY_ALIAS")
            keyPassword = signing.getValue("YINGXI_KEY_PASSWORD")
        }
    }
    buildTypes.getByName("release") {
        isMinifyEnabled = false
        if(hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(generatedNotices)
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}
tasks.named("preBuild").configure { dependsOn(generateNotices) }
val verifyReleaseSigning by tasks.registering {
    doLast { check(hasReleaseSigning) { "Signed alpha release requires YINGXI_KEYSTORE_PATH, YINGXI_KEYSTORE_PASSWORD, YINGXI_KEY_ALIAS and YINGXI_KEY_PASSWORD" } }
}
tasks.matching { it.name in setOf("assembleRelease", "packageRelease", "bundleRelease") }.configureEach { dependsOn(verifyReleaseSigning) }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
