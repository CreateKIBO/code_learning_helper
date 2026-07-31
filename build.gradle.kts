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

// ---- 仓库 ----
// 注意：2.x 要求 IntelliJ Platform 专用仓库用 intellijPlatform { ... } 块声明
repositories {
    // 阿里云镜像放最前，加速国内下载（mavenCentral 内容）
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
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

        ideaVersion {
            // 仅支持 2024.1.x（241.*），不跨大版本（个人本地工具，按需收窄）
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    // 构建校验：当前不发布，关掉避免缺网络/版本时构建失败
    pluginVerification {
        // 占位：发布前再启用 Plugin Verifier
    }
}

tasks {
    // 禁用 2.x 默认开启但本项目不用的可选任务，避免构建警告
    val verifyPlugin by getting { /* 默认即可 */ }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        // 生成插件时使用平台 JDK 17
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    test {
        useJUnitPlatform()
    }
}
