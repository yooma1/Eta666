// 国内加速镜像默认开启（本机/CN 网络用）。CI 或海外网络设环境变量 ETA_NO_CN_MIRRORS=1 关掉，
// 直接走 google()/mavenCentral()/gradlePluginPortal()。
// 背景：com.google.devtools.ksp 的新版本号（如 2.3.12）只出现在阿里云 gradle-plugin 镜像，
// Google Maven 上没有；GitHub runner 在境外访问阿里云镜像会被限流/拒绝，插件解析直接失败。
pluginManagement {
    val cnMirrors = System.getenv("ETA_NO_CN_MIRRORS").isNullOrBlank()
    repositories {
        if (cnMirrors) {
            maven("https://maven.aliyun.com/repository/google")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        if (cnMirrors) {
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    val cnMirrors = System.getenv("ETA_NO_CN_MIRRORS").isNullOrBlank()
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (cnMirrors) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Eta"
include(":app")
