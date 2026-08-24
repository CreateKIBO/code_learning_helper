import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// ============================================================
//  CodeMentor — IntelliJ 插件构建脚本
//  使用 IntelliJ Platform Gradle Plugin 2.x（2.18.1）
//  官方文档: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
// ============================================================

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.codementor"
version = providers.gradleProperty("pluginVersion").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// ---- 官方仓库 ----
// IntelliJ Platform 2.x 的专用仓库必须通过 intellijPlatform { ... } 声明。
repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// ---- 依赖 ----
dependencies {
    intellijPlatform {
        // 目标 IDE：IDEA 社区版 2024.1.1（来自 gradle.properties）
        intellijIdeaCommunity(property("platformVersion") as String)

        // 依赖 Java 插件：提供 PsiMethod / PsiClass 等 Java PSI 能力（本项目核心）
        bundledPlugin("com.intellij.java")

        // IntelliJ Platform 测试框架
        testFramework(TestFrameworkType.Platform)
        // Java PSI / Code Insight 夹具（LightJavaCodeInsightFixtureTestCase）
        testFramework(TestFrameworkType.Plugin.Java)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("junit:junit:4.13.2")
    // IntelliJ 2024.1 的 JUnit5TestSessionListener 仍引用 junit.framework.TestCase。
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---- 插件 DSL 配置 ----
intellijPlatform {
    // 本插件没有自定义 searchable-options 关键词。关闭可选的无界面索引，
    // 避免 IDEA 2024.1 内置 Gradle 配置页在新宿主 JDK 上输出无关异常。
    buildSearchableOptions = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        changeNotes = """
            <h3>1.2.1 — 2026-08-24</h3>
            <ul>
                <li>Fixed API Key status checks and connection-test results remaining in a loading state while the modal Settings dialog is open.</li>
                <li>Settings completion callbacks now run as guarded pure UI updates in the active modal dialog, and rejected background submissions restore the visible controls instead of leaving them busy.</li>
                <li>Applied the 30-second model request timeout to the complete bounded response body, preventing a server that stalls after response headers from blocking connection tests or learning requests indefinitely.</li>
                <li>Kept the 2 MiB response limit for both declared and chunked responses without materializing an unbounded body.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            // 仅支持 2024.1.x（241.*），不跨大版本（个人本地工具，按需收窄）
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    pluginVerification {
        ides {
            current()
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // 生成插件时使用平台 JDK 17
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    processResources {
        // 让离线安装包自身携带发布与隐私条款，而不是只依赖仓库页面。
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        from(rootProject.file("NOTICE")) {
            into("META-INF")
        }
        from(rootProject.file("PRIVACY.md")) {
            into("META-INF")
        }
    }

    test {
        useJUnitPlatform()
    }
}
