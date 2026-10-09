plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.buildkonfig) apply false // Universal build config
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" -> {
                    useVersion("4.1.137.Final")
                    because("Patches Netty HTTP/2, TLS, and DoS vulnerabilities")
                }
                requested.group == "org.bouncycastle" && (requested.name.startsWith("bcprov") || requested.name.startsWith("bcpkix")) -> {
                    useVersion("1.85")
                    because("Patches Bouncy Castle ASN.1, GOST, LDAP, and crypto vulnerabilities")
                }
                requested.group == "org.freemarker" && requested.name == "freemarker" -> {
                    useVersion("2.3.34")
                    because("Patches FreeMarker template path traversal vulnerability")
                }
                requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" -> {
                    useVersion("0.9.6")
                    because("Patches jose4j JWE decompression bomb DoS vulnerability")
                }
                requested.group == "org.jdom" && requested.name == "jdom2" -> {
                    useVersion("2.0.6.1")
                    because("Patches JDOM2 XXE vulnerability")
                }
                requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> {
                    useVersion("4.5.14")
                    because("Patches Apache HttpClient vulnerabilities")
                }
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> {
                    useVersion("3.18.0")
                    because("Patches Apache Commons Lang uncontrolled recursion vulnerability")
                }
                requested.group == "com.fasterxml.jackson.core" && (requested.name == "jackson-core" || requested.name == "jackson-databind") -> {
                    useVersion("2.13.5")
                    because("Enforces latest 2.13.x patch release maintaining minSdk 23 TV Stick compatibility")
                }
            }
        }
    }
}

