import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.artifacts.transform.*
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*

/** dcerpc 0.12 uses two RMI exception types absent on Android. Only remap these types;
 * retain the upstream protocol implementation, signatures and license resources. */
abstract class AndroidRpc : TransformAction<TransformParameters.None> {
    @get:InputArtifact @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val input: Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val file = input.get().asFile
        if (!file.name.startsWith("dcerpc-")) { outputs.file(file); return }
        val output = outputs.file("android-${file.name}")
        ZipFile(file).use { zip -> ZipOutputStream(output.outputStream()).use { out ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                out.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                out.write(if (entry.name.endsWith(".class")) remap(bytes) else bytes)
                out.closeEntry()
            }
        } }
    }
    private fun remap(bytes: ByteArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val buffer = ByteArrayOutputStream(bytes.size)
        val out = DataOutputStream(buffer)
        check(input.readInt().also { out.writeInt(it) } == 0xCAFEBABE.toInt())
        out.writeShort(input.readUnsignedShort()); out.writeShort(input.readUnsignedShort())
        val count = input.readUnsignedShort(); out.writeShort(count)
        var index = 1
        while (index < count) {
            val tag = input.readUnsignedByte(); out.writeByte(tag)
            when (tag) {
                1 -> out.writeUTF(input.readUTF()
                    .replace("java/rmi/MarshalException", "io/localplayer/core/RpcProtocolException")
                    .replace("java/rmi/UnmarshalException", "io/localplayer/core/RpcProtocolException"))
                3, 4, 9, 10, 11, 12, 17, 18 -> out.writeInt(input.readInt())
                5, 6 -> { out.writeLong(input.readLong()); index++ }
                7, 8, 16, 19, 20 -> out.writeShort(input.readUnsignedShort())
                15 -> { out.writeByte(input.readUnsignedByte()); out.writeShort(input.readUnsignedShort()) }
                else -> error("Unsupported class constant tag $tag")
            }
            index++
        }
        input.copyTo(out)
        return buffer.toByteArray()
    }
}
val compatibleRpc = Attribute.of("io.localplayer.android-rpc", Boolean::class.javaObjectType)
dependencies {
    attributesSchema.attribute(compatibleRpc)
    artifactTypes.maybeCreate("jar").attributes.attribute(compatibleRpc, false)
    registerTransform(AndroidRpc::class) {
        from.attribute(compatibleRpc, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(compatibleRpc, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    }
}
configurations.configureEach { if (isCanBeResolved || isCanBeConsumed) attributes.attribute(compatibleRpc, true) }
