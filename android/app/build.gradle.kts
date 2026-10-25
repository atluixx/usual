import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Properties
import java.io.StringReader

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

abstract class GenerateBranding : DefaultTask() {
    @get:Input abstract val appName: Property<String>
    @get:Input abstract val logoDescription: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val logoFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val name = appName.get().trim()
        if (name.isBlank() || name.length > 60 || name.any { it.isISOControl() })
            throw GradleException("APP_NAME must contain 1–60 characters on one line.")
        val file = logoFiles.singleFile
        if (!file.isFile || !file.canRead())
            throw GradleException("APP_LOGO must point to a readable PNG file: ${logoDescription.get()}")
        if (file.length() > 10 * 1024 * 1024)
            throw GradleException("APP_LOGO must be at most 10 MiB.")
        val image = javax.imageio.ImageIO.createImageInputStream(file).use { input ->
            val readers = javax.imageio.ImageIO.getImageReaders(input)
            if (!readers.hasNext()) throw GradleException("APP_LOGO is not a valid PNG image: ${logoDescription.get()}")
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (!reader.formatName.equals("png", ignoreCase = true) || width != height || width !in 192..4096)
                    throw GradleException("APP_LOGO must be a square PNG between 192×192 and 4096×4096 pixels.")
                reader.read(0) ?: throw GradleException("APP_LOGO could not be decoded.")
            } catch (error: GradleException) { throw error }
            catch (error: Exception) { throw GradleException("APP_LOGO is invalid or damaged: ${logoDescription.get()}", error) }
            finally { reader.dispose() }
        }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        fun write(path: String, value: String) { output.resolve(path).apply { parentFile.mkdirs(); writeText(value) } }
        fun png(path: String, size: Int, logoSize: Int = size, background: Boolean = false) {
            val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = result.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                if (background) { graphics.color = Color.WHITE; graphics.fillRect(0, 0, size, size) }
                val offset = (size - logoSize) / 2
                graphics.drawImage(image, offset, offset, logoSize, logoSize, null)
            } finally { graphics.dispose() }
            val target = output.resolve(path).apply { parentFile.mkdirs() }
            javax.imageio.ImageIO.write(result, "png", target)
        }
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("@", "\\@").replace("?", "\\?")
        write("values/branding.xml", "<resources><string name=\"app_name\" translatable=\"false\" formatted=\"false\">$escaped</string></resources>\n")
        png("drawable-nodpi/brand_logo.png", 512)
        listOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192).forEach { (density, size) ->
            png("mipmap-$density/ic_launcher.png", size, (size * 0.84).toInt(), true)
            png("mipmap-$density/ic_launcher_round.png", size, (size * 0.84).toInt(), true)
        }
        // Android adaptive icons crop the outer area. Fit the logo inside the 66dp safe zone.
        png("drawable-xxxhdpi/ic_launcher_foreground.png", 432, 264)
        listOf("ic_launcher", "ic_launcher_round").forEach { icon ->
            write("mipmap-anydpi-v26/$icon.xml", "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\"><background android:drawable=\"@android:color/white\"/><foreground android:drawable=\"@drawable/ic_launcher_foreground\"/></adaptive-icon>\n")
        }
    }
}

// One configuration for every variant. Environment values override the ignored local file.
val repositoryDirectory = rootProject.layout.projectDirectory.dir("..")
val localBranding = providers.fileContents(repositoryDirectory.file("branding.local.properties")).asText.orElse("").map { text ->
    Properties().apply { load(StringReader(text)) }
}
val configuredName = providers.environmentVariable("APP_NAME").orElse(localBranding.map { it.getProperty("APP_NAME", "Usual") })
val configuredLogo = providers.environmentVariable("APP_LOGO").orElse(localBranding.map { it.getProperty("APP_LOGO", "assets/logo.png") })
val generateBranding = tasks.register<GenerateBranding>("generateBranding") {
    appName.set(configuredName)
    logoDescription.set(configuredLogo)
    val configuredPath = configuredLogo.get()
    if (configuredPath.isBlank()) throw GradleException("APP_LOGO is explicitly empty. Use a PNG path or omit APP_LOGO for the default.")
    logoFiles.from(repositoryDirectory.file(configuredPath))
    outputDirectory.set(layout.buildDirectory.dir("generated/branding/res"))
}


android {
    namespace = "com.okoda.usual"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.okoda.usual"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("live") {
            dimension = "environment"
            buildConfigField("boolean", "USE_FIREBASE_EMULATORS", "false")
        }
        create("emulator") {
            dimension = "environment"
            applicationIdSuffix = if (providers.gradleProperty("partnerEmulator").orNull == "true") ".emulator.partner" else ".emulator"
            buildConfigField("boolean", "USE_FIREBASE_EMULATORS", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateBranding, GenerateBranding::outputDirectory)
    }
    beforeVariants(selector().withFlavor("environment" to "emulator").withBuildType("release")) {
        it.enable = false
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.installations)
    implementation(libs.glance)
    implementation(libs.work)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.google.id)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
