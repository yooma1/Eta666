// 国内加速镜像：默认关闭（与上游一致，GitHub runner 直连官方仓库最稳）。
// 本机/国内网络构建时设环境变量 ETA_CN_MIRRORS=1 打开。
// 历史坑：把阿里云镜像设成默认会在 GitHub runner 上解析 KSP 插件时被限流，
// 表现为 "Plugin [id: 'com.google.devtools.ksp' ...] was not found"，所以只能 opt-in。
pluginManagement {
    val cnMirrors = !System.getenv("ETA_CN_MIRRORS").isNullOrBlank()
    repositories {
        if (cnMirrors) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    val cnMirrors = !System.getenv("ETA_CN_MIRRORS").isNullOrBlank()
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
