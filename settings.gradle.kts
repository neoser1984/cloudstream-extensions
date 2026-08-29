rootProject.name = "NeOCloudstreamExtensions"

// Bu dosya hangi projelerin dahil edileceğini belirler.
// build.gradle.kts dosyası olan her klasör otomatik olarak eklenir.

val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
