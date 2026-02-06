// Top-level build file where you can add configuration options common to all sub-projects/modules.

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}

val submoduleDir = layout.projectDirectory.dir("app/java-llama.cpp")
val submoduleReady = submoduleDir.file("CMakeLists.txt").asFile.exists()

val ensureJavaLlamaSubmodule by tasks.registering(Exec::class) {
    onlyIf { !submoduleReady }
    description = "Initialize java-llama.cpp submodule (runs automatically before build/test)"
    group = "build setup"
    workingDir = rootDir
    commandLine("git", "submodule", "update", "--init")
}

val applyJavaLlamaPatch by tasks.registering(Exec::class) {
    dependsOn(ensureJavaLlamaSubmodule)
    description = "Apply Android NDK patch to java-llama.cpp (runs automatically before build/test)"
    group = "build setup"
    workingDir = rootDir
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    if (isWindows) {
        commandLine("cmd", "/c", "scripts\\apply-java-llama-android-patch.bat")
    } else {
        commandLine("bash", "scripts/apply-java-llama-android-patch.sh")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// After clone: first build/test will init submodule and apply patch automatically
gradle.projectsLoaded {
    rootProject.subprojects.find { it.name == "app" }?.let { appProject ->
        appProject.afterEvaluate {
            appProject.tasks.findByName("preBuild")?.dependsOn(applyJavaLlamaPatch)
            appProject.tasks.findByName("test")?.dependsOn(applyJavaLlamaPatch)
        }
    }
}
