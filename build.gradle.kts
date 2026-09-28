import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    java
    `maven-publish`
    jacoco
    id("com.vanniktech.maven.publish") version "0.37.0"
    signing
}

group = providers.gradleProperty("projectGroup").get()
version = providers.gradleProperty("projectVersion").get()

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

tasks.compileJava {
    options.encoding = "UTF-8"
    // 强制 Java 8 的语法与 API 表面。
    // 构建使用 JDK 17，若只设 source/target 而不设 release，误用 List.of() 之类的
    // Java 9+ API 会静默编译通过，直到在 Java 8 运行时才抛 NoSuchMethodError——
    // 而本项目要支持到 1.7.10（Java 8），这类问题必须在此拦下。
    options.release.set(8)
}

tasks.compileTestJava {
    options.encoding = "UTF-8"
    options.release.set(8)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:${property("gsonVersion")}")
    implementation("commons-io:commons-io:${property("commonsIoVersion")}")
    implementation("org.yaml:snakeyaml:${property("snakeyamlVersion")}")
    implementation("org.java-websocket:Java-WebSocket:${property("javaWebSocketVersion")}")
    implementation("org.slf4j:slf4j-api:${property("slf4jApiVersion")}")
    implementation("org.glavo:rcon-java:${property("glavoRconVersion")}")

    testImplementation("org.slf4j:slf4j-simple:${property("slf4jSimpleVersion")}")
    testImplementation("org.junit.jupiter:junit-jupiter-api:${property("junitJupiterApiVersion")}")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${property("junitJupiterApiVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${property("junitJupiterPlatformLauncherVersion")}")
}

tasks.test {
    useJUnitPlatform()

    // 测试隔离（第一道安全网）：把测试的工作目录指向 build 目录。
    // 配置加载会以「相对工作目录」解析路径（plugins/queqiao/config.yml），
    // 若不重定向，测试会读写项目根目录下的真实配置文件。
    // 配置相关用例自身还会用 @TempDir 做逐用例隔离（第二道）。
    val testWorkDir = layout.buildDirectory.dir("test-workdir").get().asFile
    doFirst { testWorkDir.mkdirs() }
    workingDir = testWorkDir

    finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<Javadoc> {
    options {
        this as StandardJavadocDocletOptions
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        links("https://docs.oracle.com/javase/8/docs/api/")
        if (JavaVersion.current().isJava9Compatible) {
            addBooleanOption("html5", true)
        }
        addBooleanOption("Xdoclint:none", true)
    }
    classpath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}

tasks.named("check") {
    dependsOn("jacocoTestReport")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

mavenPublishing {
    coordinates(
        groupId = providers.gradleProperty("projectGroup").get(),
        artifactId = providers.gradleProperty("artifactId").get(),
        version = providers.gradleProperty("projectVersion").get()
    )
    publishToMavenCentral()

    signAllPublications()

    pom {
        name.set(providers.gradleProperty("name"))
        description.set(providers.gradleProperty("description"))
        url.set(providers.gradleProperty("url"))
        licenses {
            license {
                name.set("The MIT License")
                url.set("https://opensource.org/license/mit/")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set(providers.gradleProperty("developerId"))
                name.set(providers.gradleProperty("developerName"))
            }
        }
        scm {
            url.set(providers.gradleProperty("url"))
            connection.set("scm:git:" + providers.gradleProperty("url").get() + ".git")
            developerConnection.set(
                "scm:git:ssh://github.com/" + providers.gradleProperty("githubRepository").get() + ".git"
            )
        }
    }
}

signing {
    isRequired = providers.gradleProperty("signingRequired")
        .map(String::toBoolean)
        .orElse(false)
        .get()
}
