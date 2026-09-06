import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.BaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // ! JitPack'in "com.github.recloudstream.gradle:gradle" üzerindeki HER sürümü
        // ! (hem "master-SNAPSHOT" hem sabit commit sürümleri) şu an bozuk metadata
        // ! üretiyor ve derlemeyi engelliyor (bkz: inconsistent module metadata /
        // ! "master-aster-SNAPSHOT" hatası). Bu yüzden JitPack'e artık hiç güvenmiyoruz:
        // ! CI, bu eklentiyi kaynağından (recloudstream/gradle) kendi derleyip
        // ! local-libs/cloudstream-gradle-plugin.jar olarak buraya koyuyor
        // ! (bkz: .github/workflows/Derleyici.yml). Yerelde derlerken bu jar yoksa
        // ! önce recloudstream/gradle'ı klonlayıp "./gradlew jar" ile üretmen gerekir.
        classpath(files("local-libs/cloudstream-gradle-plugin.jar"))
        classpath("org.ow2.asm:asm:9.9.1")
        classpath("org.ow2.asm:asm-tree:9.9.1")
        classpath("com.github.vidstige:jadb:v1.2.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()
fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // GitHub Actions üzerinden çalışırken GITHUB_REPOSITORY kendiliğinden doğru repoyu verir.
        // Yerelde derlerken aşağıdaki adresi KENDİ reponla değiştir.
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/neoser1984/cloudstream-extensions")

        authors = listOf("NeO")
    }

    android {
        namespace = "com.neo.cloudstream"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    listOf(
                        "-Xno-call-assertions",
                        "-Xno-param-assertions",
                        "-Xno-receiver-assertions"
                    )
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
