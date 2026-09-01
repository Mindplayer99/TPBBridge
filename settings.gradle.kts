rootProject.name = "TPBBridge"

// Build only our plugin. ExampleProvider remains in the fork as template reference,
// but is intentionally disabled so CI output contains only TPBBridge.
val disabled = listOf("ExampleProvider")

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
